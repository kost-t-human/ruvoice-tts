#!/usr/bin/env python3
"""Оценка омографов на настоящих предложениях: HomographResolutionEval (Илья Козиев, CC BY 4.0,
huggingface.co/datasets/inkoziev/HomographResolutionEval; копия в app/src/test/resources/homograph_eval/).
1741 предложение, 509 омографов, у каждого отмечено верное ударение омографа.
Считает точность Silero Stress как есть и с зеркалом Stress.gramPass (tools/phrases_extra.gram_pick, с таблицей
морфологии morph.bin) и нашими фразами; промахи — в app/build/homo_eval_miss.txt.
Запуск: <venv с silero-stress>/bin/python tools/homo_eval.py [другой json того же формата, например ../syntagrus_eval.json
из tools/syntagrus_eval.py; промахи тогда в app/build/<имя>_miss.txt]"""
import json, os, re, sys, collections
from silero_stress import load_accentor

HERE = os.path.dirname(os.path.abspath(__file__)); ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE); import phrases_extra as pe, stress_fixes as sf
from aot_morph import Table
DATA = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, 'app/src/test/resources/homograph_eval/HomographResolutionEval.json')
MISS = os.path.join(ROOT, 'app/build', ('homo_eval' if len(sys.argv) < 2 else os.path.splitext(os.path.basename(DATA))[0]) + '_miss.txt')
d = json.load(open(os.path.join(ROOT, 'app/src/main/assets/silero/silero_ru.json'), encoding='utf-8'))
gram, homo, phrases = d['gram'], d['homodict'], d['phrases']
fixes = sf.load_fixes(); morph = Table()
for w, items in pe.load_extra().items(): phrases[w] = sorted(items + [tuple(x) for x in phrases.get(w, [])], key=lambda x: -len(x[0]))  # системный словарь
ss = load_accentor()
word_re = re.compile(r'[а-яё+-]+', re.I)


def plus(w):
    """'сло́ва' → 'сл+ова'"""
    i = w.find('́'); return (w[:i - 1] + '+' + w[i - 1:]).replace('́', '') if i > 0 else w


def phrase_pick(w, text):
    for p, var in phrases.get(w, []):
        if re.search(r'(?<![а-яё-])' + re.escape(p) + r'(?![а-яё-])', text): return var
    return None


items = json.load(open(DATA, encoding='utf-8'))
stat = collections.Counter(); miss = []
for it in items:
    target = plus(it['homograph'].lower()); w = target.replace('+', '')
    text = it['context'].lower()
    got = ss(text, put_yo=False, put_yo_homo=False)
    toks = [t.replace('+', '') for t in word_re.findall(got)]
    stressed = word_re.findall(got)
    if w not in toks: stat['слово не найдено'] += 1; continue
    idx = [k for k, t in enumerate(toks) if t == w]
    i = idx[min(it.get('occurrence', 0), len(idx) - 1)]   # narusco: цель может быть не первым вхождением формы
    base = stressed[i]
    # зеркало аппки: gramPass → фразы (наши и Silero) → BERT
    ours = pe.app_pick(w, toks, i, text, gram, homo, morph, phrase_pick) or base
    if w in fixes and not pe.extra_pick(w, text): ours = fixes[w]
    stat['всего'] += 1
    # «ё» не сравниваем: модель в тесте работает без «ё» (put_yo=False), а золото narusco/Викисловаря её пишет
    ye = lambda x: x.replace('ё', 'е')
    stat['silero верно'] += ye(base) == ye(target)
    stat['аппка верно'] += ye(ours) == ye(target)
    if ye(ours) != ye(target) or ye(base) != ye(target): miss.append((it['context'], target, base, ours))
n = stat['всего']
print(f"предложений {n}, пропущено {stat['слово не найдено']}; Silero Stress: {stat['silero верно']} ({stat['silero верно'] * 100 / n:.1f}%), "
      f"с gramPass и фразами: {stat['аппка верно']} ({stat['аппка верно'] * 100 / n:.1f}%)")
with open(MISS, 'w', encoding='utf-8') as o:
    o.write('# предложение | верно | Silero Stress | аппка\n')
    for m in miss: o.write(' | '.join(m) + '\n')
