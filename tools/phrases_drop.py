#!/usr/bin/env python3
"""Фразы Silero (phrases в silero_ru.json), которые аппка не применяет: tools/phrases_drop.txt, строка «фраза = слов+о  # причина».
Критерий (tools/phrase_stats.py по золоту): с фразой верных меньше, чем без неё (вредная), или столько же при ≥10 срабатываниях
(бесполезная — модель и так права, а вне золота фраза только рискует). export_silero_stress.py фильтрует ими экспорт;
запуск python3 tools/phrases_drop.py — убрать их из готового json без переэкспорта."""
import json, os

HERE = os.path.dirname(os.path.abspath(__file__)); ROOT = os.path.dirname(HERE)
DROP = os.path.join(HERE, 'phrases_drop.txt'); JSON = os.path.join(ROOT, 'app/src/main/assets/silero/silero_ru.json')


def load():
    out = set()
    for line in open(DROP, encoding='utf-8'):
        s = line.split('#', 1)[0].strip()
        if '=' in s: out.add(tuple(x.strip() for x in s.split('=', 1)))
    return out


def apply(phrases):
    """phrases: слово → [[фраза, вариант], …]; возвращает число убранных"""
    drop = load(); n = 0
    for w in list(phrases):
        kept = [x for x in phrases[w] if (x[0], x[1]) not in drop]
        n += len(phrases[w]) - len(kept)
        if kept: phrases[w] = kept
        else: del phrases[w]
    return n


if __name__ == '__main__':
    d = json.load(open(JSON, encoding='utf-8'))
    n = apply(d['phrases'])
    json.dump(d, open(JSON, 'w', encoding='utf-8'), ensure_ascii=False)
    print(f'убрано фраз Silero: {n}')
