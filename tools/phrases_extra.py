#!/usr/bin/env python3
"""Фразы-подсказки для омографов поверх Silero Stress: tools/phrases_extra.txt → «phrases» в silero_ru.json.

  python3 tools/phrases_extra.py build  — собрать кандидатов из фраз словарей замен (app/build/ss_rows.tsv:
      ключ \\t словарь \\t Silero Stress; словари лежат в гитигнорной test/resources/local/, см. tools/stress_survey.py),
      где модель на фразе промахнулась, а gramPass молчит. Берутся только слова из homodict (вариант словаря —
      один из двух вариантов homodict) и слова из таблицы gram (вариант — из неё); имена и прочее — нет.
      Список просматривается руками, потом коммитится.
  python3 tools/phrases_extra.py apply  — дописать фразы в json (сейчас не используется: список идёт в системный
      словарь замен, tools/system_dicts.py).
Формат строки: «фраза = слов+о». Слово с «+» должно входить в фразу."""
import json, os, re, sys, collections
from aot_morph import Table

HERE = os.path.dirname(os.path.abspath(__file__)); ROOT = os.path.dirname(HERE)
BUILD = os.path.join(ROOT, 'app/build'); EXTRA = os.path.join(HERE, 'phrases_extra.txt')
JSON = os.path.join(ROOT, 'app/src/main/assets/silero/silero_ru.json')
word_re = re.compile(r'[а-яё+-]+')

GEN = set('с со из изо от ото у до без безо для около вдоль возле мимо после кроме вокруг против среди из-за из-под ради вместо '
          'подле близ накануне вне насчёт ввиду вследствие позади впереди посреди сверх свыше внутри внутрь вроде '
          'два две три четыре полтора полторы нет'.split())
PREP = set('в во на за под подо через про сквозь о об обо по при к ко над надо перед передо между меж'.split())
PRON = set('я ты он она оно мы вы они'.split())
COUNT = set('два две три четыре оба обе полтора полторы'.split())
NOT_ADJ = set('его него чего кого ничего никого некого нечего всего сего много немного итого'.split())


NOM_PL = re.compile(r'[а-яё]+(ые|ие)$')
PARTICIPLE_PL = re.compile(r'[а-яё]+((вш|ш|щ)ие|(нн|т|м)ые)$')
PASSIVE_PL = re.compile(r'[а-яё]+(нн|т|м)ые$')


def gram_pick(prev, prev2, e, in_homo=False, w=None, morph=None, prev3=None, prev4=None):
    """Зеркало Stress.gramPass: что поставит грамматический проход, None — молчит. morph — aot_morph.Table для согласования
    с прилагательным (w — само слово, prev3/prev4 — для согласования через слово); без неё, как в Kotlin без Morph,
    это правило выключено."""
    if prev in ('под', 'за') and prev2 == 'из': return e.get('g') or e.get('n')
    if prev == 'за' and prev2 == 'что': return None
    if prev in GEN: return e.get('g') or e.get('n')
    if prev in ('в', 'во') and 'g' in e and 'p' not in e: return None
    if prev in PREP: return e.get('p') or e.get('g') or (None if prev in ('в', 'во', 'на', 'при') and in_homo else e.get('n'))
    if prev in PRON: return e.get('v')
    if prev.endswith(('ого', 'его')):
        return None if prev in NOT_ADJ or prev.startswith(('сам', 'котор')) or prev.endswith(('вшего', 'ющего', 'ущего', 'ащего', 'ящего')) else e.get('g') or e.get('n')
    if morph and w and ('g' in e or 'p' in e): return agree(morph, prev, prev2, w, e, prev3, prev4)
    return None


def agree(m, prev, prev2, w, e, prev3=None, prev4=None):
    """Зеркало Stress.agree: «высокие стены» → мн., «высокой стены» → род. ед. Прилагательное перед словом согласуется
    с ним в клетке род. ед. (g) или им./вин. мн. (p), но не в обеих; в обеих или ни в одной («вся округа») — молчим.
    После «две/три/четыре» прилагательное во мн., а слово — в род. ед.: «две толстые ноги».
    Через слово или предложную группу («покрытые пылью доски», «почерневшие от времени доски») прилагательное или
    причастие на «-ые/-ие» даёт мн.; причастий в таблице нет — по суффиксу, действительное только за предлогом."""
    if prev == 'всё': return None  # в таблице «ё» = «е», а «всё» — не «все»
    tw = m.tags(w)
    if not m.is_noun(tw): return None
    ta = m.tags(prev.replace('+', ''))
    if not m.is_adjective(ta) or m.is_noun(ta):
        if 'p' not in e or prev in GEN or prev in PREP or not prev2: return None
        far = prev2 in GEN or prev2 in PREP
        cand, head = (prev3, prev4) if far else (prev2, prev3)
        if not cand: return None
        if not far and m.is_noun(ta) and m.noun_cases(ta, True) & {'nom', 'acc'}: return None  # «бревенчатые стены терема»
        tc = m.tags(cand)
        adj_plural = cand != 'все' and NOM_PL.match(cand) and ((PARTICIPLE_PL if far else PASSIVE_PL).match(cand) if tc == 0 else m.is_adjective(tc) and not m.is_noun(tc))
        return None if not adj_plural else e.get('g') if head in COUNT else e['p']
    if prev2 in COUNT: return e.get('g')
    def fit(plural):
        noun = m.noun_cases(tw, plural) & ({'nom', 'acc'} if plural else {'gen'})
        genders = [None] if plural else m.genders(tw) or ['m', 'f', 'n']
        return any(m.adj_cases(ta, g, plural) & noun for g in genders)
    sg, pl = fit(False), fit(True)
    return e.get('g') if sg and not pl else e.get('p') if pl and not sg else None


def vse_pick(nxt, morph, gram):
    """«все» + слово только мн. ч. («все крупные») или слово из gram, согласованное с «все» во мн. («все окна») → «вс+е»;
    None — молчим."""
    if not morph or not nxt: return None
    e = gram.get(nxt)
    if morph.plural_only(morph.tags(nxt.replace('+', ''))) or e and 'p' in e and agree(morph, 'все', None, nxt, e) == e['p']: return 'вс+е'
    return None


def norm(key):
    """Ключ словаря → фраза в виде очищенного текста Stress.cleanText: пробел после запятой, без хвостовой точки."""
    s = re.sub(r'\s*,\s*', ', ', key.lower().strip()).strip(' .!?,')
    return re.sub(r'\s+', ' ', s)


def load_extra():
    out = collections.defaultdict(list)  # слово → [(фраза, вариант)]
    if not os.path.exists(EXTRA): return out
    for line in open(EXTRA, encoding='utf-8'):
        s = line.split('#', 1)[0].strip()
        if '=' not in s: continue
        phrase, var = (x.strip() for x in s.split('=', 1))
        w = var.replace('+', '').replace('ё', 'е')  # ё-вариант («все же = вс+ё же») ищется в фразе по «е»
        if re.search(r'(?<![а-яё])' + re.escape(w) + r'(?![а-яё])', phrase): out[w].append((phrase, var))
    return out


def apply(data):
    n = 0
    for w, items in load_extra().items():
        cur = data['phrases'].setdefault(w, [])
        have = {p for p, _ in cur}
        for phrase, var in items:
            if phrase not in have: cur.append([phrase, var]); have.add(phrase); n += 1
        cur.sort(key=lambda x: -len(x[0]))  # длинные фразы раньше, как у Silero
    return n


def dict_phrases():
    """Фразы словарей замен (app/build/ss_rows.tsv): слово → [(фраза, вариант)]; слово с «ё» без «+» — тоже вариант."""
    by_word = collections.defaultdict(list)
    for line in open(os.path.join(BUILD, 'ss_rows.tsv'), encoding='utf-8'):
        key, val, _ = line.rstrip('\n').split('\t')
        for x in word_re.findall(val):
            if '+' in x or 'ё' in x: by_word[x.replace('+', '').replace('ё', 'е')].append((norm(key), x))
    return by_word


def conflicts(by_word, w, phrase, dv):
    """Фраза спорит с фразой словаря, где встречается целиком («а глаза» внутри «…а глаза его…»), а слово там читается иначе."""
    pat = re.compile(r'(?<![а-яё])' + re.escape(phrase).replace('ё', '[её]') + r'(?![а-яё])')
    plain = dv.replace('+', '')
    return any((v.replace('+', '') != plain or '+' in v and v != dv) for k, v in by_word[w] if k != phrase and pat.search(k))


def build():
    with open(JSON, encoding='utf-8') as f: d = json.load(f)
    homo, gram = d['homodict'], d['gram']
    mine = {(w, p) for w, l in load_extra().items() for p, _ in l}  # уже наложенные наши фразы не считаем чужими
    have = {(w, p) for w, l in d['phrases'].items() for p, _ in l} - mine
    seen = set(); rows = []; morph = Table()
    by_word = dict_phrases()  # все фразы словарей, не только промахи
    for line in open(os.path.join(BUILD, 'ss_rows.tsv'), encoding='utf-8'):
        key, val, got = line.rstrip('\n').split('\t')
        dw = [(x.replace('+', ''), x) for x in word_re.findall(val)]
        gw = [(x.replace('+', ''), x) for x in word_re.findall(got)]
        if len(dw) < 2 or len(dw) != len(gw) or [w for w, _ in dw] != [w for w, _ in gw]: continue
        toks = [w for w, _ in dw]
        for i, (w, dv) in enumerate(dw):
            if '+' not in dv or dv == gw[i][1]: continue
            ok = w in homo and dv in homo[w] or w in gram and dv in gram[w].values()
            if not ok: continue
            if w in gram and i > 0 and gram_pick(toks[i - 1], toks[i - 2] if i > 1 else None, gram[w], w in homo, w, morph,
                                                 toks[i - 3] if i > 2 else None, toks[i - 4] if i > 3 else None) is not None: continue
            phrase = norm(key)
            if (w, phrase) in have or (w, phrase) in seen or w not in phrase.split(' ') and w not in re.split(r'[ ,-]+', phrase): continue
            seen.add((w, phrase)); rows.append((w, phrase, dv, w in homo))
    dropped = sum(conflicts(by_word, w, phrase, dv) for w, phrase, dv, _ in rows)
    rows = [r for r in rows if not conflicts(by_word, r[0], r[1], r[2])]
    rows.sort(key=lambda r: (not r[3], r[0], r[1]))
    with open(EXTRA, 'w', encoding='utf-8') as o:
        o.write('# Фразы-подсказки поверх Silero Stress, собраны из чужих словарей замен (tools/phrases_extra.py build),\n'
                '# вариант проверен по homodict/AOT, список просмотрен руками. Формат: «фраза = слов+о».\n')
        cur = None
        for w, phrase, dv, in_homo in rows:
            if w != cur: cur = w; o.write(f'\n# {w}: {" / ".join(homo[w]) if in_homo else " / ".join(sorted(set(gram[w].values())))}{"" if in_homo else " (не в homodict)"}\n')
            o.write(f'{phrase} = {dv}\n')
    print(f'отброшено как спорные: {dropped}; фраз: {len(rows)}, слов: {len(set(r[0] for r in rows))}, из них в homodict: {len(set(r[0] for r in rows if r[3]))} → {EXTRA}')


if __name__ == '__main__':
    if sys.argv[1:] == ['build']: build()
    elif sys.argv[1:] == ['apply']:
        with open(JSON, encoding='utf-8') as f: d = json.load(f)
        n = apply(d)
        with open(JSON, 'w', encoding='utf-8') as f: json.dump(d, f, ensure_ascii=False)
        print(f'phrases += {n}')
    else: print(__doc__)
