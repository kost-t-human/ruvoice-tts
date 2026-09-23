#!/usr/bin/env python3
"""Буква «ё»: сколько Silero Stress восстанавливает на ёфицированном тексте. Корпус — app/build/yo_corpus.txt
(tools/wiki_yo_corpus.py, Википедия); из предложения «ё» стирается, модель ставит её заново.
Слова делятся по словарю eyo (github.com/e2yo/eyo-kernel, MIT; safe.txt и not_safe.txt рядом с корпусом):
однозначные (е→ё всегда), неоднозначные (все/всё) и неизвестные. Раньше модели — словарь eyo safe (как YoDict в аппке), поверх — наши фразы системного словаря
(tools/phrases_extra.txt), как замены в аппке, и правило «все» + слово только мн. ч. из Stress.gramPass
(tools/phrases_extra.vse_pick, таблица morph.bin). Промахи — app/build/yo_eval_miss.txt.
Запуск: [ORIG_HOMO=1] [MARKED=1] <venv с silero-stress>/bin/python tools/yo_eval.py [макс_предложений] [корпус.txt]
Другой корпус — файл ёфицированных предложений по одному на строку, например ../narusco/yo_corpus.txt (фрагменты narusco с
ручной «ё», промахи тогда в app/build/<имя>_miss.txt)."""
import os, re, sys, json, collections, itertools
from silero_stress import load_accentor

HERE = os.path.dirname(os.path.abspath(__file__)); BUILD = os.path.join(os.path.dirname(HERE), 'app/build')
sys.path.insert(0, HERE); import phrases_extra as pe
from aot_morph import Table
morph = Table(); gram = json.load(open(pe.JSON, encoding='utf-8'))['gram']
args = sys.argv[1:]
CORPUS = next((a for a in args if a.endswith('.txt')), os.path.join(BUILD, 'yo_corpus.txt'))
LIMIT = next((int(a) for a in args if a.isdigit()), 0)
MISS = os.path.join(BUILD, ('yo_eval' if CORPUS.startswith(BUILD) else os.path.splitext(os.path.basename(CORPUS))[0]) + '_miss.txt')
word_re = re.compile(r'[а-яё]+')
# слово → [(regex фразы, смещение слова в фразе, вариант)], длинные фразы раньше, как в homo_eval.py
phrases = {w: [(re.compile(r'(?<![а-яё-])' + re.escape(p) + r'(?![а-яё-])'), re.search(r'(?<![а-яё])' + w + r'(?![а-яё])', p).start(), v.replace('+', ''))
               for p, v in sorted(items, key=lambda x: -len(x[0]))] for w, items in pe.load_extra().items()}


def expand(line):
    """«Ёжиков(а|ой|у|ы)» → все формы; «(|а)» — пустое окончание."""
    m = re.match(r'^([^(]*)\(([^)]*)\)$', line)
    if not m: return [line]
    return [m.group(1) + e for e in m.group(2).split('|')]


def eyo(name):
    forms = set()
    path = os.path.join(BUILD, name)
    if not os.path.exists(path): return forms
    for line in open(path, encoding='utf-8'):
        for f in expand(line.strip()): forms.add(f.lower())
    return forms


safe, not_safe = eyo('eyo_safe.txt'), eyo('eyo_not_safe.txt')
# словарь бесспорной «ё» раньше модели, как YoDict в Stress.accentorPass (регистр здесь не различаем — текст в нижнем)
yo_dict = {w.replace('ё', 'е'): w for w in safe}
for line in open(os.path.join(HERE, 'yo_extra.txt'), encoding='utf-8'):   # добор tools/yo_extra.py, в ассете с «_»
    if line.strip() and not line.startswith('#'): yo_dict[line.strip().replace('ё', 'е')] = line.strip()
def kind(w_yo):
    if w_yo in not_safe: return 'неоднозначные'
    if w_yo in safe: return 'однозначные'
    return 'неизвестные'


MARKED = os.environ.get('MARKED')   # 1 — слово промаха в предложении в [[…]] (для ручной проверки)
ss = load_accentor()
if not os.environ.get('ORIG_HOMO'):   # как в аппке: homo.ptl из ассетов (ветка «все/всё», маска); ORIG_HOMO=1 — пакетная модель
    from torch.jit.mobile import _load_for_lite_interpreter
    ss.homosolver.model = _load_for_lite_interpreter(os.path.join(os.path.dirname(HERE), 'app/src/main/assets/silero/homo.ptl'))
lines = [l.strip() for l in open(CORPUS, encoding='utf-8') if l.strip()]
if LIMIT: lines = lines[:LIMIT]
st = collections.defaultdict(collections.Counter); miss = []
for n, sent in enumerate(lines):
    orig = word_re.findall(sent.lower())
    deyo = sent.replace('ё', 'е').replace('Ё', 'Е')
    got = [w.replace('+', '') for w in re.findall(r'[а-яё+]+', ss(deyo, put_yo=True, put_yo_homo=True).lower())]
    if len(got) != len(orig): st['всего']['предложений не сравнить'] += 1; continue
    low = deyo.lower(); pos = [m.start() for m in word_re.finditer(low)]; starts = {p: i for i, p in enumerate(pos)}
    for i, w in enumerate(orig):
        w = w.replace('ё', 'е')
        if w in yo_dict: got[i] = yo_dict[w]
        for p, off, v in phrases.get(w, ()):
            hit = next((m for m in p.finditer(low) if starts.get(m.start() + off) == i), None)
            if hit: got[i] = v; break
        else:
            if w == 'все' and i + 1 < len(orig) and not low[pos[i] + 3:pos[i + 1]].strip() and pe.vse_yo(orig[i + 1], low[pos[i + 1] + len(orig[i + 1]):]): got[i] = 'всё'
            elif w == 'все' and i + 1 < len(orig) and not low[pos[i] + 3:pos[i + 1]].strip() and pe.vse_pick(orig[i + 1].replace('ё', 'е'), morph, gram): got[i] = 'все'
    st['всего']['предложений'] += 1
    for i, (o, g) in enumerate(zip(orig, got)):
        at = sent[:pos[i]] + '[[' + sent[pos[i]:pos[i] + len(o)] + ']]' + sent[pos[i] + len(o):] if MARKED else sent
        if 'ё' in o:
            k = kind(o); st[k]['слов с ё'] += 1
            if g == o: st[k]['ё восстановлена'] += 1
            else: st[k]['ё потеряна'] += 1; miss.append(('потеряна', o, g, at))
        elif 'ё' in g:
            k = kind(g); st[k]['ё лишняя'] += 1; miss.append(('лишняя', o, g, at))
    if n % 5000 == 0: print(n, len(lines), flush=True)
for k in ('однозначные', 'неоднозначные', 'неизвестные'):
    c = st[k]; tot = c['слов с ё']
    print(f"{k:14} слов с ё {tot:6}, восстановлено {c['ё восстановлена']:6} ({c['ё восстановлена'] * 100 / max(tot, 1):.1f}%), потеряно {c['ё потеряна']:5}, лишняя ё {c['ё лишняя']:5}")
print(dict(st['всего']))
with open(MISS, 'w', encoding='utf-8') as o:
    o.write('# что | должно быть | модель | предложение\n')
    for m in miss: o.write(' | '.join(m) + '\n')
