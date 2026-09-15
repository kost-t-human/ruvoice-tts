#!/usr/bin/env python3
"""Встроенные поправки ударений поверх Silero Stress: tools/stress_fixes.txt → exceptions в silero_ru.json.

  python3 tools/stress_fixes.py build   — собрать список: промахи модели по AOT (app/build/aot_survey.txt,
      tools/aot_survey.py), подтверждённые Викисловарём (app/build/wikt_forms.tsv, tools/wikt_forms.py);
      имена, слова с «ё» (и те, что модель читает через «ё») и слова, уже лежащие в exceptions, не берутся. Частота для сортировки — ru_full.txt
      (github.com/hermitdave/FrequencyWords, OpenSubtitles) рядом с aot_survey.txt, если есть.
  python3 tools/stress_fixes.py build3  — три голоса против модели: словарь Демагога (вариант с максимальной
      частотой из homographs-unknown.txt рядом с репозиторием) или верный ответ HomographResolutionEval
      (app/build/homo_eval_miss.txt, слова не из homodict), AOT и Викисловарь. Кандидат — словарь с хотя бы
      одним арбитром против модели, ни один арбитр не за модель, у арбитров одно ударение; фильтры те же, что
      в build. Пишет app/build/stress_fixes_new.txt (на просмотр, в список дописывать руками),
      hidden_homographs.txt (у арбитра два ударения — скрытые омографы) и stress_names_review.txt (нет ни в одном
      арбитре или имя по тегам: верхние 500 по частоте с фразой, на ручной просмотр).
  python3 tools/stress_fixes.py apply   — записать список в exceptions json (сейчас не используется: список идёт
      в системный словарь, tools/system_dicts.py).
Формат строки: «слово = сл+ово  # комментарий». Список можно править руками; build его перезаписывает целиком,
поэтому после build3 хвост списка (после строки «# build3») переносить заново."""
import json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__)); ROOT = os.path.dirname(HERE)
BUILD = os.path.join(ROOT, 'app/build'); FIXES = os.path.join(HERE, 'stress_fixes.txt')
JSON = os.path.join(ROOT, 'app/src/main/assets/silero/silero_ru.json')
NAMES = {'name', 'surname', 'patr'}  # теги AOT; в Викисловаре имена — pos «name»
UNKNOWN = os.path.join(os.path.dirname(ROOT), 'homographs-unknown.txt')
# просмотрено руками: в текстах это имена (Арчи, Вита, Карим, Дик, Морено, Голубев…) или другое слово (на скаку, левел)
SKIP = set('арчи морено вита карим дика туту усе камеди скаку голубев зверев власов каменев корсаков левел муслим тютю анхеле спались'.split())
# build3, просмотрено 15.09.2026: варианты нормы (б+унгало/бунг+ало, щав+ель), словарь неправ (сх+арчит, ки+оскер) или омограф (перепл+ачу/переплач+у)
SKIP |= set('бунгало пидары ведьмовской ведьмовская киоскер щавелем схарчит просолена издревна имиже абами аргаса искусствъ переплачу полулитра наволочь'.split())
# без «ё» не решить: пров+ернут/провёрнут, зат+оченный/заточённый, пристыжен, захлестнут, дохнем, пахнете
SKIP |= set('провернут пристыжен заточенное заточенного захлестнут тяжеленько повторенного дохнем пахнете'.split())


def load_fixes():
    out = {}
    if not os.path.exists(FIXES): return out
    for line in open(FIXES, encoding='utf-8'):
        s = line.split('#', 1)[0].strip()
        if '=' in s:
            k, v = (x.strip() for x in s.split('=', 1))
            if v.replace('+', '') == k and v.count('+') == 1: out[k] = v
    return out


def apply(data):
    """exceptions: слово → [индекс ударной гласной, индекс «ё» или -1]."""
    n = 0
    for w, v in load_fixes().items():
        data['exceptions'][w] = [v.index('+'), -1]; n += 1
    return n


def build():
    with open(JSON, encoding='utf-8') as f: exc = json.load(f)['exceptions']
    exc = {w for w in exc if w not in load_fixes()}  # свои же поправки не считаем чужими исключениями
    wk = {}
    for l in open(os.path.join(BUILD, 'wikt_forms.tsv'), encoding='utf-8'):
        p = l.rstrip('\n').split('\t'); wk[p[0]] = [v.split(' ')[0] for v in p[1:]]
    freq = {}
    fp = os.path.join(BUILD, 'ru_full.txt')
    if os.path.exists(fp):
        for l in open(fp, encoding='utf-8'): w, n = l.split(); freq[w] = int(n)
    rows = []
    for l in open(os.path.join(BUILD, 'aot_survey.txt'), encoding='utf-8'):
        if l.startswith('#'): continue
        w, aot, got, gr = l.rstrip('\n').split('\t')
        if w in exc or w in SKIP or 'ё' in got or wk.get(w) != [aot]: continue
        i = got.find('+е')  # модель читает «е» как «ё» (шоф+ером, т+елки): если такое слово с «ё» есть, это не промах
        if i >= 0 and (got[:i] + 'ё' + got[i + 2:]) in wk: continue
        if any('ё' in a.split(':')[0] for a in gr.split('|')): continue
        if NAMES & set(gr.split('|')[0].split(':')[2].split(',')): continue
        rows.append((w, aot, got, freq.get(w, 0), gr.split('|')[0]))
    rows.sort(key=lambda r: (-r[3], r[0]))
    with open(FIXES, 'w', encoding='utf-8') as o:
        o.write('# Поправки ударений: модель Silero Stress ставит иначе, AOT и Викисловарь согласны. tools/stress_fixes.py build\n'
                '# слово = ударение  # частота в субтитрах, как ставит модель, разбор AOT\n')
        for w, aot, got, fq, gr in rows: o.write(f'{w} = {aot}  # {fq}, модель {got}, {gr}\n')
    print(f'поправок: {len(rows)} → {FIXES}')


def forms(name, words):
    """форма → {ударный вариант: разбор} из app/build/*_forms.tsv, только для нужных слов."""
    out = {}
    for l in open(os.path.join(BUILD, name), encoding='utf-8'):
        w = l[:l.index('\t')]
        if w in words: out[w] = dict(v.split(' ', 1) for v in l.rstrip('\n').split('\t')[1:])
    return out


def build3():
    with open(JSON, encoding='utf-8') as f: d = json.load(f)
    fixes = load_fixes(); exc = {w for w in d['exceptions'] if w not in fixes}
    freq = {}
    for l in open(os.path.join(BUILD, 'ru_full.txt'), encoding='utf-8'): w, n = l.split(); freq[w] = int(n)
    # слово → (голос словаря, голос модели, кто, фраза); словарь — вариант с максимальной частотой
    words = {}
    def votes(s): return {v: int(n) for v, n in (t.rsplit(':', 1) for t in s.split(' ')[1:])}
    for l in open(UNKNOWN, encoding='utf-8'):
        if l.startswith('== '):
            w, dic, _, ss, homo = l[3:].rstrip('\n').split(' | ')
            if homo.endswith('да') or w in d['homodict']: continue  # омограф для модели — спека 3
            dv, mv = (max(v, key=v.get) for v in (votes(dic), votes(ss)))
            words[w] = [dv, mv, 'словарь', None]
        elif l.startswith('   ') and w in words and words[w][3] is None: words[w][3] = l.strip().split(' | ')[0]
    for l in open(os.path.join(BUILD, 'homo_eval_miss.txt'), encoding='utf-8'):
        if l.startswith('#'): continue
        text, t, got, _ = l.rstrip('\n').split(' | '); w = t.replace('+', '')
        if w in d['homodict'] or got == t: continue
        if w in words and words[w][2] == 'eval' and words[w][0] != t: words[w][0] = '?'  # в корпусе оба варианта — омограф
        elif w not in words: words[w] = [t, got, 'eval', text]
    aot, wk = forms('aot_forms.tsv', words), forms('wikt_forms.tsv', words)
    rows, hidden, names = [], [], []
    for w, (dv, mv, src, text) in words.items():
        if w in exc or w in fixes or w in SKIP or 'ё' in w or dv == mv: continue
        i = mv.find('+е')
        if i >= 0 and (mv[:i] + 'ё' + mv[i + 2:]) in wk: continue
        a, k = aot.get(w, {}), wk.get(w, {})
        gr = next(iter(a.values()), '') + '|' + next(iter(k.values()), '')
        name = any(NAMES & set(g.split(':')[2].split(',')) for g in gr.split('|') if g.count(':') >= 2) or any(g.split(':')[1] == 'name' for g in gr.split('|') if g.count(':') >= 2)
        if not a and not k or name: names.append((w, dv, mv, freq.get(w, 0), 'имя' if name else 'нет в арбитрах', text)); continue
        if len(a) > 1 or len(k) > 1 or dv == '?': hidden.append((w, freq.get(w, 0), src, dv, mv, ' / '.join(sorted(set(a) | set(k))))); continue
        av, kv = next(iter(a), None), next(iter(k), None)
        if mv in (av, kv) or dv not in (av, kv) or av and kv and av != kv: continue  # арбитр за модель или арбитры врозь
        if any('ё' in g.split(':')[0] for g in gr.split('|')): continue  # шелка: шёлка или шелк+а — не решить
        who = src + '+' + '+'.join(n for n, v in (('AOT', av), ('wikt', kv)) if v == dv)
        rows.append((w, dv, freq.get(w, 0), who, mv, gr.strip('|').split('|')[0], text))
    rows.sort(key=lambda r: (-r[2], r[0])); hidden.sort(key=lambda r: (-r[1], r[0])); names.sort(key=lambda r: (-r[3], r[0]))
    with open(os.path.join(BUILD, 'stress_fixes_new.txt'), 'w', encoding='utf-8') as o:
        o.write('# build3: словарь Демагога или корпус eval + арбитр против модели; на просмотр, потом в tools/stress_fixes.txt\n'
                '# слово = ударение  # частота, кто голосовал, как ставит модель, разбор, фраза\n')
        for w, dv, fq, who, mv, g, text in rows: o.write(f'{w} = {dv}  # {fq}, {who}, модель {mv}, {g}, «{text}»\n')
    with open(os.path.join(BUILD, 'hidden_homographs.txt'), 'w', encoding='utf-8') as o:
        o.write('# Скрытые омографы: у AOT или Викисловаря два ударения, модель не считает омографом (для спеки 3)\n'
                '# слово | частота | источник | словарь | модель | варианты арбитров\n')
        for r in hidden: o.write(' | '.join(map(str, r)) + '\n')
    with open(os.path.join(BUILD, 'stress_names_review.txt'), 'w', encoding='utf-8') as o:
        o.write('# Нет ни в одном арбитре или имя по тегам: верхние 500 по частоте, на ручной просмотр, в словарь автоматически не кладём\n'
                '# слово | словарь | модель | частота | почему | фраза\n')
        for r in names[:500]: o.write(' | '.join(map(str, r)) + '\n')
    print(f'кандидатов: {len(rows)}, скрытых омографов: {len(hidden)}, без арбитра/имён: {len(names)} → {BUILD}')


if __name__ == '__main__':
    if sys.argv[1:] == ['build']: build()
    elif sys.argv[1:] == ['build3']: build3()
    elif sys.argv[1:] == ['apply']:
        with open(JSON, encoding='utf-8') as f: d = json.load(f)
        n = apply(d)
        with open(JSON, 'w', encoding='utf-8') as f: json.dump(d, f, ensure_ascii=False)
        print(f'exceptions += {n}')
    else: print(__doc__)
