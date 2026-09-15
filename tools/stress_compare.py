#!/usr/bin/env python3
"""Второй проход по промахам из app/build/stress_survey.txt: те же фразы через Silero Stress
(pip install silero-stress, MIT; словарь ~4 млн словоформ, 2,2 тыс. омографов) как арбитр между
словарём и accentor-ом из v5_5_ru.
Выход:
  app/build/stress_candidates.txt — слова, где словарь и Silero Stress согласны, а v5 нет, и слово не
    омограф ни по одному списку: кандидаты во встроенный список ударений (ударение от Silero Stress).
  <out_dir>/homographs-unknown.txt — слова не из homodict v5 с промахом: омографы, которых модель не
    знает, по словам и со всеми фразами; сырьё для будущей работы над контекстом.
Запуск: <venv>/bin/python tools/stress_compare.py [out_dir=..]"""
import json, os, re, sys
from collections import Counter, defaultdict
from silero_stress import load_accentor

HERE = os.path.dirname(os.path.abspath(__file__)); ROOT = os.path.dirname(HERE)
BUILD = os.path.join(ROOT, 'app/build'); OUT_DIR = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, '..')
v5homo = set(json.load(open(os.path.join(ROOT, 'app/src/main/assets/silero/silero_ru.json')))['homodict'])
ss = load_accentor(); sshomo = set(ss.homosolver.homodict)
word_re = re.compile(r'[а-яё+]+', re.I)

def split(s): return [(w.replace('+', ''), w.find('+')) for w in word_re.findall(s)]
def mark(w, i): return w[:i] + '+' + w[i:] if i >= 0 else w

rows = []  # (ключ, словарь, v5, silero-stress)
body = open(os.path.join(BUILD, 'stress_survey.txt'), encoding='utf-8').read().split('== все промахи: ключ | словарь | модель ==\n')[1]
for n, line in enumerate(body.splitlines()):
    key, val, v5 = (x.strip() for x in line.split(' | '))
    got = ss(key, put_yo=False, put_yo_homo=False)
    rows.append((key, val, v5, got))
    if n % 5000 == 0: print(n, flush=True)

# по словам: словарь → {v5, ss} с фразами
per = defaultdict(lambda: {'dict': Counter(), 'v5': Counter(), 'ss': Counter(), 'phr': []})
cand = defaultdict(Counter)  # слово → ударение (от ss) → число фраз, где dict == ss != v5
for key, val, v5, got in rows:
    dw, vw, gw = split(val), split(v5), split(got)
    if not (len(dw) == len(vw) == len(gw)) or [w for w, _ in gw] != [w for w, _ in dw]: continue
    for (w, di), (_, vi), (_, gi) in zip(dw, vw, gw):
        if di < 0 or di == vi: continue
        p = per[w]; p['dict'][mark(w, di)] += 1; p['v5'][mark(w, vi)] += 1; p['ss'][mark(w, gi)] += 1; p['phr'].append((key, val, v5, got))
        if gi == di and w not in v5homo and w not in sshomo: cand[w][mark(w, gi)] += 1

# кандидаты: у словаря одно ударение на слово (по всем фразам), совпадающее с Silero Stress
with open(os.path.join(BUILD, 'stress_candidates.txt'), 'w', encoding='utf-8') as o:
    o.write('# слово = ударение по Silero Stress (MIT); словарь согласен, accentor v5_5_ru ставит иначе; не омограф\n')
    o.write('# число — сколько фраз; v5 — как ставит модель сейчас\n')
    n = 0
    for w, c in sorted(cand.items(), key=lambda x: -x[1].total()):
        if len(per[w]['dict']) != 1: continue
        (st, k), = c.most_common(1); n += 1
        o.write(f'{w} = {st}  # {k}, v5: {per[w]["v5"].most_common(1)[0][0]}\n')
print('кандидатов во встроенный список:', n)

unk = [(w, p) for w, p in per.items() if w not in v5homo]
with open(os.path.join(OUT_DIR, 'homographs-unknown.txt'), 'w', encoding='utf-8') as o:
    o.write(f'# Слова не из homodict v5_5_ru, где модель разошлась со словарями замен: {len(unk)} слов, {sum(len(p["phr"]) for _, p in unk)} фраз.\n'
            '# Строка слова: словарь {варианты: число} | v5 {варианты} | silero-stress {варианты} | ss-omograph да/нет\n'
            '# Далее фразы: ключ | словарь | v5 | silero-stress\n\n')
    for w, p in sorted(unk, key=lambda x: -len(x[1]['phr'])):
        fmt = lambda c: ' '.join(f'{k}:{v}' for k, v in c.most_common())
        o.write(f'== {w} | словарь {fmt(p["dict"])} | v5 {fmt(p["v5"])} | ss {fmt(p["ss"])} | ss-омограф {"да" if w in sshomo else "нет"}\n')
        for key, val, v5, got in p['phr']: o.write(f'   {key} | {val} | {v5} | {got}\n')
print('файлы:', os.path.join(BUILD, 'stress_candidates.txt'), os.path.join(OUT_DIR, 'homographs-unknown.txt'))
