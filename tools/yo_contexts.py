#!/usr/bin/env python3
"""Контексты ё-омографов из дампа Википедии (там «ё» пишут) → app/build/yo_phrases.txt в формате phrases_extra.txt.
Пары — слова homodict, у которых два варианта различаются только «ё» (все/всё, узнает/узнаёт, небо/нёбо).
Для каждого вхождения формы (строчной или в начале предложения — имена вроде Королёва не берутся) считаются
контексты: (слово слева, форма), (форма, слово справа), (слева, форма, справа). Контекст — строчные слова, «ё» в них
заменена на «е» (в тексте пользователя «ё» обычно нет), запятая между соседом и формой остаётся («всё, что»),
другие знаки — граница; сосед из служебных слов (STOP) не считается. Кандидат: ≥ MIN вхождений и ≥ SHARE одного варианта (для «ё» — ≥ SHARE_YO: написанная «ё»
надёжна, а «е» в Википедии на пятую часть опечатка — «все время», «все равно»); трёхсловный — только если ни один
из его двухсловных не дотягивает. Отбрасываются фразы, где gramPass уже решает (tools/phrases_extra.gram_pick),
и те, что спорят со словарями замен из app/build/ss_rows.tsv (phrases_extra.conflicts).
Все контексты с ≥ MIN вхождениями — в app/build/yo_contexts.tsv (слово, фраза, сколько с «е», сколько с «ё»);
без дампов в аргументах отбор идёт заново по этому tsv.
Запуск: python3 tools/yo_contexts.py [<dump.xml.bz2>...] [--max N страниц]"""
import json, os, re, sys, collections

HERE = os.path.dirname(os.path.abspath(__file__)); ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE); import wiki_yo_corpus as wiki, phrases_extra as pe
from aot_morph import Table
OUT = os.path.join(ROOT, 'app/build/yo_phrases.txt'); TSV = os.path.join(ROOT, 'app/build/yo_contexts.tsv')  # все частые контексты, для просмотра
MIN, SHARE, SHARE_YO = 20, 0.95, 0.75
# сосед — служебное слово или буква: контекст ничего не говорит («жены в», «звезды из», «озера до» — перекос Википедии)
STOP = set('в во на с со и а но к ко от до у за из по о об обо при для над под через же ли бы не ни то это да или что как'.split())
tok_re = re.compile(r'[А-Яа-яЁё]+(?:-[А-Яа-яЁё]+)*|,|[^\sА-Яа-яЁё,]')


def pairs():
    d = json.load(open(os.path.join(ROOT, 'app/src/main/assets/silero/silero_ru.json'), encoding='utf-8'))
    homo, gram = d['homodict'], d['gram']
    out = {}  # форма с «е» → {вариант без «+»: вариант}
    for w, vs in homo.items():
        if len(vs) == 2 and {v.replace('+', '').replace('ё', 'е') for v in vs} == {w} and any('ё' in v for v in vs):
            out[w] = {v.replace('+', ''): v for v in vs}
    return out, gram


def contexts(text, words, stat):
    """stat[(слово, фраза)][0/1] += 1: 0 — вариант с «е», 1 — с «ё»."""
    for line in wiki.clean(text).split('\n'):
        if not line or line[0] in '{|!=': continue
        toks = [m.group() for m in tok_re.finditer(line)]
        for i, t in enumerate(toks):
            if not t[0].isalpha(): continue
            low = t.lower(); w = low.replace('ё', 'е')
            if w not in words or low not in words[w]: continue
            if t[0].isupper() and i > 0 and (toks[i - 1] == ',' or toks[i - 1][0].isalpha()): continue  # имя посреди фразы
            left = right = None
            j = i - 1; comma = j >= 0 and toks[j] == ','
            if comma: j -= 1
            if j >= 0 and toks[j][0].isalpha(): left = toks[j].lower().replace('ё', 'е') + (',' if comma else '')
            j = i + 1; comma = j < len(toks) and toks[j] == ','
            if comma: j += 1
            if j < len(toks) and toks[j][0].isalpha(): right = (', ' if comma else ' ') + toks[j].lower().replace('ё', 'е')
            yo = 'ё' in low
            if left: stat[(w, f'{left} {w}')][yo] += 1
            if right: stat[(w, f'{w}{right}')][yo] += 1
            if left and right: stat[(w, f'{left} {w}{right}')][yo] += 1


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    limit = int(sys.argv[sys.argv.index('--max') + 1]) if '--max' in sys.argv else 0
    words, gram = pairs()
    # пары, на которых модель промахивалась в корпусе tools/yo_eval.py: для остальных (берег/берёг) фразы ни к чему
    hard = set()
    miss = os.path.join(ROOT, 'app/build/yo_eval_miss.txt')
    if os.path.exists(miss):
        for line in open(miss, encoding='utf-8'):
            if not line.startswith('#'): hard.add(line.split(' | ')[1].replace('ё', 'е'))
    stat = collections.defaultdict(lambda: [0, 0])
    n = 0
    if not args:
        for line in open(TSV, encoding='utf-8'):
            if line[0] != '#': w, phrase, ne, nyo = line.rstrip('\n').split('\t'); stat[(w, phrase)] = [int(ne), int(nyo)]
    for path in args:
        for txt in wiki.pages(path, lambda k: print(path, k, 'страниц,', len(stat), 'контекстов', flush=True)):
            contexts(txt, words, stat); n += 1
            if n % 200000 == 0:  # ponytail: чистка одиночных контекстов ради памяти; редкий контекст может недосчитаться пары вхождений
                for k in [k for k, v in stat.items() if v[0] + v[1] < 2]: del stat[k]
            if limit and n >= limit: break
        if limit and n >= limit: break
    good = {}  # (слово, фраза) → (вариант, n, доля)
    dropped = collections.Counter()
    tsv = open(TSV, 'w', encoding='utf-8') if args else None
    if tsv: tsv.write('# слово\tфраза\tс «е»\tс «ё»\n')
    for (w, phrase), (ne, nyo) in sorted(stat.items(), key=lambda x: (x[0][0], -sum(x[1]))):
        tot = ne + nyo
        if tot < MIN: continue
        if tsv: tsv.write(f'{w}\t{phrase}\t{ne}\t{nyo}\n')
        if ne < SHARE * tot and nyo < SHARE_YO * tot: continue
        if hard and w not in hard: dropped['модель не промахивается'] += 1; continue
        if any(t in STOP or len(t) < 2 for t in re.split(r'[ ,]+', phrase) if t != w): dropped['сосед — служебное слово'] += 1; continue
        var = next(v for k, v in words[w].items() if ('ё' in k) == (nyo > ne))
        good[(w, phrase)] = (var, tot, max(ne, nyo) / tot)
    by_word = pe.dict_phrases(); morph = Table()
    rows = []
    for (w, phrase), (var, tot, share) in good.items():
        toks = re.split(r'[ ,]+', phrase); i = toks.index(w)
        if len(toks) == 3 and ((w, ' '.join(phrase.split(' ')[:2])) in good or (w, ' '.join(phrase.split(' ')[1:])) in good):
            dropped['двухсловный уже есть'] += 1; continue
        if w in gram and i > 0 and pe.gram_pick(toks[i - 1], toks[i - 2] if i > 1 else None, gram[w], True, w, morph,
                                                 toks[i - 3] if i > 2 else None, toks[i - 4] if i > 3 else None) is not None \
                or w == 'все' and i == 0 and len(toks) > 1 and pe.vse_pick(toks[1], morph, gram) == var:
            dropped['gramPass решает'] += 1; continue
        if pe.conflicts(by_word, w, phrase, var): dropped['спорит со словарями'] += 1; continue
        rows.append((w, phrase, var, tot, share))
    rows.sort(key=lambda r: (r[0], -r[3]))
    with open(OUT, 'w', encoding='utf-8') as o:
        o.write(f'# ё-контексты из Википедии (tools/yo_contexts.py): ≥ {MIN} вхождений, ≥ {SHARE:.0%} одного варианта (≥ {SHARE_YO:.0%} для «ё»)\n')
        cur = None
        for w, phrase, var, tot, share in rows:
            if w != cur: cur = w; o.write(f'\n# {w}: {" / ".join(words[w].values())}\n')
            o.write(f'{phrase} = {var}  # {tot} вхождений, {share:.0%}\n')
    print(f'страниц {n}, контекстов {len(stat)}, кандидатов {len(good)}, отброшено {dict(dropped)}; '
          f'фраз {len(rows)}, слов {len(set(r[0] for r in rows))} → {OUT}')


if __name__ == '__main__':
    main()
