#!/usr/bin/env python3
"""Имена и термины с ударениями из русской Википедии: в первом абзаце статьи заголовок выделен жирным
и с ударением («'''Гельмго́льц'''»). Модель на именах ошибается примерно в трети случаев, словари AOT и
Викисловаря их не знают. Берём формы с одним знаком ударения (U+0301) или «ё», к ним приписываем
косвенные падежи (ударение на месте, tools/wiki_names.py:forms), оставляем только те, что встречаются в
тексте Википедии не реже порога, и только там, где акцентор ставит ударение иначе. Слова, которые есть в AOT
как нарицательные, в исключениях, омографах и грамматической таблице модели, не берутся (в списке ударений
ключ без регистра, «Роман» и «роман» одно и то же).

  python3 tools/wiki_names.py extract <dump.xml.bz2>...   — tmp/wiki/leads.tsv: форма, вариант, сколько статей
  python3 tools/wiki_names.py count <dump.xml.bz2>...     — tmp/wiki/counts.tsv: сколько раз форма (и её падежи) в тексте
  python3 tools/wiki_names.py build [--min N] [--min-form M] — tools/wiki_names.txt (формат tools/stress_fixes.txt); по умолчанию 50 и 5
"""
import bz2, collections, json, multiprocessing, os, re, sys
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__)); ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)
TMP = os.path.join(ROOT, 'tmp', 'wiki'); OUT = os.path.join(HERE, 'wiki_names.txt')
NS = '{http://www.mediawiki.org/xml/export-0.11/}'
ACUTE = '́'; V = 'аоуыэиеяёю'
re_bold = re.compile(r"'''(.{1,200}?)'''", re.S)
re_word = re.compile(r'[А-ЯЁа-яё́]+')
re_tok = re.compile(r'[А-ЯЁа-яё]+')


def pages(path):
    with bz2.open(path, 'rb') as f:
        for ev, el in ET.iterparse(f):
            if el.tag != NS + 'page': continue
            ns = el.find(NS + 'ns').text
            rev = el.find(NS + 'revision'); txt = rev.find(NS + 'text').text if rev is not None else None
            if ns == '0' and txt and not txt.startswith('#'): yield txt
            el.clear()


def plus(w):
    """«Гельмго́льц» → «Гельмг+ольц»; без знака — ударение на «ё»; None, если ударений не одно."""
    if w.count(ACUTE) == 1:
        i = w.index(ACUTE); w = w.replace(ACUTE, '')
        return w[:i - 1] + '+' + w[i - 1:] if i > 0 and w[i - 1].lower() in V else None
    if ACUTE not in w and w.count('ё') == 1 and sum(c in V for c in w.lower()) > 1:
        i = w.index('ё'); return w[:i] + '+' + w[i:]
    return None


def extract_one(path):
    c = collections.Counter()
    for txt in pages(path):
        seen = set()
        for m in re_bold.finditer(txt[:6000]):
            for w in re_word.findall(m.group(1)):  # NFD нельзя: «й» распадётся на «и» + бреве
                if sum(c in V for c in w.lower()) < 2: continue
                if 'й' in w.replace('и' + '̆', 'й'): pass
                v = plus(w)
                if v: seen.add((v.replace('+', ''), v))
        c.update(seen)
    print(path, len(c), flush=True)
    return c


def forms(w):
    """Косвенные формы с ударением на том же месте: Гельмг+ольц → Гельмг+ольца…; какие из них реальны, решит корпус."""
    k = w.index('+'); s = w.replace('+', ''); low = s.lower()
    vow = [i for i, c in enumerate(low) if c in V]
    if len(vow) < 2: return []
    if low.endswith(('ов', 'ев', 'ёв', 'ин', 'ын')): stem, ends = s, ['а', 'у', 'ым', 'ом', 'е', 'ой', 'ы', 'ых', 'ыми']
    elif low.endswith(('ий', 'ый', 'ой')) and len(s) > 4: stem, ends = s[:-2], ['ого', 'ому', 'им', 'ым', 'ом', 'ем', 'ая', 'ой', 'ую', 'ие', 'ые', 'их', 'ых', 'ими', 'ыми', 'ия', 'ию', 'ием', 'ии']
    elif low.endswith('й'): stem, ends = s[:-1], ['я', 'ю', 'ем', 'е', 'и', 'ев', 'ям', 'ями', 'ях']
    elif low.endswith('а'): stem, ends = s[:-1], ['ы', 'и', 'е', 'у', 'ой']
    elif low.endswith('я'): stem, ends = s[:-1], ['и', 'е', 'ю', 'ей']
    elif low.endswith('ь'): stem, ends = s[:-1], ['я', 'ю', 'ем', 'е', 'и', 'ью']
    elif low[-1] in V or low.endswith(('ец', 'ок', 'ёк', 'ел', 'ень', 'ич')): return []  # несклоняемые, беглая гласная, отчества (Ильич+а)
    else: stem, ends = s, ['а', 'у', 'ом', 'е', 'ы', 'и', 'ов', 'ам', 'ами', 'ах']
    out = []
    for e in ends:
        f = stem + e
        j = k if k < len(stem) else len(stem) + next(i for i, c in enumerate(e) if c in V)
        out.append(f[:j] + '+' + f[j:])
    return out


CAND = set()  # глобально до Pool: наследуется форком, не пикуется на каждый вызов


def count_one(path):
    c = collections.Counter()
    for txt in pages(path):
        for t in re_tok.findall(txt):
            if t in CAND: c[t] += 1
    print(path, flush=True)
    return c


def leads():
    out = collections.defaultdict(collections.Counter)
    for line in open(os.path.join(TMP, 'leads.tsv'), encoding='utf-8'):
        w, v, n = line.rstrip('\n').split('\t'); out[w][v] += int(n)
    return out


def main():
    cmd, args = sys.argv[1], [a for a in sys.argv[2:] if not a.startswith('--')]
    os.makedirs(TMP, exist_ok=True)
    if cmd == 'extract':
        total = collections.Counter()
        with multiprocessing.Pool(6) as p:
            for c in p.imap_unordered(extract_one, args): total.update(c)
        with open(os.path.join(TMP, 'leads.tsv'), 'w', encoding='utf-8') as o:
            for (w, v), n in sorted(total.items(), key=lambda x: -x[1]): o.write(f'{w}\t{v}\t{n}\n')
        print('форм:', len(total))
    elif cmd == 'count':
        for w, vs in leads().items():
            CAND.add(w)
            for v in vs:
                for f in forms(v): CAND.add(f.replace('+', ''))
        print('кандидатов с падежами:', len(CAND), flush=True)
        total = collections.Counter()
        with multiprocessing.Pool(6) as p:
            for c in p.imap_unordered(count_one, args): total.update(c)
        with open(os.path.join(TMP, 'counts.tsv'), 'w', encoding='utf-8') as o:
            for w, n in total.most_common(): o.write(f'{w}\t{n}\n')
    elif cmd == 'build':
        build(int(sys.argv[sys.argv.index('--min') + 1]) if '--min' in sys.argv else 50,
              int(sys.argv[sys.argv.index('--min-form') + 1]) if '--min-form' in sys.argv else 5)


def build(min_n, min_form):
    import stress_fixes
    from torch.jit.mobile import _load_for_lite_interpreter
    acc = _load_for_lite_interpreter(os.path.join(ROOT, 'app/src/main/assets/silero/accentor.ptl'))
    d = json.load(open(os.path.join(ROOT, 'app/src/main/assets/silero/silero_ru.json'), encoding='utf-8'))
    known = set(d['exceptions']) | set(d['homodict']) | set(d.get('gram', {})) | set(stress_fixes.load_fixes())
    common = set()  # формы AOT, у которых есть разбор не-имени
    for line in open(os.path.join(ROOT, 'app/build/aot_forms.tsv'), encoding='utf-8'):
        f, *vs = line.rstrip('\n').split('\t')
        if any(not set(a.split(':')[-1].split(',')) & {'name', 'surname', 'patr', 'loc', 'org'} for t in vs for a in t.split('|')): common.add(f)
    arbiter = collections.defaultdict(set)  # форма → ударения по AOT и Викисловарю (с именами): расходятся с нашим — не берём («Руси» от «Руса» против Рус+и)
    for name in ('aot_forms.tsv', 'wikt_forms.tsv'):
        for line in open(os.path.join(ROOT, 'app/build', name), encoding='utf-8'):
            f, *vs = line.rstrip('\n').split('\t'); arbiter[f].update(t.split(' ', 1)[0] for t in vs)
    counts = {}
    for line in open(os.path.join(TMP, 'counts.tsv'), encoding='utf-8'):
        w, n = line.rstrip('\n').split('\t'); counts[w] = int(n)

    def model(words):
        out = {}
        for i in range(0, len(words), 256):
            batch = words[i:i + 256]; st, y = acc(batch)
            for w, row in zip(batch, st):
                vow = [j for j, c in enumerate(w) if c in V]; k = int(row.argmax())
                out[w] = w[:vow[k]] + '+' + w[vow[k]:] if k < len(vow) else w
        return out

    cand, stat = [], collections.Counter()
    L = leads(); heads = {w.lower() for w in L}
    for w, vs in L.items():
        n = counts.get(w, 0)
        if n < min_n: stat['редкое'] += 1; continue
        (v, k), tot = vs.most_common(1)[0], sum(vs.values())
        if k < tot * 0.75: stat['разные ударения'] += 1; continue
        for f, need in [(v, min_n)] + [(f, min_form) for f in forms(v) if f.replace('+', '').lower() not in heads]:  # «Мали» от «Мала» против статьи «Мали́»
            plain = f.replace('+', ''); low = plain.lower()
            if counts.get(plain, 0) < need: continue
            if low in common or low in known or low.replace('ё', 'е') in common or low.replace('ё', 'е') in known: stat['нарицательное/известно'] += 1; continue  # ключ и через «е»: «тёмно» задел бы «темно»
            cand.append((counts[plain], low, f.lower(), w))
    by_low = collections.defaultdict(list)
    for r in cand: by_low[r[1]].append(r)
    pred = model(sorted(l.replace('ё', 'е') for l in by_low))
    rows = []
    for low, rs in by_low.items():
        n, _, f, w = rs[0]
        if any(r[2] != f for r in rs): stat['разные ударения'] += 1; continue
        if low in arbiter and f not in arbiter[low]: stat['против AOT/Викисловаря'] += 1; continue
        m = pred[low.replace('ё', 'е')]
        if m == f.replace('ё', 'е'): stat['модель права'] += 1; continue
        rows.append((n, low, f, m, w))
    rows.sort(key=lambda r: -r[0])
    with open(OUT, 'w', encoding='utf-8') as o:
        o.write('# Имена и термины из первых абзацев Википедии: модель ставит иначе (tools/wiki_names.py build)\n# слово = ударение  # раз в тексте Википедии, модель, статья\n')
        for n, low, f, m, w in rows: o.write(f'{low} = {f}  # {n}, модель {m}{"" if w.lower() == low else ", " + w}\n')
    print(dict(stat), 'взято:', len(rows), '→', OUT)


if __name__ == '__main__':
    main()
