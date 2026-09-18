"""Бэкбон вокодера v5_5_ru (Conv1d + 8 ConvNeXt + LayerNorm) как eager-модуль → ExecuTorch .pte с XNNPACK, fp32.
Веса из v5_5_ru.pt, вход мел (1, 192, T). Запуск после export_silero.py: ../venv-et/bin/python tools/vocoder_et.py
Голова вокодера (Linear, exp/cos/sin, iSTFT) остаётся в head.ptl: комплексных чисел и istft в core ATen нет,
а iSTFT как ConvTranspose1d в XNNPACK в 5 раз медленнее lite. fp16 отвергнут: XNNPACK на ARM копит в fp16,
на golden-наборе log-STFT 0,05 против порога 0,05 и SNR до 20 дБ. Сам бэкбон и экспорт — silero_export.py."""
import os, torch
from silero_export import Backbone, export_backbone, verify_backbone

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(os.path.dirname(HERE), 'app/src/full/assets/silero')  # модель только в сборке full

if __name__ == '__main__':
    pk = torch.package.PackageImporter(os.path.join(HERE, 'v5_5_ru.pt')).load_pickle('tts_models', 'model').packages[0]
    orig = pk.models[0].vocoder
    bb = Backbone().eval()
    bb.load_state_dict(orig.backbone.state_dict())
    mel = torch.load(os.path.join(HERE, 'mel_sample.pt'))
    with torch.no_grad():
        ref = orig(mel, 48000, 0., True)
        assert (bb(mel) - orig.backbone(mel)).abs().max().item() < 1e-4, 'eager backbone mismatch'
    out = os.path.join(ASSETS, 'backbone.pte')
    export_backbone(orig.backbone.state_dict(), mel, out)
    d = verify_backbone(out, os.path.join(ASSETS, 'head.ptl'), mel, ref)
    assert d < 1e-3, f'pte mismatch {d}'
    print('backbone.pte', round(os.path.getsize(out) / 1048576, 1), 'MB, maxdiff', d)
