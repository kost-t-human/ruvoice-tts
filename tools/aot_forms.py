#!/usr/bin/env python3
"""Словоформы с ударениями из морфословаря AOT (LGPL, по Зализняку) → app/build/aot_forms.tsv.

Вход: data/Russian/morphs.json и gramtab.json из github.com/sokirko74/morph_dict (каталог — аргумент).
Строка выхода: форма \t вариант1 \t вариант2 …, вариант = ударная форма с «+» и граммемы через «;»
(лемма:часть речи:граммемы, несколько наборов через «|»). Ударение в AOT — номер гласной с конца, 0 — последняя.
"""
import json, os, sys, collections

SRC = sys.argv[1] if len(sys.argv) > 1 else os.path.expanduser('~/aot')
OUT = os.path.join(os.path.dirname(__file__), '..', 'app', 'build', 'aot_forms.tsv')
VOWELS = 'аеёиоуыэюя'


def stressed(form, acc):
    """'+' перед ударной гласной; acc — номер гласной с конца."""
    idx = [i for i, c in enumerate(form) if c in VOWELS]
    if acc >= len(idx): return None
    i = idx[len(idx) - 1 - acc]
    return form[:i] + '+' + form[i:]


def main():
    d = json.load(open(os.path.join(SRC, 'morphs.json'), encoding='utf-8'))
    g = json.load(open(os.path.join(SRC, 'gramtab.json'), encoding='utf-8'))['gramcodes']
    fm, am = d['flexia_models'], d['accent_models']
    table = collections.defaultdict(lambda: collections.defaultdict(set))  # form -> stressed -> {лемма:pos:gram}
    for l in d['lemmas']:
        acc = am[l['a']]
        if all(a == 255 for a in acc): continue
        f = fm[l['f']]
        e0 = f['endings'][0]['flexia']
        lemma = l['l']
        stem = lemma[:len(lemma) - len(e0)] if e0 and lemma.endswith(e0) else lemma
        common = g.get(l.get('t', ''), {}).get('g', [])
        for e, a in zip(f['endings'], acc):
            if a == 255: continue
            form = (e.get('prefix', '') + stem + e['flexia']).lower()
            s = stressed(form, a)
            if s is None: continue
            gc = g[e['gramcode']]
            table[form][s].add(lemma.lower() + ':' + gc['p'] + ':' + ','.join(common + gc['g']))
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    n_homo = 0
    with open(OUT, 'w', encoding='utf-8') as out:
        for form in sorted(table):
            vs = table[form]
            if len(vs) > 1: n_homo += 1
            out.write(form + '\t' + '\t'.join(s + ' ' + '|'.join(sorted(gr)) for s, gr in sorted(vs.items())) + '\n')
    print(f'форм: {len(table)}, с двумя и более ударениями: {n_homo} → {OUT}')


if __name__ == '__main__':
    main()
