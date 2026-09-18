#!/usr/bin/env python3
"""Тест омографов из СинТагРуса (UD_Russian-SynTagRus, CC BY-NC-SA, github.com/UniversalDependencies/UD_Russian-SynTagRus):
морфология там снята руками, и для слов из homodict/gram лемма плюс граммемы (падеж, число, время, лицо…) однозначно
выбирают ударный вариант через таблицы форм AOT и Викисловаря (app/build/aot_forms.tsv, wikt_forms.tsv —
tools/aot_forms.py, tools/wikt_forms.py). Берутся только слова, у которых ровно один вариант согласуется с разбором,
и только предложения, где слово встречается один раз. Выход — JSON в формате HomographResolutionEval
(id, context, homograph с U+0301), «ё» в контексте заменена на «е» как в тексте пользователя; его ест tools/homo_eval.py.
Запуск: python3 tools/syntagrus_eval.py <папка с *.conllu> [выход.json, по умолчанию ../syntagrus_eval.json] [--cap N]"""
import collections, glob, json, os, random, re, sys

HERE = os.path.dirname(os.path.abspath(__file__)); ROOT = os.path.dirname(HERE); BUILD = os.path.join(ROOT, 'app/build')
CASE = {'Nom': 'nom', 'Gen': 'gen', 'Dat': 'dat', 'Acc': 'acc', 'Ins': 'ins', 'Loc': 'prp', 'Par': 'gen', 'Voc': 'nom'}
NUM = {'Sing': 'sg', 'Plur': 'pl'}
TENSE = {'Past': 'past', 'Pres': 'pres', 'Fut': 'fut'}
PERSON = {'1': '1p', '2': '2p', '3': '3p'}
GENDER = {'Masc': 'mas', 'Fem': 'fem', 'Neut': 'neu'}
# теги Викисловаря → теги AOT (общий словарь признаков)
WIKT = {'nominative': 'nom', 'genitive': 'gen', 'dative': 'dat', 'accusative': 'acc', 'instrumental': 'ins', 'prepositional': 'prp',
        'locative': 'prp', 'partitive': 'gen', 'vocative': 'nom', 'singular': 'sg', 'plural': 'pl', 'past': 'past', 'present': 'pres',
        'future': 'fut', 'first-person': '1p', 'second-person': '2p', 'third-person': '3p', 'masculine': 'mas', 'feminine': 'fem',
        'neuter': 'neu', 'imperative': 'imp', 'infinitive': 'inf'}
# часть речи: AOT/Викисловарь → класс, UD → класс; сравниваются только когда обе стороны знают класс
POS_AOT = {'N': 'n', 'A': 'a', 'ADJ_SHORT': 'a', 'V': 'v', 'INFINITIVE': 'v', 'PARTICIPLE': 'v', 'PARTICIPLE_SHORT': 'v',
           'ADV_PARTICIPLE': 'v', 'ADV': 'adv', 'PRED': 'pred', 'NUM': 'num', 'ORD_NUM': 'a', 'PRON': 'pron', 'PA': 'pron'}
POS_WIKT = {'noun': 'n', 'name': 'n', 'adj': 'a', 'verb': 'v', 'adv': 'adv', 'num': 'num', 'pron': 'pron', 'det': 'pron'}
POS_UD = {'NOUN': 'n', 'PROPN': 'n', 'ADJ': 'a', 'VERB': 'v', 'AUX': 'v', 'ADV': 'adv', 'NUM': 'num', 'PRON': 'pron', 'DET': 'pron'}
FEAT_KEYS = {'nom', 'gen', 'dat', 'acc', 'ins', 'prp', 'sg', 'pl', 'past', 'pres', 'fut', '1p', '2p', '3p', 'mas', 'fem', 'neu', 'imp', 'inf'}
ACUTE = '́'


def yo(s): return s.replace('ё', 'е')


def load_forms(path, wikt):
    """форма → вариант → [(лемма, класс части речи, {признаки})]"""
    d = collections.defaultdict(lambda: collections.defaultdict(list))
    for line in open(path, encoding='utf-8'):
        p = line.rstrip('\n').split('\t')
        for v in p[1:]:
            var, _, rest = v.partition(' ')
            for an in rest.split('|'):
                if not an: continue
                lemma, pos, tags = (an.split(':', 2) + [''])[:3] if an.count(':') >= 2 else (an, '', '')
                if wikt:
                    feats = {WIKT[t] for t in tags.split(',') if t in WIKT}; cls = POS_WIKT.get(pos)
                    if 'adverb' in tags.split(','): cls = 'adv'; lemma = p[0]   # «малый:adj:adverb» — наречие «мало», лемма в UD «мало»
                    if feats & {'mas', 'fem', 'neu'} and not feats & {'sg', 'pl'}: feats.add('sg')   # род без числа — ед. ч. («весь:det:neuter»)
                else:
                    if ',2' in tags or tags.endswith(',2'): continue   # второй родительный/предложный («на ход+у») у AOT под ударением основы
                    feats = {t for t in tags.split(',') if t in FEAT_KEYS}; cls = POS_AOT.get(pos)
                    if pos == 'INFINITIVE': feats.add('inf')
                    if 'pl' in feats: feats -= {'mas', 'fem', 'neu'}   # AOT пишет род и во мн. ч. («стена:N:fem,pl,acc»)
                d[p[0]][var].append((lemma.lower(), cls, feats))
    return d


def ud_feats(upos, feats):
    f = dict(x.split('=', 1) for x in feats.split('|')) if feats != '_' else {}
    out = set()
    if 'Case' in f: out.add(CASE.get(f['Case'], ''))
    if 'Number' in f: out.add(NUM[f['Number']])
    if 'Tense' in f: out.add(TENSE[f['Tense']])
    if 'Person' in f: out.add(PERSON[f['Person']])
    if 'Gender' in f: out.add(GENDER[f['Gender']])
    if f.get('Mood') == 'Imp': out.add('imp')
    if f.get('VerbForm') == 'Inf': out.add('inf')
    out.discard('')
    return POS_UD.get(upos), out


def compatible(an, cls, feats):
    lemma, acls, afeats = an
    if cls and acls and cls != acls: return False
    # у разбора признак есть и у токена есть признак той же группы — должны совпасть
    for group in (('nom', 'gen', 'dat', 'acc', 'ins', 'prp'), ('sg', 'pl'), ('past', 'pres', 'fut'), ('1p', '2p', '3p'), ('mas', 'fem', 'neu')):
        a = afeats & set(group); t = feats & set(group)
        if a and t and not (a & t): return False
    for flag in ('imp', 'inf'):
        if (flag in afeats) != (flag in feats) and (cls == 'v' or acls == 'v'): return False
    return True


def sentences(path):
    text, toks = None, []
    for line in open(path, encoding='utf-8'):
        line = line.rstrip('\n')
        if line.startswith('# text = '): text = line[9:]
        elif not line:
            if text is not None and toks: yield text, toks
            text, toks = None, []
        elif line[0] != '#':
            p = line.split('\t')
            if '-' in p[0] or '.' in p[0]: continue
            toks.append((p[1], p[2], p[3], p[5]))


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    cap = int(sys.argv[sys.argv.index('--cap') + 1]) if '--cap' in sys.argv else 0
    src = args[0]; out_path = args[1] if len(args) > 1 else os.path.join(os.path.dirname(ROOT), 'syntagrus_eval.json')
    d = json.load(open(os.path.join(ROOT, 'app/src/main/assets/silero/silero_ru.json'), encoding='utf-8'))
    variants = {w: list(v) for w, v in d['homodict'].items()}
    for w, g in d['gram'].items():
        if w not in variants: variants[w] = sorted(set(g.values()))
    aot = load_forms(os.path.join(BUILD, 'aot_forms.tsv'), False); wikt = load_forms(os.path.join(BUILD, 'wikt_forms.tsv'), True)
    word_re = re.compile(r'[а-яё]+', re.I)
    items = []; stat = collections.Counter(); by_word = collections.defaultdict(list)
    for path in sorted(glob.glob(os.path.join(src, '*.conllu'))):
        for text, toks in sentences(path):
            words = [w.lower() for w in word_re.findall(text)]
            counts = collections.Counter(yo(w) for w in words)
            for form, lemma, upos, feats in toks:
                w = form.lower(); key = yo(w)
                if key not in variants or counts[key] != 1: continue
                vs = variants[key]; stat['вхождений'] += 1
                cls, tf = ud_feats(upos, feats); lm = lemma.lower(); yo_pair = any('ё' in v for v in vs)
                if 'ё' in w: match = [v for v in vs if v.replace('+', '') == w]
                elif yo_pair and 'ё' in lm: match = [v for v in vs if 'ё' in v]
                elif yo_pair and cls is None: stat['ё-пара, служебное'] += 1; continue   # у частиц/союзов лемма без «ё» («все же» → лемма «все»)
                else:
                    # «ё» в корпусе почти не пишут (еще 2254, ещё 63), поэтому для ё-пар разбор берётся по ключу самого варианта
                    # и только из Викисловаря (AOT для «все» даёт лемму «все» в ср. р. ед.), лемма сравнивается как есть
                    match = []
                    for v in vs:
                        vk = v.replace("+", "")
                        ans = wikt.get(vk, {}).get(v, []) + ([] if yo_pair else aot.get(vk, {}).get(v, []))
                        if any((a[0] == lm if yo_pair else yo(a[0]) == yo(lm)) and compatible(a, cls, tf) for a in ans): match.append(v)
                if len(match) != 1: stat['неоднозначно' if match else 'нет разбора'] += 1; continue
                v = match[0]; i = v.index('+')
                by_word[key].append({'context': yo(text), 'homograph': v[:i] + v[i + 1] + ACUTE + v[i + 2:], 'lemma': lemma, 'feats': feats})
    random.seed(1); n = 0
    for w in sorted(by_word):
        lst = by_word[w]
        if cap and len(lst) > cap: lst = random.sample(lst, cap)
        for it in lst: n += 1; it['id'] = 20000 + n; items.append(it)
    json.dump(items, open(out_path, 'w', encoding='utf-8'), ensure_ascii=False, indent=0)
    print(f"{dict(stat)}; в тест {len(items)} предложений, {len(by_word)} слов → {out_path}")
    for w, lst in sorted(by_word.items(), key=lambda x: -len(x[1]))[:30]:
        print(f"  {w} {len(lst)} {collections.Counter(it['homograph'] for it in lst).most_common(3)}")


if __name__ == '__main__': main()
