#!/usr/bin/env python3
"""Встроенные поправки ударений поверх Silero Stress: tools/stress_fixes.txt → exceptions в silero_ru.json.

  python3 tools/stress_fixes.py build   — собрать список: промахи модели по AOT (app/build/aot_survey.txt,
      tools/aot_survey.py), подтверждённые Викисловарём (app/build/wikt_forms.tsv, tools/wikt_forms.py);
      имена, слова с «ё» (и те, что модель читает через «ё») и слова, уже лежащие в exceptions, не берутся. Частота для сортировки — ru_full.txt
      (github.com/hermitdave/FrequencyWords, OpenSubtitles) рядом с aot_survey.txt, если есть.
  python3 tools/stress_fixes.py apply   — записать список в exceptions (export_silero_stress.py делает это сам).
Формат строки: «слово = сл+ово  # комментарий». Список можно править руками, build его перезаписывает."""
import json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__)); ROOT = os.path.dirname(HERE)
BUILD = os.path.join(ROOT, 'app/build'); FIXES = os.path.join(HERE, 'stress_fixes.txt')
JSON = os.path.join(ROOT, 'app/src/main/assets/silero/silero_ru.json')
NAMES = {'name', 'surname', 'patr'}
# просмотрено руками: в текстах это имена (Арчи, Вита, Карим, Дик, Морено, Голубев…) или другое слово (на скаку, левел)
SKIP = set('арчи морено вита карим дика туту усе камеди скаку голубев зверев власов каменев корсаков левел муслим тютю анхеле спались'.split())


def load_fixes():
    out = {}
    if not os.path.exists(FIXES): return out
    for line in open(FIXES, encoding='utf-8'):
        s = line.split('#', 1)[0].strip()
        if '=' in s:
            k, v = (x.strip() for x in s.split('=', 1))
            if v.replace('+', '') == k and v.count('+') == 1: out[k] = v
    return out


def apply(data):
    """exceptions: слово → [индекс ударной гласной, индекс «ё» или -1]."""
    n = 0
    for w, v in load_fixes().items():
        data['exceptions'][w] = [v.index('+'), -1]; n += 1
    return n


def build():
    with open(JSON, encoding='utf-8') as f: exc = json.load(f)['exceptions']
    exc = {w for w in exc if w not in load_fixes()}  # свои же поправки не считаем чужими исключениями
    wk = {}
    for l in open(os.path.join(BUILD, 'wikt_forms.tsv'), encoding='utf-8'):
        p = l.rstrip('\n').split('\t'); wk[p[0]] = [v.split(' ')[0] for v in p[1:]]
    freq = {}
    fp = os.path.join(BUILD, 'ru_full.txt')
    if os.path.exists(fp):
        for l in open(fp, encoding='utf-8'): w, n = l.split(); freq[w] = int(n)
    rows = []
    for l in open(os.path.join(BUILD, 'aot_survey.txt'), encoding='utf-8'):
        if l.startswith('#'): continue
        w, aot, got, gr = l.rstrip('\n').split('\t')
        if w in exc or w in SKIP or 'ё' in got or wk.get(w) != [aot]: continue
        i = got.find('+е')  # модель читает «е» как «ё» (шоф+ером, т+елки): если такое слово с «ё» есть, это не промах
        if i >= 0 and (got[:i] + 'ё' + got[i + 2:]) in wk: continue
        if any('ё' in a.split(':')[0] for a in gr.split('|')): continue
        if NAMES & set(gr.split('|')[0].split(':')[2].split(',')): continue
        rows.append((w, aot, got, freq.get(w, 0), gr.split('|')[0]))
    rows.sort(key=lambda r: (-r[3], r[0]))
    with open(FIXES, 'w', encoding='utf-8') as o:
        o.write('# Поправки ударений: модель Silero Stress ставит иначе, AOT и Викисловарь согласны. tools/stress_fixes.py build\n'
                '# слово = ударение  # частота в субтитрах, как ставит модель, разбор AOT\n')
        for w, aot, got, fq, gr in rows: o.write(f'{w} = {aot}  # {fq}, модель {got}, {gr}\n')
    print(f'поправок: {len(rows)} → {FIXES}')


if __name__ == '__main__':
    if sys.argv[1:] == ['build']: build()
    elif sys.argv[1:] == ['apply']:
        with open(JSON, encoding='utf-8') as f: d = json.load(f)
        n = apply(d)
        with open(JSON, 'w', encoding='utf-8') as f: json.dump(d, f, ensure_ascii=False)
        print(f'exceptions += {n}')
    else: print(__doc__)
