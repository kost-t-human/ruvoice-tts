#!/usr/bin/env python3
"""Таблица морфологии из словаря AOT (LGPL, по Зализняку) → app/src/main/assets/morph.bin.

Вход: data/Russian/morphs.json и gramtab.json из github.com/sokirko74/morph_dict (каталог — аргумент,
по умолчанию scratchpad aot/). Берём существительные (N), прилагательные (A), порядковые (ORD_NUM) и
местоимения-прилагательные (PA); имена, фамилии, отчества, топонимы, организации, аббревиатуры,
опечатки и формы с дефисом пропускаем. Читает text/Morph.kt, формат записи и биты — там же.

Запись 12 байт, little-endian, отсортированы по хэшу (как signed int64):
  8 байт — FNV-1a 64 от UTF-8 формы строчными, «ё»→«е»;
  4 байта — теги: биты 0–3 часть речи (1 N, 2 A, 4 ORD_NUM, 8 PA), 4–6 род леммы (16 м, 32 ж, 64 ср),
  7 — одушевлённое, 8–13 ед. ч. nom gen dat acc ins loc, 14–19 мн. ч., 20–25 ед. ж. р. и 26–31 ед. ср. р.
  (у прилагательных; ед. м. р. — в 8–13). Винительный у прилагательных — только неодушевлённый
  (совпадает с именительным); одушевлённый совпадает с родительным, его выводит вызывающий код.
Объединение разборов кладётся по клеткам: «службы» = {gen.sg, nom.pl, acc.pl}.
"""
import json, os, struct, sys, collections

SRC = sys.argv[1] if len(sys.argv) > 1 else '/tmp/claude-1000/-home-kost-serverApp-ruvoice/748723da-ad5b-4ba1-ac6d-6d37fdd0e81b/scratchpad/aot'
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'app', 'src', 'main', 'assets', 'morph.bin')
POS = {'N': 1, 'A': 2, 'ORD_NUM': 4, 'PA': 8}
GENDER = {'mas': 16, 'fem': 32, 'neu': 64}
CASES = ['nom', 'gen', 'dat', 'acc', 'ins', 'prp']
SKIP = {'name', 'surname', 'patr', 'loc', 'org', 'abbr', 'misspell'}
MASK_CELLS = 0xFFFFFF00
MASK_NOUN = 0x000FFF00


def fnv1a(s):
    h = 0xcbf29ce484222325
    for b in s.encode('utf-8'):
        h = ((h ^ b) * 0x100000001b3) & 0xFFFFFFFFFFFFFFFF
    return h


class Table:
    """Чтение готового morph.bin (mmap, бинарный поиск по хэшу) — зеркало text/Morph.kt для tools/phrases_extra.py."""

    def __init__(self, path=OUT):
        import mmap
        self.f = open(path, 'rb'); self.buf = mmap.mmap(self.f.fileno(), 0, access=mmap.ACCESS_READ); self.n = len(self.buf) // 12

    def tags(self, form):
        h = fnv1a(form.lower().replace('ё', 'е')); h = h - (1 << 64) if h >> 63 else h
        lo, hi = 0, self.n - 1
        while lo <= hi:
            mid = (lo + hi) // 2; v, t = struct.unpack_from('<qI', self.buf, mid * 12)
            if v < h: lo = mid + 1
            elif v > h: hi = mid - 1
            else: return t
        return 0

    @staticmethod
    def is_noun(t): return t & 1 != 0
    @staticmethod
    def is_adjective(t): return t & 14 != 0
    @staticmethod
    def genders(t): return [g for g, b in (('m', 16), ('f', 32), ('n', 64)) if t & b]
    @staticmethod
    def cases(t, shift): return {c for i, c in enumerate(CASES) if t >> (shift + i) & 1}
    @classmethod
    def noun_cases(cls, t, plural): return cls.cases(t, 14 if plural else 8)
    @classmethod
    def adj_cases(cls, t, gender, plural): return cls.cases(t, 14 if plural else {'f': 20, 'n': 26}.get(gender, 8))
    @staticmethod
    def plural_only(t):
        """Форма только мн. ч. и есть им./вин. — согласуется с «все»; «новых» (род. мн.) — нет: «всё новых и новых»."""
        return t & 0x24000 != 0 and t & 0xFFF03F00 == 0


def cells(pos, gr):
    """Биты сетки число×падеж для одного разбора; 0 — разбор без падежа (сравнительная, звательный)."""
    cases = [i for i, c in enumerate(CASES) if c in gr] or (list(range(6)) if '0' in gr else [])
    if not cases: return 0
    if pos == 'N':
        rows = [r for r, n in ((8, 'sg'), (14, 'pl')) if n in gr] or [8, 14]
    elif 'pl' in gr:
        rows = [14]
    else:
        rows = [r for r, g in ((8, 'mas'), (20, 'fem'), (26, 'neu')) if g in gr]
    bits = 0
    for r in rows:
        for c in cases: bits |= 1 << (r + c)
    return bits


def main():
    d = json.load(open(os.path.join(SRC, 'morphs.json'), encoding='utf-8'))
    gt = json.load(open(os.path.join(SRC, 'gramtab.json'), encoding='utf-8'))
    g = gt['gramcodes']
    service = {gt.get('plug_noun_gram_code'), gt.get('mas_abbr_noun')}
    fm = d['flexia_models']
    table = collections.defaultdict(int)  # форма -> теги
    for l in d['lemmas']:
        f = fm[l['f']]
        pos = g[f['endings'][0]['gramcode']]['p']
        if pos not in POS: continue
        common = g.get(l.get('t', ''), {}).get('g', [])
        e0 = f['endings'][0]['flexia']
        lemma = l['l']
        stem = lemma[:len(lemma) - len(e0)] if e0 and lemma.endswith(e0) else lemma
        if '-' in stem or SKIP & set(common): continue
        for e in f['endings']:
            if e['gramcode'] in service: continue
            gc = g[e['gramcode']]
            gr = set(common + gc['g'])
            if SKIP & gr or '-' in e['flexia']: continue
            # у прилагательных одушевлённый винительный (= родительному) не кладём
            if pos != 'N' and 'anim' in gr and 'inanim' not in gr: continue
            c = cells(pos, gr)
            if not c: continue
            t = POS[pos] | c
            if pos == 'N':
                t |= sum(v for k, v in GENDER.items() if k in gr) | (128 if 'anim' in gr else 0)
            form = (e.get('prefix', '') + stem + e['flexia']).lower().replace('ё', 'е')
            table[form] |= t
    rows = {}
    for form, t in table.items():
        h = fnv1a(form)
        if h in rows and rows[h] != t: print(f'коллизия хэша: {form}', file=sys.stderr)
        rows[h] = rows.get(h, 0) | t
    signed = sorted((h - (1 << 64) if h >> 63 else h, t) for h, t in rows.items())
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, 'wb') as out:
        for h, t in signed: out.write(struct.pack('<qI', h, t))
    nouns = [t for t in table.values() if t & 1]
    multi = sum(1 for t in nouns if bin(t & MASK_NOUN).count('1') > 1)
    print(f'форм: {len(rows)}, байт: {len(rows) * 12}, существительных: {len(nouns)}, '
          f'из них многозначных по числу-падежу: {multi} → {OUT}')


if __name__ == '__main__':
    main()
