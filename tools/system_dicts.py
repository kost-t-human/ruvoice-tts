#!/usr/bin/env python3
"""Системный словарь приложения: tools/stress_fixes.txt и tools/phrases_extra.txt → assets/dicts/{stress,replace}/Системный.txt.
В аппке это обычные списки «Системный» на вкладках «Ударения» и «Замены»: их нельзя удалить и править,
но можно выключить; при старте файл в данных приложения обновляется из assets, если отличается.
Запуск: python3 tools/system_dicts.py (export_silero_stress.py вызывает сам)."""
import os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__)); ROOT = os.path.dirname(HERE)
ASSETS = os.path.join(ROOT, 'app/src/main/assets/dicts')
sys.path.insert(0, HERE)
# по корпусу Википедии (tools/yo_eval.py): тёмно-зелёный, чёрно-белый, жёлто-, зелёно-, взлётно-посадочная, ликёро-водочный
COMPOUNDS = [('темно', 'тёмно'), ('черно', 'чёрно'), ('желто', 'жёлто'), ('зелено', 'зелёно'), ('взлетно', 'взлётно'), ('ликеро', 'ликёро')]


def main():
    import stress_fixes, phrases_extra
    fixes = stress_fixes.load_fixes()
    os.makedirs(os.path.join(ASSETS, 'stress'), exist_ok=True); os.makedirs(os.path.join(ASSETS, 'replace'), exist_ok=True)
    with open(os.path.join(ASSETS, 'stress', 'Системный.txt'), 'w', encoding='utf-8') as o:
        o.write('# Поправки ударений: модель ставит иначе, словари AOT и Викисловаря согласны между собой (tools/stress_fixes.txt)\n')
        for w, v in sorted(fixes.items()): o.write(f'{w} {v}\n')
    n = 0
    with open(os.path.join(ASSETS, 'replace', 'Системный.txt'), 'w', encoding='utf-8') as o:
        o.write('# Первые части сложных слов с «ё»: отдельно «темно» — наречие темн+о, и модель теряет «ё»\n')
        for a, b in COMPOUNDS: o.write(f'{a}-* = {b}-*\n')
        o.write('# Фразы-подсказки для омографов: слово в этой фразе читается так (tools/phrases_extra.txt)\n')
        for w, items in sorted(phrases_extra.load_extra().items()):
            for phrase, var in items:
                out = re.sub(r'(?<![а-яё])' + re.escape(w) + r'(?![а-яё])', var, phrase, count=1)
                o.write(f'{phrase} = {out}\n'); n += 1
    print(f'системный словарь: ударений {len(fixes)}, фраз {n} → {ASSETS}')


if __name__ == '__main__':
    main()
