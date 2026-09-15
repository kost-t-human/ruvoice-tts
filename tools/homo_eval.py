#!/usr/bin/env python3
"""Оценка омографов на настоящих предложениях: HomographResolutionEval (Илья Козиев, CC BY 4.0,
huggingface.co/datasets/inkoziev/HomographResolutionEval; копия в app/src/test/resources/homograph_eval/).
1741 предложение, 509 омографов, у каждого отмечено верное ударение омографа.
Считает точность Silero Stress как есть и с зеркалом Stress.gramPass (tools/phrases_extra.gram_pick, с таблицей
морфологии morph.bin) и нашими фразами; промахи — в app/build/homo_eval_miss.txt.
Запуск: <venv с silero-stress>/bin/python tools/homo_eval.py"""
import json, os, re, sys, collections
from silero_stress import load_accentor

HERE = os.path.dirname(os.path.abspath(__file__)); ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE); import phrases_extra as pe, stress_fixes as sf
from aot_morph import Table
DATA = os.path.join(ROOT, 'app/src/test/resources/homograph_eval/HomographResolutionEval.json')
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
    i = toks.index(w)
    base = stressed[i]
    # зеркало аппки: gramPass → фразы (наши и Silero) → BERT
    ours = None
    if w in gram and i > 0: ours = pe.gram_pick(toks[i - 1], toks[i - 2] if i > 1 else None, gram[w], w in homo, w, morph)
    if w == 'все' and i + 1 < len(toks): ours = pe.vse_pick(toks[i + 1], morph, gram)
    if ours is None: ours = phrase_pick(w, text) or base
    if w in fixes: ours = fixes[w]
    stat['всего'] += 1
    stat['silero верно'] += base == target
    stat['аппка верно'] += ours == target
    if ours != target or base != target: miss.append((it['context'], target, base, ours))
n = stat['всего']
print(f"предложений {n}, пропущено {stat['слово не найдено']}; Silero Stress: {stat['silero верно']} ({stat['silero верно'] * 100 / n:.1f}%), "
      f"с gramPass и фразами: {stat['аппка верно']} ({stat['аппка верно'] * 100 / n:.1f}%)")
with open(os.path.join(ROOT, 'app/build/homo_eval_miss.txt'), 'w', encoding='utf-8') as o:
    o.write('# предложение | верно | Silero Stress | аппка\n')
    for m in miss: o.write(' | '.join(m) + '\n')
