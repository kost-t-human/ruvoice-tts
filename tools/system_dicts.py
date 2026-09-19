#!/usr/bin/env python3
"""Системный словарь приложения: tools/stress_fixes.txt, tools/wiki_names.txt, tools/phrases_extra.txt и tools/phrases_clitic.txt → assets/dicts/{stress,replace}/Системный.txt.
В аппке это обычные списки «Системный» на вкладках «Ударения» и «Замены»: их нельзя удалить и править,
но можно выключить; при старте файл в данных приложения обновляется из assets, если отличается.
Запуск: python3 tools/system_dicts.py (export_silero_stress.py вызывает сам)."""
import os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__)); ROOT = os.path.dirname(HERE)
ASSETS = os.path.join(ROOT, 'app/src/main/assets/dicts')
sys.path.insert(0, HERE)
# по корпусу Википедии (tools/yo_eval.py): тёмно-зелёный, чёрно-белый, жёлто-, зелёно-, взлётно-посадочная, ликёро-водочный
COMPOUNDS = [('темно', 'тёмно'), ('черно', 'чёрно'), ('желто', 'жёлто'), ('зелено', 'зелёно'), ('взлетно', 'взлётно'), ('ликеро', 'ликёро')]
# Орфоэпия, проверено на слух (tmp/ortho): междометия без гласной модель проговаривает слишком коротко;
# «тест», «темп», «тезис», «тенденция» читает с мягким [т'е], а по норме твёрдое [тэ]. Маска на «тест*» и «темп*»
# зацепила бы «тесто», «тесть», «температуру», «темперамент» — там формы перечислены.
# «дорого» модель читает «дорово»: окончание «-ого» → [ова] она применяет и к наречию; «-ога» без ударения звучит так же.
ORTHOEPY = [('гм', 'гмм'), ('хм', 'хмм'), ('тсс', 'тссс'), ('тезис*', 'тэзис*'), ('тенденци*', 'тэнд+энци*'), ('тестир*', 'тэстир*'), ('тестов*', 'тэстов*')] + \
    [(w, 'тэ' + w[2:]) for w in 'тест теста тесту тестом тесте тесты тестов тестам тестами тестах темп темпа темпу темпом темпе темпы темпов темпам темпами темпах'.split()] + [('дорого', 'д+орога')] + \
    [('по-моему', 'по-м+оему'), ('по-твоему', 'по-тв+оему'), ('по-своему', 'по-св+оему')] + \
    [(r'~(?<![\p{L}+-])([аоэу])(?=[!?])', '$1$1')]   # «А!», «О!», «Э?» — 325 мс, вдвое короче «Гм!»; сдвоенная гласная даёт 650 (tools/v5_5_ru.pt)


def main():
    import stress_fixes, phrases_extra
    fixes = stress_fixes.load_fixes()
    os.makedirs(os.path.join(ASSETS, 'stress'), exist_ok=True); os.makedirs(os.path.join(ASSETS, 'replace'), exist_ok=True)
    with open(os.path.join(ASSETS, 'stress', 'Системный.txt'), 'w', encoding='utf-8') as o:
        o.write('# Поправки ударений: модель ставит иначе, против неё словари AOT и Викисловаря вместе или словарь Демагога с одним из них (tools/stress_fixes.txt)\n')
        for w, v in sorted(fixes.items()): o.write(f'{w} {v}\n')
        names = {w: v for w, v in stress_fixes.load_fixes(os.path.join(HERE, 'wiki_names.txt')).items() if w not in fixes}
        o.write('# Имена и термины из первых абзацев Википедии, где модель ставит ударение иначе (tools/wiki_names.py); с «ё» — и через «е»\n')
        for w, v in sorted(names.items()):
            o.write(f'{w} {v}\n')
            if 'ё' in w: o.write(f'{w.replace("ё", "е")} {v}\n')
    n = 0
    with open(os.path.join(ASSETS, 'replace', 'Системный.txt'), 'w', encoding='utf-8') as o:
        o.write('# Первые части сложных слов с «ё»: отдельно «темно» — наречие темн+о, и модель теряет «ё»\n')
        for a, b in COMPOUNDS: o.write(f'{a}-* = {b}-*\n')
        o.write('# Орфоэпия: междометия без гласной подлиннее, твёрдое [тэ] в заимствованиях\n')
        for a, b in ORTHOEPY: o.write(f'{a} = {b}\n'); n += 1
        o.write('# Фразы-подсказки для омографов: слово в этой фразе читается так (tools/phrases_extra.txt)\n')
        merged = {}  # одна фраза на два слова («все равно» → «вс+ё равн+о»): ключ в словаре один, замены складываются
        for w, items in sorted(phrases_extra.load_extra().items()):
            for phrase, var in items:
                merged[phrase] = re.sub(r'(?<![а-яё])' + re.escape(w) + r'(?![а-яё])', var, merged.get(phrase, phrase), count=1)
        # «$Толстого» — регистровый ключ Демагога: «$» только в ключе, в замене его быть не должно
        for phrase, out in merged.items(): o.write(f'{phrase} = {out[1:] if phrase.startswith("$") and not phrase.startswith("$$") else out}\n'); n += 1
        o.write('# Ударение на предлоге: слеплено в одно слово, «н+абок» (tools/phrases_clitic.txt)\n')
        for line in open(os.path.join(HERE, 'phrases_clitic.txt'), encoding='utf-8'):
            if '=' in line.split('#', 1)[0]: o.write(line.strip() + '\n'); n += 1
    print(f'системный словарь: ударений {len(fixes)}, имён {len(names)}, фраз {n} → {ASSETS}')


if __name__ == '__main__':
    main()
