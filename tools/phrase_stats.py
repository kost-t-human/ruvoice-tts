#!/usr/bin/env python3
"""Польза каждой фразы-подсказки (наши tools/phrases_extra.txt и фразы Silero из silero_ru.json) по золоту формата
homo_eval (narusco, СинТагРус, Викисловарь, HomographResolutionEval): n — сработала, k — аппка без этой фразы верна,
m — с фразой верна. Без нашей фразы решают gramPass → фразы Silero → BERT; без фразы Silero — BERT (её выключаем в
модели на лету: фразы зашиты в homosolver.compiled_phrases). Итог app/build/phrase_stats_<имя>.tsv:
источник, фраза, слово, вариант, n, k, m. Вредные (m<k) и бесполезные (m=k, n≥10) → tools/phrases_drop.txt (Silero)
или долой из phrases_extra.txt (наши). Запуск: <venv с silero-stress>/bin/python tools/phrase_stats.py <золото.json>"""
import json, os, re, sys, collections
TOOLS = os.path.dirname(os.path.abspath(__file__)); ROOT = os.path.dirname(TOOLS)
sys.path.insert(0, TOOLS); import phrases_extra as pe, stress_fixes as sf
from aot_morph import Table
from silero_stress import load_accentor
DATA = sys.argv[1]; OUT = os.path.join(ROOT, 'app/build', 'phrase_stats_' + os.path.splitext(os.path.basename(DATA))[0] + '.tsv')
word_re = re.compile(r'[а-яё+-]+', re.I)
d = json.load(open(os.path.join(ROOT, 'app/src/main/assets/silero/silero_ru.json'), encoding='utf-8'))
gram, homo, phrases = d['gram'], d['homodict'], d['phrases']   # только фразы Silero: наши через extra_pick
fixes = sf.load_fixes(); morph = Table(); ss = load_accentor(); hs = ss.homosolver; sil_phr = hs.compiled_phrases
def bert_only(text, i):
    hs.compiled_phrases = {}
    try: g = word_re.findall(ss(text, put_yo=False, put_yo_homo=False))
    finally: hs.compiled_phrases = sil_phr
    return g[i] if i < len(g) else None
extra = {w: [(re.compile(r'(?<![а-яё-])' + re.escape(p).replace(r'\*', '[а-яё-]*') + r'(?![а-яё-])'), p, v) for p, v in items] for w, items in pe.load_extra().items()}

def plus(w):
    i = w.find('́'); return (w[:i - 1] + '+' + w[i - 1:]).replace('́', '') if i > 0 else w
fired = [None]
def phrase_pick(w, text):
    for p, var in phrases.get(w, []):
        if re.search(r'(?<![а-яё-])' + re.escape(p) + r'(?![а-яё-])', text): fired[0] = ('silero', p, w, var); return var
    return None
def extra_pick(w, text):
    for rx, p, v in extra.get(w, ()):
        if rx.search(text): fired[0] = ('extra', p, w, v); return v
    return None
pe.extra_pick = extra_pick   # наши фразы через нашу обёртку, чтобы знать, какая сработала
none = lambda w, t: None

stat = collections.defaultdict(lambda: [0, 0, 0]); ye = lambda x: x.replace('ё', 'е'); total = collections.Counter()
for it in json.load(open(DATA, encoding='utf-8')):
    target = plus(it['homograph'].lower()); w = target.replace('+', ''); text = it['context'].lower()
    got = ss(text, put_yo=False, put_yo_homo=False)
    toks = [t.replace('+', '') for t in word_re.findall(got)]; stressed = word_re.findall(got)
    if w not in toks: continue
    idx = [k for k, t in enumerate(toks) if t == w]; i = idx[min(it.get('occurrence', 0), len(idx) - 1)]
    base = stressed[i]; t = ye(target)
    fired[0] = None
    with_p = pe.app_pick(w, toks, i, text, gram, homo, morph, phrase_pick) or base
    f = fired[0]
    if w in fixes and not extra_pick(w, text): with_p = fixes[w]
    total['n'] += 1; total['with'] += ye(with_p) == t
    if not f: continue
    # без этой фразы: наша фраза → gram/vse/фразы Silero/BERT; фраза Silero → BERT (gram молчал)
    if f[0] == 'extra':
        pe.extra_pick = none; fired[0] = None
        without = pe.app_pick(w, toks, i, text, gram, homo, morph, phrase_pick) or base
        pe.extra_pick = extra_pick
        if w in fixes: without = fixes[w]
    else:
        without = bert_only(text, i) or base   # фразы Silero зашиты в модель: считаем заново без них
        if w in fixes: without = fixes[w]
    s = stat[f]; s[0] += 1; s[1] += ye(without) == t; s[2] += ye(with_p) == t
print(DATA, dict(total), 'фраз сработало:', len(stat))
with open(OUT, 'w', encoding='utf-8') as o:
    for (src, p, w, v), (n, k, m) in sorted(stat.items(), key=lambda x: x[1][2] - x[1][1]):
        o.write(f'{src}\t{p}\t{w}\t{v}\t{n}\t{k}\t{m}\n')
