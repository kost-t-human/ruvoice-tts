#!/usr/bin/env python3
"""Процент слов с верным ударением на текстах с ручной разметкой (U+0301 после ударной гласной, «ё» без знака — ударная):
пять текстов классики из FreeLanguageTools/stress-russian-books (correctness_tests, копия в ../stressed-books/), там же их
бенчмарк чужих систем. Текст подаётся модели без «ё», как пишет пользователь; сравнивается только позиция ударения у слов,
где она есть в золоте (клитики без знака пропускаются). Считает Silero Stress как есть и зеркало аппки (gramPass, фразы,
stress_fixes — как в homo_eval.py). Промахи — в app/build/books_eval_miss.txt.
Запуск: <venv с silero-stress>/bin/python tools/books_eval.py [папка или файлы .txt, по умолчанию ../stressed-books]"""
import collections, glob, json, os, re, sys
from silero_stress import load_accentor

HERE = os.path.dirname(os.path.abspath(__file__)); ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE); import phrases_extra as pe, stress_fixes as sf
from aot_morph import Table
d = json.load(open(os.path.join(ROOT, 'app/src/main/assets/silero/silero_ru.json'), encoding='utf-8'))
gram, homo, phrases = d['gram'], d['homodict'], d['phrases']
fixes = sf.load_fixes(); morph = Table()
for w, items in pe.load_extra().items(): phrases[w] = sorted(items + [tuple(x) for x in phrases.get(w, [])], key=lambda x: -len(x[0]))
ss = load_accentor()
ACUTE = '́'; VOW = 'аеёиоуыэюя'
gold_re = re.compile(r'[а-яё́̂-]+', re.I); word_re = re.compile(r'[а-яё+-]+', re.I)
sent_re = re.compile(r'(?<=[.!?…»"])\s+(?=[А-ЯЁ«"—-])')


def vowel_idx(word):
    """номер ударной гласной (по «+» или «ё») или None"""
    plain = word.replace('+', '')
    if '+' in word: pos = word.index('+')
    elif 'ё' in plain: pos = plain.index('ё')
    else: return None
    return sum(c in VOW for c in plain[:pos])


def gold_idx(word):
    plain = word.replace(ACUTE, '').replace('̂', '')
    if ACUTE in word: pos = word.index(ACUTE) - 1
    elif 'ё' in plain: pos = plain.index('ё')
    else: return None
    return sum(c in VOW for c in plain[:pos])


def phrase_pick(w, text):
    for p, var in phrases.get(w, []):
        if re.search(r'(?<![а-яё-])' + re.escape(p) + r'(?![а-яё-])', text): return var
    return None


def app_mirror(toks, i, text, base):
    """что поставит аппка для слова toks[i] (без «+»): gramPass → фразы → поправки, иначе как модель"""
    w = toks[i]; ours = pe.app_pick(w, toks, i, text, gram, homo, morph, phrase_pick) or base
    if w in fixes and not pe.extra_pick(w, text): ours = fixes[w]
    return ours


args = sys.argv[1:] or [os.path.join(os.path.dirname(ROOT), 'stressed-books')]
files = sorted(f for a in args for f in (glob.glob(os.path.join(a, '*.txt')) if os.path.isdir(a) else [a]))
total = collections.Counter(); miss = []
for path in files:
    st = collections.Counter()
    for para in open(path, encoding='utf-8').read().split('\n'):
        if not para.strip(): continue
        for gold_sent in sent_re.split(para.strip()):
            gold_words = gold_re.findall(gold_sent)
            plain = gold_sent.replace(ACUTE, '').replace('̂', '').replace('ё', 'е').replace('Ё', 'Е')
            got = ss(plain, put_yo=False, put_yo_homo=False)
            out_words = word_re.findall(got)
            if len(out_words) != len(gold_words): st['предложение не совпало по словам'] += 1; continue
            toks = [t.replace('+', '').lower() for t in out_words]; low = plain.lower()
            for i, (g, o) in enumerate(zip(gold_words, out_words)):
                gi = gold_idx(g.lower())
                if gi is None: continue
                st['слов'] += 1
                si = vowel_idx(o.lower()); ai = vowel_idx(app_mirror(toks, i, low, o.lower()))
                st['silero верно'] += si == gi; st['silero без ударения'] += si is None
                st['аппка верно'] += ai == gi; st['аппка без ударения'] += ai is None
                if ai != gi or si != gi:
                    j = low.find(toks[i]); miss.append((os.path.basename(path), low[max(0, j - 40):j + 40].replace('\n', ' '), g, o, app_mirror(toks, i, low, o.lower())))
    n = st['слов']
    print(f"{os.path.basename(path)}: слов {n}, не выровнено {st['предложение не совпало по словам']} предл.; Silero {st['silero верно'] * 100 / n:.1f}% "
          f"(без ударения {st['silero без ударения']}), аппка {st['аппка верно'] * 100 / n:.1f}% (без ударения {st['аппка без ударения']})")
    total.update(st)
n = total['слов']
print(f"ИТОГО: слов {n}; Silero {total['silero верно'] * 100 / n:.1f}%, аппка {total['аппка верно'] * 100 / n:.1f}%; без ударения "
      f"{total['silero без ударения']} / {total['аппка без ударения']}; не выровнено {total['предложение не совпало по словам']} предл.")
with open(os.path.join(ROOT, 'app/build/books_eval_miss.txt'), 'w', encoding='utf-8') as o:
    o.write('# файл | контекст | золото | Silero Stress | аппка\n')
    for m in miss: o.write(' | '.join(m) + '\n')
