"""Экспорт пака Silero v5 (cis_base_nostress, turkic, caucasian) для RuVoice:
dist/ruvoice-pack-<id>.zip (tts.ptl + pack.json) и app/src/test/resources/golden_packs.json.
Запуск: python3 tools/export_pack.py tools/v5_turkic.pt [tools/v5_caucasian.pt ...]"""
import json, os, sys, tempfile, zipfile
import torch
from torch.jit.mobile import _load_for_lite_interpreter

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DIST = os.path.join(ROOT, 'dist')
GOLDEN = os.path.join(ROOT, 'app/src/test/resources/golden_packs.json')
FORMAT = 1
SKIP = {'ukr', 'bel', 'ru'}  # славянские ждут «+» в каждом слове; ru_* — русские голоса, не в этом релизе
CODE_FIX = {'darg': 'dar', 'erz': 'myv'}   # у Silero нестандартные коды даргинского и эрзянского (ISO 639-3)
# id пака, заголовок, лицензия, язык golden-фразы
PACKS = {
    'v5_cis_base_nostress': ('cis_base_nostress', 'Языки России и СНГ (Silero v5 CIS base)', 'MIT', 'tat'),
    'v5_turkic': ('turkic', 'Тюркские языки (Silero v5 Turkic)', 'CC BY-NC 4.0', 'sah'),
    'v5_caucasian': ('caucasian', 'Кавказские языки (Silero v5 Caucasian)', 'CC BY-NC 4.0', 'che'),
}
NAMES = {
    'tat': 'Татарский', 'bak': 'Башкирский', 'chv': 'Чувашский', 'sah': 'Якутский', 'xal': 'Калмыцкий',
    'udm': 'Удмуртский', 'kjh': 'Хакасский', 'myv': 'Эрзянский', 'mdf': 'Мокшанский', 'kaz': 'Казахский',
    'kir': 'Киргизский', 'uzb': 'Узбекский', 'tgk': 'Таджикский', 'aze': 'Азербайджанский', 'hye': 'Армянский',
    'kat': 'Грузинский', 'kbd': 'Кабардино-черкесский', 'crh': 'Крымскотатарский', 'gag': 'Гагаузский',
    'kaa': 'Каракалпакский', 'sty': 'Сибирскотатарский', 'tuk': 'Туркменский', 'tyv': 'Тувинский',
    'abq': 'Абазинский', 'ady': 'Адыгейский', 'agx': 'Агульский', 'ava': 'Аварский', 'che': 'Чеченский',
    'dar': 'Даргинский', 'inh': 'Ингушский', 'krc': 'Карачаево-балкарский', 'kum': 'Кумыкский',
    'lbe': 'Лакский', 'lez': 'Лезгинский', 'oss': 'Осетинский', 'tab': 'Табасаранский', 'tkr': 'Цахурский',
}
GOLDEN_TEXT = {
    'tat': 'Мин сине яратам, минем туган ягым. Кояш чыга, кошлар сайрый!',
    'sah': 'Мин эйигин таптыыбын, төрөөбүт дойдум. Күн тахсар, чыычаахтар ыллыыллар!',
    'che': 'Суна хьо веза, сан даймохк. Малх хьалакхоьссина, олхазарш дека!',
}


def export(pt):
    name = os.path.splitext(os.path.basename(pt))[0]
    pack_id, title, license_, golden_lang = PACKS[name]
    big = torch.package.PackageImporter(pt).load_pickle('tts_models', 'model')
    pk = big.packages[0]
    assert len(big.packages) == 1 and len(pk.models) == 1 and pk.accentor is None and not pk.phons, name
    languages = {}
    for spk, sid in pk.speaker_to_ids[0].items():
        code = spk.split('_')[0]
        if code in SKIP:
            continue
        code = CODE_FIX.get(code, code)
        assert code in NAMES, f'нет названия для языка {code} ({spk})'
        languages.setdefault(code, {'name': NAMES[code], 'speakers': {}})['speakers'][spk] = sid
    pack = {
        'format': FORMAT, 'id': pack_id, 'title': title, 'license': license_,
        'source': f'https://models.silero.ai/models/tts/ru/{name}.pt',
        'symbols': pk.symbols, 'symbol_to_id': pk.symbol_to_id, 'sos': pk.sos_token, 'eos': pk.eos_token,
        'alphabet': ''.join(pk.alphabet), 'languages': languages,
    }
    if pk.ext_alph:
        pack['translit'] = {CODE_FIX.get(k, k): v for k, v in pk.ext_alph.items() if k not in SKIP}
    os.makedirs(DIST, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        ptl = os.path.join(tmp, 'tts.ptl')
        pk.models[0]._save_for_lite_interpreter(ptl)
        golden = verify(pk, ptl, golden_lang)
        out = os.path.join(DIST, f'ruvoice-pack-{pack_id}.zip')
        with zipfile.ZipFile(out, 'w') as zf:
            zf.writestr('pack.json', json.dumps(pack, ensure_ascii=False), compress_type=zipfile.ZIP_DEFLATED)
            zf.write(ptl, 'tts.ptl', compress_type=zipfile.ZIP_STORED)
    print(out, round(os.path.getsize(out) / 1048576, 1), 'MB;', len(languages), 'языков')
    golden['pack'] = pack
    return golden


def verify(pk, ptl, lang):
    """lite-модуль даёт тот же звук, что apply_tts; заодно эталон препроцессинга для JVM-теста."""
    text = GOLDEN_TEXT[lang]
    spk = next(s for s in pk.speaker_to_ids[0] if s.startswith(lang + '_'))
    ref = pk.apply_tts(text=text, speaker=spk, sample_rate=48000)
    prepared, _, _ = pk.prepare_text_input(text, lang)
    seq, _ = pk.preprocess_tacotron(prepared)
    seq = seq.unsqueeze(0); n = seq.shape[1]
    lite = _load_for_lite_interpreter(ptl)
    a, _ = lite(seq, torch.LongTensor([pk.speaker_to_ids[0][spk]]), 48000, None, torch.ones(1, n), torch.ones(1, n),
                None, None, 'cpu', -1, False)
    assert a.shape[1] == ref.shape[0] and (a[0] - ref).abs().max().item() == 0, 'tts mismatch'
    print('verify: ok', spk)
    return {'lang': lang, 'text': text, 'prepared': prepared, 'ids': seq[0].tolist()}


if __name__ == '__main__':
    pts = sys.argv[1:] or [os.path.join(HERE, f'{n}.pt') for n in PACKS]
    goldens = [export(pt) for pt in pts]
    old = json.load(open(GOLDEN, encoding='utf-8')) if os.path.exists(GOLDEN) else []
    merged = {g['pack']['id']: g for g in old}
    merged.update({g['pack']['id']: g for g in goldens})
    with open(GOLDEN, 'w', encoding='utf-8') as f:
        json.dump(list(merged.values()), f, ensure_ascii=False, indent=1)
    print('golden:', len(merged), 'паков')
