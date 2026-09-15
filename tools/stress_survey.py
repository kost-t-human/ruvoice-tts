#!/usr/bin/env python3
"""Сверка ударений Silero (accentor + homosolver из v5_5_ru.pt, те же модели, что в аппке) со
словарями замен из app/src/test/resources/local/*.txt (чужие, в репозиторий не входят).
Берутся только правила «ударение то же слово»: замена отличается от ключа лишь «+». Ключи с
маской, regex, «$» регистра — мимо. Сравнение по словам, где словарь поставил «+».
Выход: app/build/stress_survey.txt — сводка, самые частые слова с промахом, полный список.
Запуск: python3 tools/stress_survey.py [макс_строк]"""
import glob, os, re, sys, time
from collections import Counter, defaultdict
import torch

HERE = os.path.dirname(os.path.abspath(__file__)); ROOT = os.path.dirname(HERE)
LOCAL = os.path.join(ROOT, 'app/src/test/resources/local'); OUT = os.path.join(ROOT, 'app/build/stress_survey.txt')
LIMIT = int(sys.argv[1]) if len(sys.argv) > 1 else 0

pk = torch.package.PackageImporter(os.path.join(HERE, 'v5_5_ru.pt')).load_pickle('tts_models', 'model').packages[0]

def decode(path):
    raw = open(path, 'rb').read()
    try: return raw.decode('utf-8-sig')
    except UnicodeDecodeError: return raw.decode('cp1251')

word_re = re.compile(r'[а-яё+]+', re.I)
cases = {}  # ключ → замена; дубли между словарями схлопываются
for f in sorted(glob.glob(os.path.join(LOCAL, '*.txt'))):
    for line in decode(f).splitlines():
        s = line.strip()
        if not s or s[0] in '#~$' or '=' not in s: continue
        k, v = (x.strip() for x in s.split('=', 1))
        if '*' in k or '+' not in v or v.replace('+', '').lower() != k.lower(): continue
        if not re.fullmatch(r'[а-яё ,.!?\-]+', k, re.I): continue
        cases.setdefault(k.lower(), v.lower())
items = list(cases.items())
if LIMIT: items = items[:LIMIT]

def stressed(word):  # «сл+ова» → («слова», 2): позиция «+» в слове без «+»
    i = word.find('+'); return word.replace('+', ''), i

t0 = time.time(); total = checked = ok = 0
miss = []; by_word = Counter(); by_pair = defaultdict(list)
for n, (key, val) in enumerate(items):
    try: got = pk.accentor(key)
    except Exception as e: print('ошибка', key, e); continue
    gw = [stressed(w) for w in word_re.findall(got)]
    vw = [stressed(w) for w in word_re.findall(val)]
    if [w for w, _ in gw] != [w for w, _ in vw]: continue  # accentor переписал текст, не сравнить
    total += 1; bad = []
    for (w, gi), (_, vi) in zip(gw, vw):
        if vi < 0: continue
        checked += 1
        if gi == vi: ok += 1
        else: bad.append((w, vi, gi))
    if bad:
        miss.append((key, val, got))
        for w, vi, gi in bad:
            by_word[w] += 1
            by_pair[(w[:vi] + '+' + w[vi:], w[:gi] + '+' + w[gi:] if gi >= 0 else w)].append(key)
    if n % 5000 == 0: print(f'{n}/{len(items)} {time.time() - t0:.0f}с', flush=True)

os.makedirs(os.path.dirname(OUT), exist_ok=True)
with open(OUT, 'w', encoding='utf-8') as o:
    o.write(f'фраз: {total}, ударений сверено: {checked}, совпало: {ok} ({100 * ok / max(1, checked):.1f}%), фраз с промахом: {len(miss)}\n\n')
    o.write('== слово: словарь → модель (число фраз) ==\n')
    for (want, got), keys in sorted(by_pair.items(), key=lambda x: -len(x[1]))[:400]:
        o.write(f'{want} → {got}  ({len(keys)})  напр.: {keys[0]}\n')
    o.write('\n== все промахи: ключ | словарь | модель ==\n')
    for key, val, got in miss: o.write(f'{key} | {val} | {got}\n')
print(open(OUT, encoding='utf-8').readline().strip(), '→', OUT)
