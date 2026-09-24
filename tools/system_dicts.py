#!/usr/bin/env python3
"""Системный словарь приложения: tools/stress_fixes.txt, tools/wiki_names.txt, tools/lib_names.txt, tools/phrases_extra.txt и tools/phrases_clitic.txt → assets/dicts/{stress,replace}/Системный.txt.
В аппке это обычные списки «Системный» на вкладках «Ударения» и «Замены»: их нельзя удалить и править,
но можно выключить; при старте файл в данных приложения обновляется из assets, если отличается.
Запуск: python3 tools/system_dicts.py (export_silero_stress.py вызывает сам)."""
import os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__)); ROOT = os.path.dirname(HERE)
ASSETS = os.path.join(ROOT, 'app/src/main/assets/dicts')
sys.path.insert(0, HERE)
# по корпусу Википедии (tools/yo_eval.py): тёмно-зелёный, чёрно-белый, жёлто-, зелёно-, взлётно-посадочная, ликёро-водочный
COMPOUNDS = [('темно', 'тёмно'), ('черно', 'чёрно'), ('желто', 'жёлто'), ('зелено', 'зелёно'), ('взлетно', 'взлётно'), ('ликеро', 'ликёро')]
# Орфоэпия, проверено на слух (tmp/ortho): междометия без гласной модель проговаривает слишком коротко.
# Твёрдое [тэ]/[нэ] в заимствованиях («тест», «темп», «энергия») — не здесь, а в assets/hard_e.txt (tools/hard_e.py):
# замена идёт после акцентора, иначе на «тэст»/«энэргия» он промахивается.
# «дорого» модель читает «дорово»: окончание «-ого» → [ова] она применяет и к наречию; «-ога» без ударения звучит так же.
# «кого-то», «какого-нибудь»: перед дефисом модель читает «-ого/-его» с [г] (на слух 23.09.2026). Список, а не правило:
# у «много-то», «строго-то», «Диего-то» «г» корневая. Формы — все такие из частотной базы Lecron, кроме «самого» (омограф).
OVO_ALL = ['ког+о', 'чег+о', 'как+ого', 'чьег+о']                     # + «-то», «-нибудь», «-либо»
OVO_TO = ['всег+о', 'отчег+о', 'ег+о', 'нег+о', 'так+ого', '+этого', 'ничег+о', 'отт+ого', 'тог+о', 'одн+ого', 'друг+ого',
          'сво+его', 'н+ашего', 'мо+его', 'тво+его', 'посл+еднего']    # только «-то»
OVO = [(w.replace('+', '') + '-' + p, re.sub(r'г(\+?о)$', r'в\1', w) + '-' + p)
       for ws, ps in ((OVO_ALL, ('то', 'нибудь', 'либо')), (OVO_TO, ('то',))) for w in ws for p in ps]
# [шн] на месте «чн»: «конечно», «скучно», «что», «прачечная», «пустячный» модель читает верно, а эти — как написано
# (tmp/ortho/3, на слух 19.09.2026). Отчества на -ична все по норме [шн]. Ударение явное: акцентор «яишницу» не знает.
ORTHOEPY = [('гм', 'гмм'), ('хм', 'хмм'), ('тсс', 'тссс'), ('дорого', 'д+орога'),
            ('нарочно', 'нар+ошно'), ('ничто', 'ништ+о'), ('яичниц*', 'я+ишниц*'), ('скворечник*', 'сквор+ешник*')] + \
    [(n + 'ичн*', re.sub(r'([аеиоуыэюя])([^аеиоуыэюя]*)$', r'+\1\2', n) + 'ишн*') for n in 'Ильин Никит Кузьмин Лукин Фомин Савв'.split()] + \
    OVO + [('по-моему', 'по-м+оему'), ('по-твоему', 'по-тв+оему'), ('по-своему', 'по-св+оему')] + \
    [(r'~(?<![\p{L}+-])([аоэу])(?=[!?])', '$1$1')] + \
    [(r'~(?<![\p{L}+-])(\p{L}+)-(\1)(?=, а(?!\p{L}))', '$1 $2')] + \
    [(r'~(?<![\p{L}\p{N}][^\p{L}\p{N}.!?…]{0,20})(?<![–—-][\s«"(]{0,5})(спокойн\p{L}*)', '— $1')]
# «что-что, а…»: с дефисом модель глотает первое «ч» посреди фразы («то-что»), на слух лучше всего пробел.
# «Спокойное лицо» в начале предложения eugene читает «покойное», «спокойно»/«спокойный» тоже; тире перед словом лечит,
# другие «с+согласная» в начале он читает верно (на слух 24.09.2026, share/spokoinoe)


def load_lib(path, skip):
    """«слово = сл+ово»: буквы значения могут отличаться от ключа на «ё»/«э» — словарь подменяет слово целиком (Stress.userDictPass)"""
    out = {}
    for line in open(path, encoding='utf-8'):
        t = line.split('#', 1)[0].strip()
        if '=' not in t: continue
        k, v = (x.strip() for x in t.split('=', 1))
        same = lambda a, b: len(a) == len(b) and all(x == y or {x, y} <= {'е', 'ё', 'э'} for x, y in zip(a, b))
        assert v.count('+') == 1 and same(v.replace('+', ''), k), line
        if k not in skip: out[k] = v
    return out


def main():
    import stress_fixes, phrases_extra
    fixes = stress_fixes.load_fixes()
    os.makedirs(os.path.join(ASSETS, 'stress'), exist_ok=True); os.makedirs(os.path.join(ASSETS, 'replace'), exist_ok=True)
    with open(os.path.join(ASSETS, 'stress', 'Системный.txt'), 'w', encoding='utf-8') as o:
        o.write('# Поправки ударений: модель ставит иначе, против неё словари AOT и Викисловаря вместе или словарь Демагога с одним из них (tools/stress_fixes.txt)\n')
        for w, v in sorted(fixes.items()): o.write(f'{w} {v}\n')
        lib = load_lib(os.path.join(HERE, 'lib_names.txt'), set(fixes))
        names = {w: v for w, v in stress_fixes.load_fixes(os.path.join(HERE, 'wiki_names.txt')).items() if w not in fixes and w not in lib}
        o.write('# Имена и термины из первых абзацев Википедии, где модель ставит ударение иначе (tools/wiki_names.py); с «ё» — и через «е»\n')
        for w, v in sorted(names.items()):
            o.write(f'{w} {v}\n')
            if 'ё' in w: o.write(f'{w.replace("ё", "е")} {v}\n')
        o.write('# Слова и имена из библиотеки книг, где модель ставит иначе (books/lib_names.py); значение может нести «ё» и твёрдое «э»\n')
        for w, v in sorted(lib.items()):
            o.write(f'{w} {v}\n')
            yo = v.replace('+', '').replace('э', 'е')
            if 'ё' in yo and yo != w: o.write(f'{yo} {v}\n')   # ключ через «е», в тексте может быть «ё»
    n = 0
    with open(os.path.join(ASSETS, 'replace', 'Системный.txt'), 'w', encoding='utf-8') as o:
        o.write('# Первые части сложных слов с «ё»: отдельно «темно» — наречие темн+о, и модель теряет «ё»\n')
        for a, b in COMPOUNDS: o.write(f'{a}-* = {b}-*\n')
        o.write('# Орфоэпия: междометия без гласной подлиннее\n')
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
    print(f'системный словарь: ударений {len(fixes)}, имён {len(names)}, из библиотеки {len(lib)}, фраз {n} → {ASSETS}')


if __name__ == '__main__':
    main()
