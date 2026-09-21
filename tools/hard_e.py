#!/usr/bin/env python3
"""Твёрдый согласный перед «е» в заимствованиях («энергия» [нэ], «тест» [тэ]): модель читает мягко.
Источник — приложение Викисловаря «Русские слова с твёрдым парным согласным перед Е» (по ОЭСРЯ-2010 и
Крысину): берутся слова без пометы и с {+е} (твёрдый — основной вариант); {=} и {+э} — мимо, там мягкий
не ошибка. Сюда же «е» после гласной без йотации (прое́кт, дие́та). Раздел «Начальные и конечные части слов»
(интер…, нейро…) не берётся: маска «интер*» зацепила бы «интерес».

Правила «е» → «э» без ударения: применяются в HardE.kt после акцентора, так что акцентор, словарь ударений и
омографы видят обычное написание (на «энэргия» акцентор давал «энэрг+ия», на «интэрнэт» молчал). Основа
≥ 6 букв — маска «основа*» (ловит и производные, по норме они читаются так же); короче — перечисление форм
леммы по базе Lecron («тест*» зацепил бы «тесто», «темп*» — «температуру»); у лемм на «-ия» основа без «-ия»
и порог 5 — ради производных (энерг-етика, аллерг-ен). ENUM_ONLY — длинные основы,
под маску которых лезут чужие слова с мягким («темпер*» → температура, «мистер*» → мистерия).

  python3 tools/hard_e.py   — app/build/hard_e.wiki (качается, если нет) + ../lecron-dict/words.db (вне
                              репозитория; без него — только леммы) → app/src/main/assets/hard_e.txt"""
import os, re, sqlite3, sys, unicodedata, urllib.parse, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__)); ROOT = os.path.dirname(HERE)
WIKI = os.path.join(ROOT, 'app/build/hard_e.wiki'); LECRON = os.path.join(ROOT, '..', 'lecron-dict', 'words.db')
OUT = os.path.join(ROOT, 'app/src/main/assets/hard_e.txt')
TITLE = 'Приложение:Русские слова с твёрдым парным согласным перед Е'
URL = 'https://ru.wiktionary.org/w/index.php?action=raw&title=' + urllib.parse.quote(TITLE)
MIN_STEM = 6
# под маску лезут чужие слова с мягким: каратель, мистерия, постеречь, сентиментальный, температура, термостат, катер, остерегать, астра
ENUM_ONLY = {'карате', 'мистер', 'постер', 'сентимо', 'темпера', 'темперировать', 'термос', 'катерна', 'остерия', 'астерия'}
SKIP = {'изабелла'}   # сорт винограда [бэ], но чаще это имя [б'е]
# формы, совпавшие с русским словом: стена, «бел как снег», стёк без ё, «в форте/тире», «к новой эре», «о каре»,
# дат. п. «амбра», род. п. «тесло», «в мате»
SKIP_FORMS = {'стен', 'бел', 'стек', 'форте', 'тире', 'эре', 'каре', 'амбре', 'тесла', 'мате'}
EXTRA = {'тест': 'тестам тестами тестах'}   # в Lecron формы «теста» отданы «тесту»; ед. ч. (теста, тесте…) — «из теста», не берём
ENDINGS = ('ироваться', 'ировать', 'ться', 'ть', 'ый', 'ий', 'ой', 'ия', 'а', 'я', 'о', 'е', 'ь', 'й')


def fetch():
    if not os.path.exists(WIKI):
        req = urllib.request.Request(URL, headers={'User-Agent': 'ruvoice-tools/1.0 (https://github.com/kost/ruvoice)'})
        os.makedirs(os.path.dirname(WIKI), exist_ok=True)
        open(WIKI, 'wb').write(urllib.request.urlopen(req).read())
    return open(WIKI, encoding='utf-8').read()


def parse(text):
    """→ {лемма: (позиции твёрдых «е», ударение или -1, нельзя_маску)}; позиции по слову без диакритики."""
    body = text.split('== А ==', 1)[1]
    out = {}
    seg_re = re.compile(r'\[\[([^|\]]+)\|([^\]]+)\]\]( \{(=|\+э|\+е)\})?')
    for line in body.splitlines():
        if not line.startswith('*'): continue
        no_mask = '[но' in line
        for lemma, disp, _, mark in seg_re.findall(line):
            if mark in ('=', '+э'): continue
            plain, hard, stress = '', [], -1
            for chunk in re.split(r'(\{\{red\|[^}]*\}\})', disp):
                m = re.fullmatch(r'\{\{red\|([^}]*)\}\}', chunk); s = m.group(1) if m else chunk
                for ch in unicodedata.normalize('NFD', s):
                    if ch == '́': stress = len(plain) - 1
                    elif unicodedata.combining(ch): continue
                    else:
                        if m and ch == 'е': hard.append(len(plain))
                        plain += ch
            lemma = lemma.strip().lower()
            if plain != lemma or len(lemma) < 3 or not hard or not re.fullmatch(r'[а-яё -]+', lemma): continue
            old = out.get(lemma)
            if old: out[lemma] = (sorted(set(old[0]) | set(hard)), old[1] if old[1] >= 0 else stress, old[2] or no_mask)
            else: out[lemma] = (hard, stress, no_mask)
    return out


def stem_of(lemma, hard):
    for e in ENDINGS:
        if lemma.endswith(e) and len(lemma) > len(e):
            s = lemma[:-len(e)]
            if all(p < len(s) for p in hard): return s
    return lemma


def hardened(word, hard):
    if any(p >= len(word) or word[p] != 'е' for p in hard): return None
    return ''.join('э' if i in hard else c for i, c in enumerate(word))


def main():
    lemmas = parse(fetch())
    db = sqlite3.connect(LECRON) if os.path.exists(LECRON) else None
    if not db: print('нет', LECRON, '— только леммы, без форм', file=sys.stderr)
    rules = {}; masks = enum = 0
    for lemma, (hard, stress, no_mask) in sorted(lemmas.items()):
        if lemma in SKIP: continue
        stem = stem_of(lemma, hard)
        if len(stem) >= MIN_STEM - (lemma.endswith('ия') and len(stem) == len(lemma) - 2) and not no_mask and ' ' not in lemma and lemma not in ENUM_ONLY:
            rules[stem + '*'] = hardened(stem, hard) + '*'; masks += 1; continue
        forms = {lemma} | set(EXTRA.get(lemma, '').split())
        if db:
            row = db.execute('select id from normal where text = ?', (lemma,)).fetchone()
            if row: forms |= {w for (w,) in db.execute('select text from word where normal_id = ?', (row[0],))}
        for w in sorted(forms):
            h = hardened(w, hard)
            if h and w not in SKIP_FORMS: rules[w] = h
        enum += 1
    with open(OUT, 'w', encoding='utf-8') as o:
        o.write(f'# Твёрдый согласный перед «е»: Викисловарь по ОЭСРЯ-2010, tools/hard_e.py. Лемм {len(lemmas)}: масок {masks}, перечислено {enum}\n')
        for k, v in sorted(rules.items()): o.write(f'{k} = {v}\n')
    print(f'лемм {len(lemmas)}, правил {len(rules)} (масок {masks}, перечислено {enum} лемм) → {OUT}')


if __name__ == '__main__':
    main()
