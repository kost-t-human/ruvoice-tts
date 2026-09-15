#!/usr/bin/env python3
"""Таблица грамматических омографов для Stress.gramPass → ключ «gram» в silero_ru.json.
Вход: app/build/aot_forms.tsv (tools/aot_forms.py, морфословарь AOT, LGPL).
Берутся формы, где ударение решает падеж или часть речи:
  Сущ./глагол не берётся, если глагольное ударение совпадает с местным падежом по Викисловарю (в чест+и).
  Ключ — форма без «ё», варианты могут быть с «ё» (озера: +озера / оз+ёра).
  g — род. ед. (стен+ы, для одушевлённых это же вин. ед.), p — им./вин. мн. (ст+ены; только если есть вин.,
      у одушевлённых им. мн. после предлога не бывает), n — существительное (сел+а), v — глагол (с+ела).
Варианты с иным различием (м+орщило/морщ+ило — просто два допустимых ударения) не берутся."""
import json, os, collections

HERE = os.path.dirname(os.path.abspath(__file__)); ROOT = os.path.dirname(HERE)
SRC = os.path.join(ROOT, 'app/build/aot_forms.tsv'); JSON = os.path.join(ROOT, 'app/src/main/assets/silero/silero_ru.json')


def locatives():
    """форма → {ударные варианты, помеченные в Викисловаре как locative} (в чест+и, в цвет+у)."""
    out = collections.defaultdict(set)
    path = os.path.join(ROOT, 'app/build/wikt_forms.tsv')
    if not os.path.exists(path): return out
    for line in open(path, encoding='utf-8'):
        p = line.rstrip('\n').split('\t')
        for v in p[1:]:
            st, gr = v.split(' ', 1)
            if 'locative' in gr: out[p[0]].add(st)
    return out


def build():
    out = {}; stat = collections.Counter(); loc = locatives()
    # формы с «ё» кладём под ключ без «ё»: в тексте без «ё» «озера» — это и о́зера (род. ед.), и озёра (мн.)
    by_key = collections.defaultdict(list)
    for line in open(SRC, encoding='utf-8'):
        p = line.rstrip('\n').split('\t'); w = p[0]
        if len(p) < 2 or not w.isalpha(): continue
        by_key[w.replace('ё', 'е')].extend(p[1:])
    for w, variants in by_key.items():
        if len(variants) < 2: continue
        tags = {}
        for v in variants:
            st, gr = v.split(' ', 1); t = set()
            for a in gr.split('|'):
                lem, pos, g = a.split(':', 2); gs = set(g.split(','))
                if pos == 'N':
                    t.add('n')
                    if 'sg' in gs and 'gen' in gs: t.add('g')
                    if 'pl' in gs and 'nom' in gs: t.add('p')
                    if 'pl' in gs and 'acc' in gs: t.add('pa')
                elif pos == 'V': t.add('v')
                else: t.add('x')
            tags[st] = t
        G = [s for s, t in tags.items() if 'g' in t and 'p' not in t]
        P = [s for s, t in tags.items() if 'p' in t and 'g' not in t]
        N = [s for s, t in tags.items() if 'n' in t and 'v' not in t and 'ё' not in s]  # сущ./глагол — только в написании без «ё»
        V = [s for s, t in tags.items() if 'v' in t and 'n' not in t and 'x' not in t and 'ё' not in s]
        e = {}
        if len(G) == 1 and len(P) == 1:
            e['g'] = G[0]
            if 'pa' in tags[P[0]]: e['p'] = P[0]
            stat['род.ед./мн.'] += 1
        if len(N) == 1 and len(V) == 1 and V[0] not in loc.get(w, ()): e['n'] = N[0]; e['v'] = V[0]; stat['сущ./глагол'] += 1
        if e:
            out[w] = e
            if any('ё' in x for x in e.values()): stat['с ё'] += 1
    return out, stat


if __name__ == '__main__':
    gram, stat = build()
    with open(JSON, encoding='utf-8') as f: d = json.load(f)
    d['gram'] = gram
    with open(JSON, 'w', encoding='utf-8') as f: json.dump(d, f, ensure_ascii=False)
    print(dict(stat), 'всего', len(gram), '→', JSON)
