#!/usr/bin/env python3
"""Silero Stress по всем однозначным словоформам AOT (app/build/aot_forms.tsv из tools/aot_forms.py).
Слова с одним ударением по AOT, не омографы Silero Stress, без «ё», с двумя и более гласными.
Выход app/build/aot_survey.txt: промахи «форма \t AOT \t модель \t лемма:часть речи:граммемы».
Запуск: <venv с silero-stress>/bin/python tools/aot_survey.py"""
import os, sys, time
from silero_stress import load_accentor

HERE = os.path.dirname(os.path.abspath(__file__)); BUILD = os.path.join(os.path.dirname(HERE), 'app/build')
ss = load_accentor(); homo = set(ss.homosolver.homodict)
VOW = set('аеёиоуыэюя')

rows = []
for line in open(os.path.join(BUILD, 'aot_forms.tsv'), encoding='utf-8'):
    p = line.rstrip('\n').split('\t')
    w = p[0]
    if len(p) != 2 or w in homo or 'ё' in w or not w.isalpha() or sum(c in VOW for c in w) < 2: continue
    st, gr = p[1].split(' ', 1)
    rows.append((w, st, gr))
print('слов на проверку:', len(rows), flush=True)

bad = []; t0 = time.time(); B = 500
for i in range(0, len(rows), B):
    chunk = rows[i:i + B]
    got = ss(' '.join(w for w, _, _ in chunk), put_yo=False, put_yo_homo=False).split()
    if len(got) != len(chunk): got = [ss(w, put_yo=False, put_yo_homo=False) for w, _, _ in chunk]
    for (w, st, gr), g in zip(chunk, got):
        if g != st: bad.append((w, st, g, gr))
    if i % 100000 == 0: print(f'{i}/{len(rows)} {time.time() - t0:.0f}с промахов {len(bad)}', flush=True)

with open(os.path.join(BUILD, 'aot_survey.txt'), 'w', encoding='utf-8') as o:
    o.write(f'# Silero Stress против AOT: слов {len(rows)}, промахов {len(bad)} ({len(bad) * 100 / len(rows):.2f}%)\n# форма\tAOT\tмодель\tграммемы\n')
    for r in bad: o.write('\t'.join(r) + '\n')
print(f'слов {len(rows)}, промахов {len(bad)} ({len(bad) * 100 / len(rows):.2f}%) → app/build/aot_survey.txt')
