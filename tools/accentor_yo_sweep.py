#!/usr/bin/env python3
"""Лишняя «ё» акцентора: голова «ё» accentor.ptl по словоформам, где «ё» невозможна.

Кандидаты — формы с «е» из базы Lecron (../lecron-dict/words.db, yo IS NULL, amount ≥ MIN) без ё-варианта
в Викисловаре и AOT (app/build/wikt_forms.tsv, aot_forms.tsv из tools/wikt_forms.py и aot_forms.py) и не из
системного словаря (там модель «ё» не ставит). Промах — как в Stress.accentorPass: p(ё) > 0.5 и та же гласная,
что ударная. Вывод в формате tools/stress_fixes.txt: значение — ударение по словарям (варианты через «|», выбрать руками),
в комментарии частота Lecron и что ставит акцентор.
Запуск: ../venv-et/bin/python tools/accentor_yo_sweep.py [MIN]"""
import os, sqlite3, sys
import torch
from torch.jit.mobile import _load_for_lite_interpreter

HERE = os.path.dirname(os.path.abspath(__file__)); ROOT = os.path.dirname(HERE)
MIN = int(sys.argv[1]) if len(sys.argv) > 1 else 20
VOW = 'аеёиоуыэюя'


def keys(path):
    """форма → ударные варианты (с «+») по словарю; вариантов может быть несколько (омографы)"""
    out = {}
    with open(path, encoding='utf-8') as f:
        for line in f:
            parts = line.rstrip('\n').split('\t')
            out.setdefault(parts[0], set()).update(v.split(' ', 1)[0] for v in parts[1:] if v)
    return out


def main():
    known = keys(os.path.join(ROOT, 'app/build/wikt_forms.tsv'))
    for w, vs in keys(os.path.join(ROOT, 'app/build/aot_forms.tsv')).items(): known.setdefault(w, set()).update(vs)
    yo_forms = {w.replace('ё', 'е') for w in known if 'ё' in w}
    system = set()
    for line in open(os.path.join(ROOT, 'app/src/main/assets/dicts/stress/Системный.txt'), encoding='utf-8'):
        if not line.startswith('#'): system.add(line.split(' ', 1)[0])
    c = sqlite3.connect(os.path.join(ROOT, '../lecron-dict/words.db'))
    rows = c.execute("select text, amount from word where yo is null and amount >= ? and text like '%е%'", (MIN,)).fetchall()
    cand = [(w, n) for w, n in rows if w.isalpha() and w not in yo_forms and w not in system and w in known]
    print(f'кандидатов {len(cand)} из {len(rows)}', file=sys.stderr)
    m = _load_for_lite_interpreter(os.path.join(ROOT, 'app/src/main/assets/silero/accentor.ptl'))
    out = []
    with torch.no_grad():
        for i in range(0, len(cand), 256):
            batch = cand[i:i + 256]
            st, yo = m([w for w, _ in batch])
            st = torch.softmax(st, 1); yo = torch.softmax(yo, 1)
            for (w, n), s, y in zip(batch, st, yo):
                yi = int(y.argmax()); yp = float(y[yi])
                if yi == 0 or yp <= 0.5: continue
                vowels = [k for k, ch in enumerate(w) if ch in VOW]; yes = [k for k, ch in enumerate(w) if ch == 'е']
                si = int(s.argmax())
                if yi - 1 >= len(yes) or si >= len(vowels): continue
                if yes[yi - 1] != vowels[si]: continue
                p = vowels[si]
                out.append((n, w, w[:p] + '+' + w[p:], yp, sorted(known[w])))
    out.sort(reverse=True)
    # значение — по словарям (несколько вариантов через «|», выбрать руками), в комментарии — что ставит акцентор
    for n, w, v, yp, ref in out: print(f'{w} = {"|".join(ref)}  # {n}, акцентор {v} ё {yp:.2f}')
    print(f'промахов {len(out)}', file=sys.stderr)


if __name__ == '__main__': main()
