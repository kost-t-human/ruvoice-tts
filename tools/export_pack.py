"""Пак русских голосов ru_* из Silero v5_cis_base_nostress для RuVoice (спека 2026-09-18):
dist/ruvoice-pack-cis_ru.zip (pack.json + tts_mel.ptl + backbone.pte + head.ptl) и
app/src/test/resources/golden_pack.json (pack.json и ids golden-фразы для JVM-теста).
Запуск: ../venv-et/bin/python tools/export_pack.py   (tools/v5_cis_base_nostress.pt скачать заранее:
curl -L -o tools/v5_cis_base_nostress.pt https://models.silero.ai/models/tts/ru/v5_cis_base_nostress.pt)"""
import json, os, tempfile, zipfile
import torch
from torch.jit.mobile import _load_for_lite_interpreter
from silero_export import split_tts, export_backbone, verify_backbone

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
PT = os.path.join(HERE, 'v5_cis_base_nostress.pt')
DIST = os.path.join(ROOT, 'dist')
GOLDEN = os.path.join(ROOT, 'app/src/test/resources/golden_pack.json')
FORMAT = 2   # Packs.FORMAT в приложении: три файла моделей, forward на 11 аргументов
PACK_ID = 'cis_ru'
TEXT, SPEAKER = 'прив+ет, м+ир.', 'ru_alexandr'


def tts_args(pk, text, sr=48000):
    """11 аргументов forward (как у v5_ru: без type_ids и focus_mask), speaker подставляется вызывающим."""
    seq, _ = pk.preprocess_tacotron(text); seq = seq.unsqueeze(0); n = seq.shape[1]
    return seq, sr, torch.ones(1, n), torch.ones(1, n)


def forward(model, pk, text, speaker_id, sr=48000):
    seq, sr, rates, pitches = tts_args(pk, text, sr)
    return model(seq, torch.LongTensor([speaker_id]), sr, None, rates, pitches, None, None, 'cpu', -1, False)


if __name__ == '__main__':
    big = torch.package.PackageImporter(PT).load_pickle('tts_models', 'model')
    pk = big.packages[0]
    assert len(big.packages) == 1 and len(pk.models) == 1 and pk.accentor is None, 'ожидалась одна модель без акцентора'
    tts = pk.models[0]
    speakers = {s: i for s, i in pk.speaker_to_ids[0].items() if s.startswith('ru_')}
    assert len(speakers) == 29, len(speakers)
    pack = {
        'format': FORMAT, 'id': PACK_ID, 'title': 'Русские голоса Silero v5 CIS', 'license': 'MIT',
        'source': 'https://models.silero.ai/models/tts/ru/v5_cis_base_nostress.pt',
        'symbols': pk.symbols, 'symbol_to_id': pk.symbol_to_id, 'sos': pk.sos_token, 'eos': pk.eos_token,
        'alphabet': ''.join(pk.alphabet), 'types': False, 'speakers': speakers,
    }
    sid = speakers[SPEAKER]
    with torch.no_grad():  # эталоны до хирургии графа
        ref, ref_durs = forward(tts, pk, TEXT, sid)
        ref24, _ = forward(tts, pk, TEXT, sid, 24000)
    os.makedirs(DIST, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        mel_p, head_p, bb_p = (os.path.join(tmp, n) for n in ('tts_mel.ptl', 'head.ptl', 'backbone.pte'))
        split_tts(tts, mel_p, head_p)
        lite_m = _load_for_lite_interpreter(mel_p); lite_head = _load_for_lite_interpreter(head_p)
        with torch.no_grad():
            mel, durs = forward(lite_m, pk, TEXT, sid)
            ref_vocoder = tts.vocoder.backbone(mel)
            a = lite_head(ref_vocoder, 48000, 0., True); a24 = lite_head(ref_vocoder, 24000, 0., True)
        assert (a - ref).abs().max().item() == 0 and (a24 - ref24).abs().max().item() == 0, 'tts_mel+head mismatch'
        assert torch.equal(durs, ref_durs), 'durs mismatch'
        export_backbone(tts.vocoder.backbone.state_dict(), mel.contiguous(), bb_p)
        d = verify_backbone(bb_p, head_p, mel.contiguous(), ref)
        assert d < 1e-3, f'pte mismatch {d}'
        out = os.path.join(DIST, f'ruvoice-pack-{PACK_ID}.zip')
        with zipfile.ZipFile(out, 'w') as zf:
            zf.writestr('pack.json', json.dumps(pack, ensure_ascii=False), compress_type=zipfile.ZIP_DEFLATED)
            for n, p in (('tts_mel.ptl', mel_p), ('backbone.pte', bb_p), ('head.ptl', head_p)):
                zf.write(p, n, compress_type=zipfile.ZIP_STORED)
    seq, _ = pk.preprocess_tacotron(TEXT)
    with open(GOLDEN, 'w', encoding='utf-8') as f:
        json.dump({'pack': pack, 'text': TEXT, 'speaker': SPEAKER, 'ids': seq.tolist()}, f, ensure_ascii=False, indent=1)
    print('verify: ok, pte maxdiff', d)
    print(out, round(os.path.getsize(out) / 1048576, 1), 'MB;', len(speakers), 'голосов')
