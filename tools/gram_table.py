#!/usr/bin/env python3
"""Таблица грамматических омографов для Stress.gramPass → ключ «gram» в silero_ru.json.
Вход: app/build/aot_forms.tsv (tools/aot_forms.py, морфословарь AOT, LGPL).
Берутся формы, где ударение решает падеж или часть речи:
  Сущ./глагол не берётся, если глагольное ударение по Викисловарю — тоже форма существительного (в чест+и, к утр+у).
  Ключ — форма без «ё», варианты могут быть с «ё» (озера: +озера / оз+ёра).
  g — род. ед. (стен+ы, для одушевлённых это же вин. ед.), p — им./вин. мн. (ст+ены; только если есть вин.,
      у одушевлённых им. мн. после предлога не бывает), l — второй предложный, совпадающий с род. ед. (в глуш+и),
      n — существительное (сел+а), v — глагол (с+ела),
  i — инфинитив несов. вида (обполз+ать; сов. обп+олзать — после «начал», «стал», «буду» не бывает),
  второй предложный с иным ударением (в кров+и, в тен+и, в печ+и): l — он, g — все остальные формы (кр+ови: род. ед.,
      им. мн.); AOT помечает его «prp,2», Викисловарь — locative; не берутся слова, где после «в/на» чаще вин. мн.
      (в бр+ови, в к+ости, в с+ени, в щ+ели) и «связи» (на св+язи / в связ+и спорно). Без «в/на» такое слово —
      всегда g (ана́лиз кр+ови), кроме совпадающих с глаголом (бреду́): у них только l.
Варианты с иным различием (м+орщило/морщ+ило — просто два допустимых ударения) не берутся."""
import json, os, collections

HERE = os.path.dirname(os.path.abspath(__file__)); ROOT = os.path.dirname(HERE)
SRC = os.path.join(ROOT, 'app/build/aot_forms.tsv'); JSON = os.path.join(ROOT, 'app/src/main/assets/silero/silero_ru.json')


NO_LOC2 = set('брови кости сени щели связи дали пещи нощи пару'.split())   # пару: на пар+у / на п+ару минут


def wikt_loc2():
    """форма → (вариант всех прочих форм, вариант второго предложного) по Викисловарю: п+ечи / печ+и."""
    out = {}
    path = os.path.join(ROOT, 'app/build/wikt_forms.tsv')
    if not os.path.exists(path): return out
    for line in open(path, encoding='utf-8'):
        p = line.rstrip('\n').split('\t')
        if len(p) < 3: continue
        L = []; G = []
        for v in p[1:]:
            st, gr = v.split(' ', 1); cells = [a.split(':', 2) for a in gr.split('|')]
            n = [c[2] for c in cells if c[1] == 'noun']
            if not n: continue
            if all('locative' in c for c in n): L.append(st)
            elif any('genitive' in c and 'singular' in c for c in n) and not any('locative' in c for c in n): G.append(st)
        if len(L) == 1 and len(G) == 1: out[p[0]] = (G[0], L[0])
    return out


def wikt_nouns():
    """форма → {ударные варианты, которые Викисловарь знает и как существительное}: второй предложный
    (в чест+и, в цвет+у) и второй дательный (к утр+у), которых нет в AOT."""
    out = collections.defaultdict(set)
    path = os.path.join(ROOT, 'app/build/wikt_forms.tsv')
    if not os.path.exists(path): return out
    for line in open(path, encoding='utf-8'):
        p = line.rstrip('\n').split('\t')
        for v in p[1:]:
            st, gr = v.split(' ', 1)
            if ':noun:' in gr: out[p[0]].add(st)
    return out


def build():
    out = {}; stat = collections.Counter(); wn = wikt_nouns(); loc2 = wikt_loc2()
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
            st, gr = v.split(' ', 1); t = set(); ncells = l2cells = 0
            for a in gr.split('|'):
                lem, pos, g = a.split(':', 2); gs = set(g.split(','))
                if pos == 'N':
                    t.add('n'); ncells += 1
                    if 'sg' in gs and 'gen' in gs: t.add('g')
                    if 'sg' in gs and 'prp' in gs: t.add('l')
                    if 'prp' in gs and '2' in gs: l2cells += 1
                    if 'pl' in gs and 'nom' in gs: t.add('p')
                    if 'pl' in gs and 'acc' in gs: t.add('pa')
                elif pos == 'V':
                    t.add('v')
                    if 'imp' not in gs: t.add('vfin')   # не повелительное: «я бреду́» частое, «тени́!» нет
                elif pos == 'INFINITIVE': t.add('x'); t.add('perf' if 'perf' in gs else 'imperf')
                else: t.add('x')
            if ncells and ncells == l2cells: t.add('l2')   # только второй предложный (кров+и), не «жар+у» = жара́ вин.
            tags[st] = t
        G = [s for s, t in tags.items() if 'g' in t and 'p' not in t]
        P = [s for s, t in tags.items() if 'p' in t and 'g' not in t]
        N = [s for s, t in tags.items() if 'n' in t and 'v' not in t and 'ё' not in s]  # сущ./глагол — только в написании без «ё»
        V = [s for s, t in tags.items() if 'v' in t and 'n' not in t and 'x' not in t and 'ё' not in s]
        e = {}
        if len(G) == 1 and len(P) == 1:
            e['g'] = G[0]
            # если род. ед. совпадает с предл. ед. (глуш+и), после «в/на» это второй предложный, а не вин. мн.
            if 'l' in tags[G[0]]: e['l'] = G[0]
            elif 'pa' in tags[P[0]]: e['p'] = P[0]
            stat['род.ед./мн.'] += 1
        if len(N) == 1 and len(V) == 1 and V[0] not in wn.get(w, ()): e['n'] = N[0]; e['v'] = V[0]; stat['сущ./глагол'] += 1
        I = [s for s, t in tags.items() if 'imperf' in t and 'perf' not in t]
        F = [s for s, t in tags.items() if 'perf' in t and 'imperf' not in t]
        if len(I) == 1 and len(F) == 1: e['i'] = I[0]; stat['вид инфинитива'] += 1
        if not e and w not in NO_LOC2:
            L2 = [s for s, t in tags.items() if 'l2' in t]
            G2 = [s for s, t in tags.items() if 'g' in t and 'l2' not in t]
            if len(L2) == 1 and len(G2) == 1: e['g'] = G2[0]; e['l'] = L2[0]; stat['второй предложный'] += 1
            elif w in loc2: e['g'], e['l'] = loc2[w]; stat['второй предложный'] += 1
            # совпадает с глаголом (бред+у, я бреду́): без «в/на» аппка молчит, g не даём
            if 'l' in e and any('vfin' in t or 'x' in t for t in tags.values()): del e['g']
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
