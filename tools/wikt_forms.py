#!/usr/bin/env python3
"""Словоформы с ударениями из английского Викисловаря (русские статьи, выгрузка kaikki.org, CC BY-SA)
→ app/build/wikt_forms.tsv в том же виде, что tools/aot_forms.py: форма \t вариант1 \t вариант2 …,
вариант = «ударная форма лемма:часть речи:теги|…». Вход: kaikki.org-dictionary-Russian.jsonl (аргумент).
Ударение — U+0301 после гласной (в сложных словах берётся последнее); «ё» всегда ударная; в слове с одной гласной знак не ставят."""
import json, os, sys, collections, re

SRC = sys.argv[1]
OUT = os.path.join(os.path.dirname(__file__), '..', 'app', 'build', 'wikt_forms.tsv')
VOW = 'аеёиоуыэюя'
ACC = '\u0301'
SKIP = {'romanization', 'table-tags', 'inflection-template', 'class', 'canonical'}
cyr = re.compile(r'^[а-яё\u0301-]+$')


def plus(form):
    """'стена́' → ('стена', 'стен+а'); None, если ударение не задано."""
    f = form.replace('́', '')
    if ACC in form:  # в сложных словах знаков несколько, главное ударение — последнее
        i = form.rindex(ACC) - 1
        i -= form[:i].count(ACC)
        return f, f[:i] + '+' + f[i:]
    vs = [i for i, c in enumerate(f) if c in VOW]
    if len(vs) == 1: return f, f[:vs[0]] + '+' + f[vs[0]:]
    if 'ё' in f: i = f.index('ё'); return f, f[:i] + '+' + f[i:]
    return None


def main():
    table = collections.defaultdict(lambda: collections.defaultdict(set))
    n = 0
    with open(SRC, encoding='utf-8') as src:
        for line in src:
            d = json.loads(line)
            if d.get('lang_code') != 'ru': continue
            lemma = d['word'].lower(); pos = d.get('pos', '')
            forms = [(f['form'], f.get('tags', [])) for f in d.get('forms', [])]
            forms.append((d['word'], ['lemma']))
            for form, tags in forms:
                if set(tags) & SKIP or not cyr.match(form.lower()): continue
                p = plus(form.lower())
                if p is None: continue
                f, s = p
                if ' ' in f or '-' in f and f.startswith('-'): continue
                table[f][s].add(lemma + ':' + pos + ':' + ','.join(t for t in tags if t not in SKIP))
            n += 1
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    n_homo = 0
    with open(OUT, 'w', encoding='utf-8') as out:
        for form in sorted(table):
            vs = table[form]
            if len(vs) > 1: n_homo += 1
            out.write(form + '\t' + '\t'.join(s + ' ' + '|'.join(sorted(gr)) for s, gr in sorted(vs.items())) + '\n')
    print(f'статей: {n}, форм: {len(table)}, с двумя и более ударениями: {n_homo} → {OUT}')


if __name__ == '__main__':
    main()
