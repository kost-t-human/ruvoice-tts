#!/usr/bin/env python3
"""Буква «ё»: сколько Silero Stress восстанавливает на ёфицированном тексте. Корпус — app/build/yo_corpus.txt
(tools/wiki_yo_corpus.py, Википедия); из предложения «ё» стирается, модель ставит её заново.
Слова делятся по словарю eyo (github.com/e2yo/eyo-kernel, MIT; safe.txt и not_safe.txt рядом с корпусом):
однозначные (е→ё всегда), неоднозначные (все/всё) и неизвестные. Промахи — app/build/yo_eval_miss.txt.
Запуск: <venv с silero-stress>/bin/python tools/yo_eval.py [макс_предложений]"""
import os, re, sys, collections, itertools
from silero_stress import load_accentor

HERE = os.path.dirname(os.path.abspath(__file__)); BUILD = os.path.join(os.path.dirname(HERE), 'app/build')
LIMIT = int(sys.argv[1]) if len(sys.argv) > 1 else 0
word_re = re.compile(r'[а-яё]+')


def expand(line):
    """«Ёжиков(а|ой|у|ы)» → все формы; «(|а)» — пустое окончание."""
    m = re.match(r'^([^(]*)\(([^)]*)\)$', line)
    if not m: return [line]
    return [m.group(1) + e for e in m.group(2).split('|')]


def eyo(name):
    forms = set()
    path = os.path.join(BUILD, name)
    if not os.path.exists(path): return forms
    for line in open(path, encoding='utf-8'):
        for f in expand(line.strip()): forms.add(f.lower())
    return forms


safe, not_safe = eyo('eyo_safe.txt'), eyo('eyo_not_safe.txt')
def kind(w_yo):
    if w_yo in not_safe: return 'неоднозначные'
    if w_yo in safe: return 'однозначные'
    return 'неизвестные'


ss = load_accentor()
lines = [l.strip() for l in open(os.path.join(BUILD, 'yo_corpus.txt'), encoding='utf-8') if l.strip()]
if LIMIT: lines = lines[:LIMIT]
st = collections.defaultdict(collections.Counter); miss = []
for n, sent in enumerate(lines):
    orig = word_re.findall(sent.lower())
    deyo = sent.replace('ё', 'е').replace('Ё', 'Е')
    got = [w.replace('+', '') for w in re.findall(r'[а-яё+]+', ss(deyo, put_yo=True, put_yo_homo=True).lower())]
    if len(got) != len(orig): st['всего']['предложений не сравнить'] += 1; continue
    st['всего']['предложений'] += 1
    for o, g in zip(orig, got):
        if 'ё' in o:
            k = kind(o); st[k]['слов с ё'] += 1
            if g == o: st[k]['ё восстановлена'] += 1
            else: st[k]['ё потеряна'] += 1; miss.append(('потеряна', o, g, sent))
        elif 'ё' in g:
            k = kind(g); st[k]['ё лишняя'] += 1; miss.append(('лишняя', o, g, sent))
    if n % 5000 == 0: print(n, len(lines), flush=True)
for k in ('однозначные', 'неоднозначные', 'неизвестные'):
    c = st[k]; tot = c['слов с ё']
    print(f"{k:14} слов с ё {tot:6}, восстановлено {c['ё восстановлена']:6} ({c['ё восстановлена'] * 100 / max(tot, 1):.1f}%), потеряно {c['ё потеряна']:5}, лишняя ё {c['ё лишняя']:5}")
print(dict(st['всего']))
with open(os.path.join(BUILD, 'yo_eval_miss.txt'), 'w', encoding='utf-8') as o:
    o.write('# что | должно быть | модель | предложение\n')
    for m in miss: o.write(' | '.join(m) + '\n')
