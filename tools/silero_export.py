"""Общее для export_silero.py, vocoder_et.py и export_pack.py: хирургия графа tts (мел отдельно от
вокодера, голова вокодера отдельно от бэкбона) и бэкбон ConvNeXt как eager-модуль для ExecuTorch.
executorch импортируется лениво — export_silero.py работает и без него."""
import torch
from torch import nn

D = 512


def cut_call(graph, submodule):
    """Вызов submodule.forward в графе заменяем его входом; freeze потом выкинет лишние веса."""
    for node in graph.nodes():
        if node.kind() == 'prim::CallMethod' and node.s('name') == 'forward' and submodule in str(node.inputsAt(0)):
            node.output().replaceAllUsesWith(node.inputsAt(1)); node.destroy(); return
    raise SystemExit(f'вызов {submodule}.forward не найден')


def split_tts(tts, mel_path, head_path):
    """head.ptl = вокодер без бэкбона (голова, iSTFT, PQMF для 24 кГц), tts_mel.ptl = tts без вокодера
    (отдаёт мел). Меняет графы tts на месте; tts.vocoder.backbone остаётся вызываемым."""
    cut_call(tts.vocoder.forward.graph, 'backbone')
    torch.jit.freeze(tts.vocoder.eval())._save_for_lite_interpreter(head_path)
    cut_call(tts.forward.graph, 'vocoder')
    torch.jit.freeze(tts.eval())._save_for_lite_interpreter(mel_path)


class Block(nn.Module):
    def __init__(self):
        super().__init__()
        self.dwconv = nn.Conv1d(D, D, 7, padding=3, groups=D); self.norm = nn.LayerNorm(D, eps=1e-6)
        self.pwconv1 = nn.Linear(D, 3 * D); self.pwconv2 = nn.Linear(3 * D, D); self.gamma = nn.Parameter(torch.ones(D))

    def forward(self, x):
        y = self.dwconv(x).transpose(1, 2)
        y = self.pwconv2(nn.functional.gelu(self.pwconv1(self.norm(y))))
        return x + (self.gamma * y).transpose(1, 2)


class Backbone(nn.Module):
    """Conv1d + 8 ConvNeXt + LayerNorm — та же архитектура у v5_5_ru и v5_cis_base_nostress (веса разные)."""
    def __init__(self):
        super().__init__()
        self.embed = nn.Conv1d(192, D, 7, padding=3); self.norm = nn.LayerNorm(D, eps=1e-6)
        self.convnext = nn.ModuleList([Block() for _ in range(8)]); self.final_layer_norm = nn.LayerNorm(D, eps=1e-6)

    def forward(self, x):
        x = self.norm(self.embed(x).transpose(1, 2)).transpose(1, 2)
        for b in self.convnext: x = b(x)
        return self.final_layer_norm(x.transpose(1, 2))


def export_backbone(state_dict, mel, out_path):
    """Бэкбон с этими весами → .pte (XNNPACK, fp32). T — кадры мела по 12,5 мс; 6000 = 75 с,
    приложение режет текст до 900 символов (~3000 кадров)."""
    from torch.export import Dim, export
    from executorch.exir import to_edge_transform_and_lower
    from executorch.backends.xnnpack.partition.xnnpack_partitioner import XnnpackPartitioner
    bb = Backbone().eval(); bb.load_state_dict(state_dict)
    ep = export(bb, (mel,), dynamic_shapes={'x': {2: Dim('T', min=4, max=6000)}})
    et = to_edge_transform_and_lower(ep, partitioner=[XnnpackPartitioner()]).to_executorch()
    with open(out_path, 'wb') as f: f.write(et.buffer)


def verify_backbone(out_path, head_path, mel, ref):
    """head.ptl(pte(mel)) против ref (звук полного вокодера на том же меле, 48 кГц) — maxdiff."""
    from torch.jit.mobile import _load_for_lite_interpreter
    from executorch.runtime import Runtime
    head = _load_for_lite_interpreter(head_path)
    with torch.no_grad():
        h = Runtime.get().load_program(out_path).load_method('forward').execute([mel])[0]
        return (head(h, 48000, 0., True) - ref).abs().max().item()
