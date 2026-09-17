"""Бэкбон вокодера v5_5_ru (Conv1d + 8 ConvNeXt + LayerNorm) как eager-модуль → ExecuTorch .pte с XNNPACK, fp32.
Веса из v5_5_ru.pt, вход мел (1, 192, T). Запуск после export_silero.py: ../venv-et/bin/python tools/vocoder_et.py
Голова вокодера (Linear, exp/cos/sin, iSTFT) остаётся в head.ptl: комплексных чисел и istft в core ATen нет,
а iSTFT как ConvTranspose1d в XNNPACK в 5 раз медленнее lite. fp16 отвергнут: XNNPACK на ARM копит в fp16,
на golden-наборе log-STFT 0,05 против порога 0,05 и SNR до 20 дБ."""
import os, torch
from torch import nn
from torch.export import Dim, export
from torch.jit.mobile import _load_for_lite_interpreter
from executorch.exir import to_edge_transform_and_lower
from executorch.backends.xnnpack.partition.xnnpack_partitioner import XnnpackPartitioner

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(os.path.dirname(HERE), 'app/src/main/assets/silero')
D = 512


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
    def __init__(self):
        super().__init__()
        self.embed = nn.Conv1d(192, D, 7, padding=3); self.norm = nn.LayerNorm(D, eps=1e-6)
        self.convnext = nn.ModuleList([Block() for _ in range(8)]); self.final_layer_norm = nn.LayerNorm(D, eps=1e-6)

    def forward(self, x):
        x = self.norm(self.embed(x).transpose(1, 2)).transpose(1, 2)
        for b in self.convnext: x = b(x)
        return self.final_layer_norm(x.transpose(1, 2))


if __name__ == '__main__':
    pk = torch.package.PackageImporter(os.path.join(HERE, 'v5_5_ru.pt')).load_pickle('tts_models', 'model').packages[0]
    orig = pk.models[0].vocoder
    bb = Backbone().eval()
    bb.load_state_dict(orig.backbone.state_dict())
    mel = torch.load(os.path.join(HERE, 'mel_sample.pt'))
    with torch.no_grad():
        ref = orig(mel, 48000, 0., True)
        assert (bb(mel) - orig.backbone(mel)).abs().max().item() < 1e-4, 'eager backbone mismatch'
    # T — кадры мела по 12,5 мс; 6000 = 75 с, приложение режет текст до 900 символов (~3000 кадров)
    ep = export(bb, (mel,), dynamic_shapes={'x': {2: Dim('T', min=4, max=6000)}})
    et = to_edge_transform_and_lower(ep, partitioner=[XnnpackPartitioner()]).to_executorch()
    out = os.path.join(ASSETS, 'backbone.pte')
    with open(out, 'wb') as f: f.write(et.buffer)
    from executorch.runtime import Runtime
    head = _load_for_lite_interpreter(os.path.join(ASSETS, 'head.ptl'))
    with torch.no_grad():
        h = Runtime.get().load_program(out).load_method('forward').execute([mel])[0]
        d = (head(h, 48000, 0., True) - ref).abs().max().item()
    assert d < 1e-3, f'pte mismatch {d}'
    print('backbone.pte', round(os.path.getsize(out) / 1048576, 1), 'MB, maxdiff', d)
