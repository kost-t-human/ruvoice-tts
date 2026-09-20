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
          'подле близ накануне вне насчёт насчет ввиду вследствие позади впереди посреди сверх свыше внутри внутрь вроде '
          'два две три четыре полтора полторы нет'.split())
QUANT_GOV = set('мало много немного немало больше меньше достаточно сколько столько полно'.split())   # только g, «он много сопел» глагол
PREP = set('в во на за под подо через про сквозь о об обо по при к ко над надо перед передо между меж'.split())
PRON = set('я ты он она оно мы вы они'.split())
COUNT = set('два две три четыре оба обе полтора полторы'.split())
NOT_ADJ = set('его него чего кого ничего никого некого нечего всего сего много немного итого отчего оттого'.split())


NOM_PL = re.compile(r'[а-яё]+(ые|ие)$')
PARTICIPLE_PL = re.compile(r'[а-яё]+((вш|ш|щ)ие|(нн|т|м)ые)$')
PASSIVE_PL = re.compile(r'[а-яё]+(нн|т|м)ые$')


LOC_PREP = {'в', 'во', 'на', 'при'}
SIZE = set('размером высотой ростом величиной длиной шириной толщиной весом'.split())
PARTICIPLE = ('вшего', 'ющего', 'ущего', 'ащего', 'ящего')
GEN_ADJ = re.compile(r'(лишённ|лишенн|полн)(ый|ая|ое|ые|ого|ой|ых|ым|ыми|ую|ому|ом)$')
ADJ_PL = re.compile(r'[а-яё]+(ые|ие|ых|их)$')
PHASE = re.compile(r'нач(ал[аио]?|ать|ав|ина[а-яё]+|н[а-яё]+)|ста(л[аио]?|ть|в|н(у|ем|ет|ут|ешь|ете|ь|ьте))|продолж[а-яё]+|'
                   r'перест(ал[аио]?|ать|ав|ан[а-яё]+)|прекра[тщ][а-яё]+|конч(ил[аи]?|ать|ай|айте)|'
                   r'брос(ил[аи]?|ить|ь|ьте|ив)|приня(лся|лась|лись|ться)|прим(ется|усь|емся|утся)|буд(у|ешь|ет|ем|ете|ут)')


POSS = {'его', 'её', 'ее', 'их'}
PRON_ADJ_LIKE = {'чем', 'кем', 'ничем', 'никем', 'ним', 'нём', 'нем', 'ней', 'ей', 'ею', 'нею', 'мной', 'тобой', 'собой'}   # «не на чем ча́ю выпить»: не прилагательное


ACC_VERB = re.compile(r'(преврат|превращ|брос|попа[лдсв]|сун|стуч|стукн|закова|заков|ломи|влет|вбе[жг]|ворв|кинул|швыр|толкн|во(шёл|шел|шла|шли|йти|йд)|заман|улов|пойма|разворач|вгляд|загляд)[а-яё]*$')
PRP_ADJ = re.compile(r"[а-яё]+(ом|ем|ой|ей)(ся)?(-то)?$")


def loc2_pick(prev, prev2, prev3, e, w, morph):
    """Слово со вторым предложным с иным ударением (кр+ови / кров+и): после «в/на/при» — l («в кров+и»), но после глагола
    движения это вин. мн. («бросилась в дв+ери»); через прилагательное в предл. — l («в чужой кров+и», «в её кров+и»), а без
    предлога перед таким прилагательным молчим («сосновом лесу» — не знаем, что слева); иначе всегда g («ана́лиз кр+ови»)."""
    if prev in LOC_PREP: return e.get('g') if ACC_VERB.match(prev2 or '') or ACC_VERB.match(prev3 or '') else e['l']   # «сунул голову в дв+ери»
    if prev == 'и' and prev3 in LOC_PREP: return e['l']   # «в крови и гряз+и»
    adj = prev in POSS or (adj_loc(morph, prev, w) if morph and w and morph.tags(prev) else prev not in PRON_ADJ_LIKE and bool(PRP_ADJ.match(prev)))
    if not adj: return e.get('g')
    if prev2 in LOC_PREP: return e['l']
    adj2 = prev2 in POSS or bool(PRP_ADJ.match(prev2 or ''))
    if prev3 in LOC_PREP and adj2: return e['l']
    return e.get('g') if prev2 and not adj2 else None   # «у толстой ц+епи»; «толстой цепи» без левого контекста — молчим


def adj_loc(m, adj, w):
    """Прилагательное согласовано со словом в предл. ед. («густой тени», «самом лесу»), а не в им./вин. мн. («открытые двери»)."""
    ta = m.tags(adj.replace('+', '')); tw = m.tags(w)
    if not m.is_adjective(ta) or not m.is_noun(tw): return False
    if m.adj_cases(ta, None, True) & {'nom', 'acc'}: return False
    return any('prp' in m.adj_cases(ta, g, False) for g in m.genders(tw) or ['m', 'f', 'n'])


def chain_head(prev, prev2, prev3, prev4):
    """Зеркало chainHead в Stress.gramPass: слово перед цепочкой прилагательных во мн. ч., кончающейся на prev."""
    if not prev or not ADJ_PL.match(prev): return prev2
    for c in (prev2, prev3, prev4):
        if not c or not ADJ_PL.match(c): return c
    return None


# после глагола (не причастия) эти слова — им./вин. мн., а не род. ед.: «заблестели глаз+а», «поднял р+уки»; по narusco и
# СинТагРусу ≥93 % при n≥6, а Silero тут ошибается («заблестели гл+аза»). Не после «не/нет» («не поднимал гл+аза» — род.).
VERB_PL = set('глаза руки слова ноги цены голоса войска слезы губы звезды трубы окна стены яйца острова весла колеса леса ордена поля свечи судьбы'.split())
VERB_END = re.compile(r'[а-яё]+([её]т|ит|ут|ют|ат|ят|[её]шь|ишь|[её]м|им|[её]те|ите|л|ла|ло|ли|ть|ти|чь|ай|яй|уй|юй|ой|ей|йте|ся|сь)$')
PARTICIPLE_ANY = re.compile(r'[а-яё]+((вш|ющ|ущ|ащ|ящ)[а-яё]+|(нн|н|т|ск|ш|щ)(ой|ей|ый|ий))$')
GEN_VERB = re.compile(r'[а-яё]*(дости|косн|каса|лиш|бо[иея]|опас|избе|сторон|слуша|проси|спроси|требов|треб|доби|добе|дожид|было|прибыло)[а-яё]*$')
NOT_VERB = set('ли или бы же уж ль ведь здесь хоть чуть пусть ей ней ею нею мной мною тобой тобою собой собою ним нём нем тем всем этим одним '
               'своим моим твоим нашим вашим каким таким самим кем чем ничем никем своей моей твоей нашей вашей всей чьей самой'.split())
NEG = {'не', 'нет', 'ни'}
# слово из VERB_PL перед глаголом во мн. ч. — подлежащее: «глаза блестели», «Глаза выглядели» (BERT в начале фразы берёт
# род. ед.); не после «не», не после «два/оба» в трёх словах («две костлявые р+уки обняли» — счётная форма) и не после
# существительного («створки окн+а распахнулись», «у края л+еса вели»). По narusco+Викисловарю+HomographEval 62:2.
VERB_PL_END = re.compile(r'[а-яё]+(ут|ют|ат|ят|ли)(ся|сь)?$')
DUAL = set('два две три четыре оба обе полтора'.split())
# количество после слова: «воды нет», «времени мало» — род. ед. (narusco+СинТагРус 81:16)
QUANT_NEXT = {'нет', 'мало', 'много', 'немного', 'достаточно', 'больше', 'меньше', 'немало', 'хватает', 'хватало', 'хватит'}
# после притяжательного эти слова во мн. в ≥92 % (золото библиотеки); за род. предлогом, «оба/два», количественным или глаголом
# с родительным — род. ед.; за существительным или прилагательным в косвенном падеже — молчим (см. Stress.possPl)
POSS_PL = set('глаза слова губы яйца окна ноги трубы войска'.split())
POSS_PRON = set('его её ее их мои твои свои наши ваши чьи'.split())
OBLIQUE_END = re.compile(r'[а-яё]+(ых|их|ого|его|ой|ей|ом|ем|ами|ями|ах|ях|ам|ям|ою|ею|ую|ов|ев)(ся)?$')
# подлежащее через одно слово перед глаголом во мн. («глаза снова сверкнули»): ≥94 % по золоту только для этих слов
SUBJ_ADV = set('глаза руки слова ноги цены войска губы яйца свечи'.split())
NOT_ADVERB = set('и а но или же ли я ты он она оно мы вы они'.split())


def verb_like(t, morph):
    return bool(t) and (not morph or morph.tags(t) == 0) and VERB_END.match(t) and not PARTICIPLE_ANY.match(t) and not GEN_VERB.match(t) and t not in NOT_VERB


def adverb_like(t, morph):
    return bool(t) and t not in NOT_ADVERB and t not in NEG and t not in DUAL and not verb_like(t, morph) and not (morph and morph.is_noun(morph.tags(t)))


def gram_pick(prev, prev2, e, in_homo=False, w=None, morph=None, prev3=None, prev4=None, nxt=None, nxt2=None):
    """Зеркало Stress.gramPass: что поставит грамматический проход, None — молчит. morph — aot_morph.Table для согласования
    с прилагательным (w — само слово, prev3/prev4 — для согласования через слово); без неё, как в Kotlin без Morph,
    это правило выключено."""
    prev2 = prev2 or ''
    if 'i' in e: return e['i'] if PHASE.fullmatch(prev) else None
    if prev in POSS_PRON and w in POSS_PL:
        if prev2 in GEN or prev2 in DUAL or prev2 in QUANT_GOV or GEN_VERB.match(prev2) or prev2 in ('под', 'за') and prev3 == 'из': return e['g']
        return None if OBLIQUE_END.match(prev2) or morph and morph.tags(prev2) != 0 else e['p']
    if prev in ('под', 'за') and prev2 == 'из': return e.get('g') or e.get('n')
    if prev == 'за' and prev2 == 'что': return None
    if prev == 'с' and prev2 in SIZE: return e.get('p')
    if prev in GEN: return e.get('g') or e.get('n')
    if prev in QUANT_GOV: return None if prev2 == 'не' and prev in ('столько', 'сколько') else e.get('g')
    if 'l' in e and e['l'] != e.get('g'): return loc2_pick(prev, prev2, prev3, e, w, morph)
    if prev in LOC_PREP and 'l' in e: return e['l']
    if prev in ('в', 'во') and 'g' in e and 'p' not in e: return None
    if prev in PREP: return e.get('p') or e.get('g') or (None if prev in LOC_PREP and in_homo else e.get('n'))
    if prev in PRON: return e.get('v')
    if prev.endswith(('ого', 'его')) or GEN_ADJ.match(prev):
        return None if prev in NOT_ADJ or prev.startswith('сам') and prev2 == 'у' or prev.startswith('котор') or prev.endswith(PARTICIPLE) else e.get('g') or e.get('n')
    if prev == 'все' and w == 'дома': return None
    if w in VERB_PL and 'p' in e and verb_like(prev, morph) and prev2 not in NEG and prev3 not in NEG and nxt not in NEG: return e['p']
    if subj_pl(w, e, prev, nxt, morph, prev2, prev3, nxt2): return e["p"]
    if nxt in QUANT_NEXT and 'g' in e and 'p' in e: return e['g']
    if morph and w and ('g' in e or 'p' in e): return agree(morph, prev, prev2, w, e, prev3, prev4, chain_head(prev, prev2, prev3, prev4))
    return None


def subj_pl(w, e, prev, nxt, morph, prev2=None, prev3=None, nxt2=None):
    verb_next = verb_like(nxt, morph) and VERB_PL_END.match(nxt) or w in SUBJ_ADV and adverb_like(nxt, morph) and verb_like(nxt2, morph) and VERB_PL_END.match(nxt2)
    return (w in VERB_PL and 'p' in e and verb_next and prev not in NEG
            and not {prev, prev2, prev3} & DUAL and not (prev and morph and morph.is_noun(morph.tags(prev))))


def agree(m, prev, prev2, w, e, prev3=None, prev4=None, chain_head=None):
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
        # одушевлённое без вин. мн. в таблице (учителя, врача): после существительного — род. ед. («задача уч+ителя»),
        # им. мн. учителя́ после существительного почти не бывает (СинТагРус: 273 против 11, Silero тут ошибается в 18 %)
        # существительное только в им./вин. мн. («маги учителя», «были мастера» — «были» и есть быль) — молчим
        if 'p' not in e: return e.get('g') if prev != 'были' and m.is_noun(ta) and not m.is_adjective(ta) and m.animate(tw) and (m.noun_cases(ta, False) or m.noun_cases(ta, True) - {'nom', 'acc'}) else None
        if prev in GEN or prev in PREP or not prev2: return None
        far = prev2 in GEN or prev2 in PREP
        cand, head = (prev3, prev4) if far else (prev2, prev3)
        if not cand: return None
        if not far and m.is_noun(ta) and m.noun_cases(ta, True) & {'nom', 'acc'}: return None  # «бревенчатые стены терема»
        tc = m.tags(cand)
        adj_plural = cand != 'все' and NOM_PL.match(cand) and ((PARTICIPLE_PL if far else PASSIVE_PL).match(cand) if tc == 0 else m.is_adjective(tc) and not m.is_noun(tc))
        return None if not adj_plural else e.get('g') if head in COUNT else e['p']
    if chain_head in COUNT: return e.get('g')
    def fit(plural):
        noun = m.noun_cases(tw, plural) & ({'nom', 'acc'} if plural else {'gen'})
        genders = [None] if plural else m.genders(tw) or ['m', 'f', 'n']
        return any(m.adj_cases(ta, g, plural) & noun for g in genders)
    sg, pl = fit(False), fit(True)
    return e.get('g') if sg and not pl else e.get('p') if pl and not sg else None


_extra = None


def extra_pick(w, low):
    """Вариант по фразам системного словаря (phrases_extra.txt) или None. Они заменяют текст до Stress, поэтому старше
    gramPass и словаря ударений (stress_fixes): Stress.userDictPass не трогает слова, пришедшие уже с «+»."""
    global _extra
    # «*» в фразе — маска словаря замен (буквы, в том числе ничего): «*ым потом» — любое слово на -ым
    if _extra is None: _extra = {w: [(re.compile(r'(?<![а-яё-])' + re.escape(p).replace(r'\*', '[а-яё-]*') + r'(?![а-яё-])'), v) for p, v in items] for w, items in load_extra().items()}
    for rx, v in _extra.get(w.replace('ё', 'е'), ()):   # ключи через «е», а модель могла вернуть «л+ёту»
        if rx.search(low): return v
    return None


def app_pick(w, toks, i, low, gram, homo, morph, phrase_pick):
    """Зеркало порядка аппки для слова toks[i]: фразы системного словаря (phrases_extra.txt, замена текста до Stress) →
    gramPass → фразы Silero. None — решает модель. phrase_pick(w, low) — вариант по фразам json или None."""
    ours = extra_pick(w, low)
    if ours: return ours
    nxt, nxt2 = (toks[i + 1] if i + 1 < len(toks) else None), (toks[i + 2] if i + 2 < len(toks) else None)
    if w in gram and i == 0 and subj_pl(w, gram[w], '', nxt, morph, nxt2=nxt2): return gram[w]['p']
    if w in gram and i > 0: ours = gram_pick(toks[i - 1], toks[i - 2] if i > 1 else None, gram[w], w in homo, w, morph,
                                            toks[i - 3] if i > 2 else None, toks[i - 4] if i > 3 else None, nxt, nxt2)
    if w == 'все' and i + 1 < len(toks): ours = vse_pick(toks[i + 1], morph, gram)
    if ours is None: ours = phrase_pick(w, low)
    return ours


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
