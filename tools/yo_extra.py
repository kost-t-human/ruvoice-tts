#!/usr/bin/env python3
"""Добор к словарю бесспорной «ё» (assets/eyo_safe.txt = safe.txt из eyo-kernel, MIT): формы, которых у eyo нет,
но которые AOT (app/build/aot_forms.tsv, tools/aot_forms.py) или Викисловарь (app/build/wikt_forms.tsv) знают с «ё»,
а написание через «е» ни один из них не знает как отдельное слово; спорные по eyo (not_safe.txt: Алфёров/Алферов) не берём. Кандидаты — из любого списка ёфикации
(аргумент: файл «слово=слово с ё» или просто слова с «ё» по одному в строке); в репо идёт только отсев, tools/yo_extra.txt.
Все записи с «_» — только строчными: через «е» с заглавной это чаще фамилия (Груздев, Блек, Одер), а не грузде́в/блёк.
Ассет собирается заново: app/build/eyo_safe.txt (оригинал eyo) + отсев. Проверка: tools/yo_eval.py.
Запуск: python3 tools/yo_extra.py <кандидаты.txt>"""
import os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__)); ROOT = os.path.dirname(HERE); BUILD = os.path.join(ROOT, 'app/build')
EXTRA = os.path.join(HERE, 'yo_extra.txt'); ASSET = os.path.join(ROOT, 'app/src/main/assets/eyo_safe.txt')
HEADER = '# safe.txt из eyo-kernel 4.1.3 (github.com/e2yo/eyo-kernel, MIT, © Denis Seleznev): слова, где «ё» бесспорна. Формат: слово(окончание|…), _только строчными, # комментарий\n'


def expand(line):
    m = re.match(r'^([^(]*)\(([^)]*)\)$', line)
    return [line] if not m else [m.group(1) + e for e in m.group(2).split('|')]


def eyo(name='eyo_safe.txt'):
    forms = set()
    for line in open(os.path.join(BUILD, name), encoding='utf-8'):
        for f in expand(line.strip().split('#')[0].strip().lstrip('_')): forms.add(f.lower())
    return forms


def forms(name):
    return set(line.split('\t', 1)[0] for line in open(os.path.join(BUILD, name), encoding='utf-8'))


def sieve(candidates):
    safe = eyo() | eyo('eyo_not_safe.txt'); known = forms('aot_forms.tsv') | forms('wikt_forms.tsv')
    keep = set()
    for raw in candidates:
        w = raw.strip().lower().split('=')[-1].strip()
        if not w or ' ' in w or 'ё' not in w or w in safe: continue
        if w in known and w.replace('ё', 'е') not in known: keep.add(w)
    return sorted(keep)


def build_asset():
    with open(ASSET, 'w', encoding='utf-8') as o:
        o.write(HEADER)
        o.write(open(os.path.join(BUILD, 'eyo_safe.txt'), encoding='utf-8').read().rstrip('\n') + '\n')
        o.write('# --- добор: формы AOT/Викисловаря с «ё» (tools/yo_extra.py → tools/yo_extra.txt), только строчными\n')
        for line in open(EXTRA, encoding='utf-8'):
            if line.strip() and not line.startswith('#'): o.write('_' + line.strip() + '\n')


if __name__ == '__main__':
    if len(sys.argv) > 1:
        keep = sieve(open(sys.argv[1], encoding='utf-8-sig'))
        with open(EXTRA, 'w', encoding='utf-8') as o:
            o.write('# Формы с «ё», которых нет в eyo safe: знает AOT или Викисловарь, через «е» не знает никто (tools/yo_extra.py)\n')
            o.write('\n'.join(keep) + '\n')
        print('отсев:', len(keep), '→', EXTRA)
    build_asset()
    print('ассет собран:', ASSET)
