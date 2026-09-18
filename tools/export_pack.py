"""Пак голосов Silero v5 для RuVoice (спека 2026-09-18):
dist/ruvoice-pack-<id>.zip (pack.json + tts_mel.ptl + backbone.pte + head.ptl); для cis_ru ещё
app/src/test/resources/golden_pack.json (pack.json и ids golden-фразы для JVM-теста).
Запуск: ../venv-et/bin/python tools/export_pack.py cis_ru | ru
(модель для id скачать заранее в tools/, см. source в PACKS)"""
import json, os, sys, tempfile, zipfile
import torch
from torch.jit.mobile import _load_for_lite_interpreter
from silero_export import split_tts, export_backbone, verify_backbone

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DIST = os.path.join(ROOT, 'dist')
GOLDEN = os.path.join(ROOT, 'app/src/test/resources/golden_pack.json')
FORMAT = 2   # Packs.FORMAT в приложении: три файла моделей, forward на 11 аргументов
PACKS = {
    # id: (файл модели, фильтр голосов, types, title, license, source, golden-голос)
    'cis_ru': ('v5_cis_base_nostress.pt', lambda s: s.startswith('ru_'), False, 'Русские голоса Silero v5 CIS', 'MIT',
               'https://models.silero.ai/models/tts/ru/v5_cis_base_nostress.pt', 'ru_alexandr'),
    'ru': ('v5_5_ru.pt', lambda s: True, True, 'Штатные голоса Silero v5.5', 'CC BY-NC-SA 4.0',
           'https://models.silero.ai/models/tts/ru/v5_5_ru.pt', 'xenia'),
}
TEXT = 'прив+ет, м+ир.'


def tts_args(pk, text, sr=48000):
    """11 аргументов forward (как у v5_ru: без type_ids и focus_mask), speaker подставляется вызывающим."""
    seq, _ = pk.preprocess_tacotron(text); seq = seq.unsqueeze(0); n = seq.shape[1]
    return seq, sr, torch.ones(1, n), torch.ones(1, n)


def forward(model, pk, text, speaker_id, types, sr=48000):
    """11 аргументов forward (как у v5_ru) или 13 (v5_5_ru: type_ids нули, focus_mask None)."""
    seq, sr, rates, pitches = tts_args(pk, text, sr)
    args = [seq, torch.LongTensor([speaker_id]), sr, None, rates, pitches, None, None, 'cpu', -1, False]
    if types: args += [torch.zeros(1, seq.shape[1], dtype=torch.long), None]
    return model(*args)


if __name__ == '__main__':
    pack_id = sys.argv[1] if len(sys.argv) > 1 else 'cis_ru'
    pt, keep, types, title, lic, source, golden_speaker = PACKS[pack_id]
    PT = os.path.join(HERE, pt)
    big = torch.package.PackageImporter(PT).load_pickle('tts_models', 'model')
    pk = big.packages[0]
    assert len(big.packages) == 1 and len(pk.models) == 1, 'ожидалась одна модель'
    assert (pk.accentor is None) == (not types), 'акцентор не соответствует types'
    tts = pk.models[0]
    speakers = {s: i for s, i in pk.speaker_to_ids[0].items() if keep(s)}
    assert len(speakers) == {'cis_ru': 29, 'ru': 5}[pack_id], len(speakers)
    pack = {
        'format': FORMAT, 'id': pack_id, 'title': title, 'license': lic,
        'source': source,
        'symbols': pk.symbols, 'symbol_to_id': pk.symbol_to_id, 'sos': pk.sos_token, 'eos': pk.eos_token,
        'alphabet': ''.join(pk.alphabet), 'types': types, 'speakers': speakers,
    }
    sid = speakers[golden_speaker]
    with torch.no_grad():  # эталоны до хирургии графа
        ref, ref_durs = forward(tts, pk, TEXT, sid, types)
        ref24, _ = forward(tts, pk, TEXT, sid, types, 24000)
    os.makedirs(DIST, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        mel_p, head_p, bb_p = (os.path.join(tmp, n) for n in ('tts_mel.ptl', 'head.ptl', 'backbone.pte'))
        split_tts(tts, mel_p, head_p)
        lite_m = _load_for_lite_interpreter(mel_p); lite_head = _load_for_lite_interpreter(head_p)
        with torch.no_grad():
            mel, durs = forward(lite_m, pk, TEXT, sid, types)
            ref_vocoder = tts.vocoder.backbone(mel)
            a = lite_head(ref_vocoder, 48000, 0., True); a24 = lite_head(ref_vocoder, 24000, 0., True)
        assert (a - ref).abs().max().item() == 0 and (a24 - ref24).abs().max().item() == 0, 'tts_mel+head mismatch'
        assert torch.equal(durs, ref_durs), 'durs mismatch'
        export_backbone(tts.vocoder.backbone.state_dict(), mel.contiguous(), bb_p)
        d = verify_backbone(bb_p, head_p, mel.contiguous(), ref)
        assert d < 1e-3, f'pte mismatch {d}'
        out = os.path.join(DIST, f'ruvoice-pack-{pack_id}.zip')
        with zipfile.ZipFile(out, 'w') as zf:
            zf.writestr('pack.json', json.dumps(pack, ensure_ascii=False), compress_type=zipfile.ZIP_DEFLATED)
            for n, p in (('tts_mel.ptl', mel_p), ('backbone.pte', bb_p), ('head.ptl', head_p)):
                zf.write(p, n, compress_type=zipfile.ZIP_STORED)
    if pack_id == 'cis_ru':
        seq, _ = pk.preprocess_tacotron(TEXT)
        for p in (GOLDEN, os.path.join(ROOT, 'app/src/androidTest/assets/golden_pack.json')):
            with open(p, 'w', encoding='utf-8') as f:
                json.dump({'pack': pack, 'text': TEXT, 'speaker': golden_speaker, 'ids': seq.tolist()}, f, ensure_ascii=False, indent=1)
    print('verify: ok, pte maxdiff', d)
    print(out, round(os.path.getsize(out) / 1048576, 1), 'MB;', len(speakers), 'голосов')
