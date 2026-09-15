#!/usr/bin/env python3
"""Словарь *.dic под движок Николая (ударение «<» или «ъ<» после гласной, «>» побочное, комментарии «//»)
→ наш формат («+» перед гласной). Regex-строки («#…») и «//» выбрасываются.
Кодировка: UTF-8 с BOM или без, иначе cp1251. Пример: nicolai_to_plus.py in.dic out.txt"""
import sys

raw = open(sys.argv[1], 'rb').read()
try: text = raw.decode('utf-8-sig')
except UnicodeDecodeError: text = raw.decode('cp1251')
out = []
for line in text.splitlines():
    s = line.strip()
    if not s or s.startswith('//') or s.startswith('#') or '=' not in s: continue
    key, val = s.split('=', 1)
    v = []
    for c in val:
        if c == '<':
            if v and v[-1] == 'ъ': v.pop()
            v.insert(len(v) - 1 if v else 0, '+')
        elif c != '>': v.append(c)
    out.append(f"{key}={''.join(v)}")
open(sys.argv[2], 'w', encoding='utf-8').write('\n'.join(out) + '\n')
print(sys.argv[2], len(out))
