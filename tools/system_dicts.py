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
# то же без «ё»: «бело-голубая» модель читает «бел+о-», как наречие; «ярко-», «красно-», «бледно-» и прочие читает верно
# (отчёт пользователя 29.09.2026, проверено на Silero Stress)
COMPOUND_STRESS = [('бело', 'б+ело'), ('светло', 'св+етло'), ('ало', '+ало')]
# Орфоэпия, проверено на слух (tmp/ortho): междометия без гласной модель проговаривает слишком коротко.
# Твёрдое [тэ]/[нэ] в заимствованиях («тест», «темп», «энергия») — не здесь, а в assets/hard_e.txt (tools/hard_e.py):
# замена идёт после акцентора, иначе на «тэст»/«энэргия» он промахивается.
# «дорого» модель читает «дорово»: окончание «-ого» → [ова] она применяет и к наречию; «-ога» без ударения звучит так же.
# «кого-то», «какого-нибудь»: перед дефисом модель читает «-ого/-его» с [г] (на слух 23.09.2026). Список, а не правило:
# у «много-то», «строго-то», «Диего-то» «г» корневая. Формы — все такие из частотной базы Lecron, кроме «самого» (омограф).
OVO_ALL = ['ког+о', 'чег+о', 'как+ого', 'чьег+о']                     # + «-то», «-нибудь», «-либо»
OVO_TO = ['всег+о', 'отчег+о', 'ег+о', 'нег+о', 'так+ого', '+этого', 'ничег+о', 'отт+ого', 'тог+о', 'одн+ого', 'друг+ого',
          'сво+его', 'н+ашего', 'мо+его', 'тво+его', 'посл+еднего']    # только «-то»
# Частица слитно: через дефис Stress ставит ей своё ударение («ков+о-ниб+удь», «ков+о-т+о»), слово распадается на два
# (жалоба пользователя 06.10.2026, предложено «ков+онибудь»; так же в словаре KooB «чев+ото»)
OVO = [(w.replace('+', '') + '-' + p, re.sub(r'г(\+?о)$', r'в\1', w) + p)
       for ws, ps in ((OVO_ALL, ('то', 'нибудь', 'либо')), (OVO_TO, ('то',))) for w in ws for p in ps]
# [шн] на месте «чн»: «конечно», «скучно», «что», «прачечная», «пустячный» модель читает верно, а эти — как написано
# (tmp/ortho/3, на слух 19.09.2026). Отчества на -ична все по норме [шн]. Ударение явное: акцентор «яишницу» не знает.
# «Семена» с заглавной не в начале предложения — имя (Семёна), BERT берёт «семен+а» («увидели Семена у ворот»); в начале
# предложения это чаще «семена» растений, там решает модель. «Семёну», «Семёном», «Семёне» модель читает верно (30.09.2026)
# «что-то», «не за что» слитно (как OVO), [ш] явно: слитное слово не словарное; «ва-банк» без ударения на «ва», «окне» [акне] — жалобы пользователя
# 06.10.2026, предложены им же; на слух я не проверял. Слитно по той же причине, что OVO
ORTHOEPY = [('гм', 'гмм'), ('хм', 'хмм'), ('тсс', 'тссс'), ('дорого', 'д+орога'),
            ('что-то', 'шт+ото'), ('не за что', 'н+езашто'), ('ва-банк', 'ваб+анк'), ('окне', 'акн+е'),
            ('нарочно', 'нар+ошно'), ('ничто', 'ништ+о'), ('яичниц*', 'я+ишниц*'), ('скворечник*', 'сквор+ешник*')] + \
    [(n + 'ичн*', re.sub(r'([аеиоуыэюя])([^аеиоуыэюя]*)$', r'+\1\2', n) + 'ишн*') for n in 'Ильин Никит Кузьмин Лукин Фомин Савв'.split()] + \
    OVO + [('по-моему', 'по-м+оему'), ('по-твоему', 'по-тв+оему'), ('по-своему', 'по-св+оему')] + \
    [(r'~(?<![\p{L}+-])([аоэу])(?=[!?])', '$1$1')] + \
    [(r'~(?<![\p{L}+-])(\p{L}+)-(\1)(?=, а(?!\p{L}))', '$1 $2')] + \
    [(r'~(?<![\p{L}\p{N}][^\p{L}\p{N}.!?…]{0,20})(?<![–—-][\s«"(]{0,5})(спокойн\p{L}*)', '— $1')] + \
    [(r'~(?-i)(?<=[\p{L}\p{N},;:)»"][^\p{L}\p{N}.!?…]{1,5})Семена(?![\p{L}+-])', 'Сем+ёна')]
# Аббревиатуры с устоявшимся чтением не по именам букв (Abbrev прочёл бы «эс эс эс ээ+р»); от владельца 30.09.2026
ABBREV = [('СССР', '+эс+эсэс+эр')]
# Ключи через «е», которые не пишем за именем с «ё» из Википедии: так пишутся русские имена и фамилии, которых нет в AOT
# («Дарьё» — Дарио, а «Дарье» — от «Дарья»; «Пёти» и «Пети»). Проверка — формы имён OpenCorpora (pymorphy3), 30.09.2026
NAME_E = set('''дарье инге касе тосе фане дане гере пети федоры бене эдена перун перуна керзон керзона
    бондарев бондарева василев василева василеве василевой василеву василевым горячев гребнев берсенева
    веселовская веселовский веселовским веселовского веселовской веселовском'''.split())
# «что-что, а…»: с дефисом модель глотает первое «ч» посреди фразы («то-что»), на слух лучше всего пробел.
# «Спокойное лицо» в начале предложения eugene читает «покойное», «спокойно»/«спокойный» тоже; тире перед словом лечит,
# другие «с+согласная» в начале он читает верно (на слух 24.09.2026, share/spokoinoe)


def aot_forms():
    """Все словоформы AOT (app/build/aot_forms.tsv из tools/aot_forms.py), строчными, «ё» как в словаре."""
    path = os.path.join(ROOT, 'app/build/aot_forms.tsv')
    assert os.path.exists(path), f'нет {path}: сначала python3 tools/aot_forms.py <каталог morph_dict/data/Russian>'
    return {line.split('\t', 1)[0] for line in open(path, encoding='utf-8')}


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


def load_hyphen(path=os.path.join(HERE, 'hyphen_first.txt')):
    """«темно = т+ёмно» — первая часть сложного слова; «по-моему = по-м+оему» — слово целиком."""
    out = []
    for line in open(path, encoding='utf-8'):
        t = line.split('#', 1)[0].strip()
        if '=' not in t: continue
        k, v = (x.strip() for x in t.split('=', 1))
        assert v.count('+') == 1 and v.replace('+', '').replace('ё', 'е') == k, line
        out.append((k, v))
    return out


def main():
    import stress_fixes, phrases_extra
    drop = stress_fixes.load_drop()   # tools/dict_drop.txt: и ключи, и е-копии
    fixes = {w: v for w, v in stress_fixes.load_fixes().items() if w not in drop}
    os.makedirs(os.path.join(ASSETS, 'stress'), exist_ok=True); os.makedirs(os.path.join(ASSETS, 'replace'), exist_ok=True)
    with open(os.path.join(ASSETS, 'stress', 'Системный.txt'), 'w', encoding='utf-8') as o:
        o.write('# Поправки ударений: модель ставит иначе, против неё словари AOT и Викисловаря вместе или словарь Демагога с одним из них (tools/stress_fixes.txt)\n')
        for w, v in sorted(fixes.items()): o.write(f'{w} {v}\n')
        lib = load_lib(os.path.join(HERE, 'lib_names.txt'), set(fixes) | drop)
        names = {w: v for w, v in stress_fixes.load_fixes(os.path.join(HERE, 'wiki_names.txt')).items() if w not in fixes and w not in lib and w not in drop}
        # Ключ через «е» для слова с «ё» ставит «ё» безусловно, поэтому не пишем его, если так пишется другое слово:
        # своя строка с этим ключом («Лебрен» и «Лебрён», «Неман» и «Нёман») или форма AOT («Петра» от «Пётр», не «Пётра»)
        known = set(fixes) | set(names) | set(lib) | aot_forms() | NAME_E | drop
        o.write('# Имена и термины из первых абзацев Википедии, где модель ставит ударение иначе (tools/wiki_names.py); с «ё» — и через «е», если так не пишется другое слово\n')
        for w, v in sorted(names.items()):
            o.write(f'{w} {v}\n')
            e = w.replace('ё', 'е')
            if e != w and e not in known: o.write(f'{e} {v}\n'); known.add(e)
        o.write('# Слова и имена из библиотеки книг, где модель ставит иначе (books/lib_names.py); значение может нести «ё» и твёрдое «э»\n')
        for w, v in sorted(lib.items()):
            o.write(f'{w} {v}\n')
            yo = v.replace('+', '').replace('э', 'е')
            if 'ё' in yo and yo != w: o.write(f'{yo} {v}\n')   # ключ через «е», в тексте может быть «ё»
    n = 0
    with open(os.path.join(ASSETS, 'replace', 'Системный.txt'), 'w', encoding='utf-8') as o:
        o.write('# Первые части сложных слов с «ё»: отдельно «темно» — наречие темн+о, и модель теряет «ё»\n')
        for a, b in COMPOUNDS: o.write(f'{a}-* = {b}-*\n')
        o.write('# Первые части сложных прилагательных, где модель ставит ударение на конец: «бел+о-голубая»\n')
        for a, b in COMPOUND_STRESS: o.write(f'{a}-* = {b}-*\n')
        o.write('# То же из дефисных словарей Balamoote (tools/hyphen_first.txt): первые части «медико-*», вторые части «по-моему»\n')
        done = {a for a, _ in COMPOUNDS + COMPOUND_STRESS + ORTHOEPY}
        for a, b in load_hyphen():
            if a in done: continue
            o.write(f'{a} = {b}\n' if '-' in a else f'{a}-* = {b}-*\n'); n += 1
        o.write('# Орфоэпия: междометия без гласной подлиннее\n')
        for a, b in ORTHOEPY: o.write(f'{a} = {b}\n'); n += 1
        o.write('# Аббревиатуры с устоявшимся чтением\n')
        for a, b in ABBREV: o.write(f'{a} = {b}\n'); n += 1
        o.write('# Фразы-подсказки для омографов: слово в этой фразе читается так (tools/phrases_extra.txt)\n')
        merged = {}  # одна фраза на два слова («все равно» → «вс+ё равн+о»): ключ в словаре один, замены складываются
        for w, items in sorted(phrases_extra.load_extra().items()):
            for phrase, var in items:
                merged[phrase] = re.sub(r'(?<![а-яё])' + re.escape(w) + r'(?![а-яё])', var, merged.get(phrase, phrase), count=1)
        for phrase in list(merged):   # «адреса надежные» для книг без «ё» (phrases_extra.e_copy); свой е-ключ главнее
            e = phrases_extra.e_copy(phrase)
            if e and e not in merged: merged[e] = merged[phrase]
        # «$Толстого» — регистровый ключ Демагога: «$» только в ключе, в замене его быть не должно
        for phrase, out in merged.items(): o.write(f'{phrase} = {out[1:] if phrase.startswith("$") and not phrase.startswith("$$") else out}\n'); n += 1
        o.write('# Ударение на предлоге: слеплено в одно слово, «н+абок» (tools/phrases_clitic.txt)\n')
        for line in open(os.path.join(HERE, 'phrases_clitic.txt'), encoding='utf-8'):
            if '=' in line.split('#', 1)[0]: o.write(line.strip() + '\n'); n += 1
    print(f'системный словарь: ударений {len(fixes)}, имён {len(names)}, из библиотеки {len(lib)}, фраз {n} → {ASSETS}')


if __name__ == '__main__':
    main()
