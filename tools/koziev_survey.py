#!/usr/bin/env python3
"""Silero Stress по корпусу ударений Козиева (github.com/Koziev/NLP_Datasets, Stress/all_accents.zip, CC0):
1,68 млн словоформ «форма \t фор^ма», собран из Википедии, Викисловаря и таблиц GrammarEngine. Оговорки:
без «ё» (ёлка → «елка»), у омографа одна форма без пометы (уже → «^уже», воды → «в^оды»), поэтому омографы
Silero Stress и нашей таблицы gram отбрасываются, а остаток — третий арбитр рядом с AOT и Викисловарём.
Выход app/build/koziev_survey.txt: промахи «форма \t Козиев \t модель \t AOT|Викисловарь (если есть)».
Запуск: <venv с silero-stress>/bin/python tools/koziev_survey.py [all_accents.tsv, по умолчанию ../koziev_all_accents.tsv]"""
import json, os, sys, time
from silero_stress import load_accentor

HERE = os.path.dirname(os.path.abspath(__file__)); ROOT = os.path.dirname(HERE); BUILD = os.path.join(ROOT, 'app/build')
SRC = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(ROOT), 'koziev_all_accents.tsv')
ss = load_accentor(); homo = set(ss.homosolver.homodict)
gram = set(json.load(open(os.path.join(ROOT, 'app/src/main/assets/silero/silero_ru.json'), encoding='utf-8'))['gram'])
VOW = set('аеёиоуыэюя')


def tsv_variants(path):
    """aot_forms.tsv / wikt_forms.tsv: форма → {ударные варианты}."""
    d = {}
    if not os.path.exists(path): return d
    for line in open(path, encoding='utf-8'):
        p = line.rstrip('\n').split('\t')
        d[p[0]] = {v.split(' ', 1)[0] for v in p[1:]}
    return d


rows = []
for line in open(SRC, encoding='utf-8'):
    p = line.rstrip('\n').split('\t')
    if len(p) != 2: continue
    w, st = p[0], p[1].replace('^', '+')
    if w in homo or w in gram or 'ё' in w or not w.isalpha() or sum(c in VOW for c in w) < 2 or '+' not in st: continue
    rows.append((w, st))
print('слов на проверку:', len(rows), flush=True)

bad = []; t0 = time.time(); B = 500
for i in range(0, len(rows), B):
    chunk = rows[i:i + B]
    got = ss(' '.join(w for w, _ in chunk), put_yo=False, put_yo_homo=False).split()
    if len(got) != len(chunk): got = [ss(w, put_yo=False, put_yo_homo=False) for w, _ in chunk]
    for (w, st), g in zip(chunk, got):
        if g != st: bad.append((w, st, g))
    if i % 100000 == 0: print(f'{i}/{len(rows)} {time.time() - t0:.0f}с промахов {len(bad)}', flush=True)

aot = tsv_variants(os.path.join(BUILD, 'aot_forms.tsv')); wikt = tsv_variants(os.path.join(BUILD, 'wikt_forms.tsv'))
with open(os.path.join(BUILD, 'koziev_survey.txt'), 'w', encoding='utf-8') as o:
    o.write(f'# Silero Stress против Козиева: слов {len(rows)}, промахов {len(bad)} ({len(bad) * 100 / len(rows):.2f}%)\n# форма\tКозиев\tмодель\tAOT|Викисловарь\n')
    for w, st, g in bad:
        o.write(f"{w}\t{st}\t{g}\t{','.join(sorted(aot.get(w, ()))) or '-'}|{','.join(sorted(wikt.get(w, ()))) or '-'}\n")
print(f'слов {len(rows)}, промахов {len(bad)} ({len(bad) * 100 / len(rows):.2f}%) → app/build/koziev_survey.txt')
