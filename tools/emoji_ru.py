#!/usr/bin/env python3
"""Сборка app/src/main/assets/emoji_ru.tsv: русские имена эмодзи из Unicode CLDR (type="tts").

Берутся common/annotations/ru.xml и common/annotationsDerived/ru.xml (тона кожи, флаги, семьи).
Только эмодзи — знаки препинания и символы (стрелки, «×», «©») читает правило symbol_names.
Тон кожи из имён убирается. Ключ — последовательность без U+FE0F (вариант начертания речи не меняет, в тексте он то есть, то нет).

    python3 tools/emoji_ru.py ru.xml ru_derived.xml > app/src/main/assets/emoji_ru.tsv
Данные: Unicode CLDR, Unicode License v3 (заголовок файла).
"""
import re
import sys
import xml.etree.ElementTree as ET

# Тон кожи в речи — шум («машет рукой: средний тон кожи»): ключ с модификатором остаётся (вся
# последовательность — одно эмодзи), из имени тон убирается.
TONE = re.compile(r'[:,]?\s*(?:очень светлый|светлый|средний|темный|тёмный|очень темный|очень тёмный) тон кожи')

HEADER = """# Имена эмодзи — Unicode CLDR (common/annotations/ru.xml, annotationsDerived/ru.xml), tools/emoji_ru.py.
# Copyright © 2001–2026 Unicode, Inc. Distributed under the Unicode License v3 (SPDX: Unicode-3.0):
# Полный текст уведомления — emoji_ru.LICENSE.txt рядом.
# Формат: эмодзи без U+FE0F <TAB> имя."""


def is_emoji(seq: str) -> bool:
    for ch in seq:
        c = ord(ch)
        if c >= 0x1F000 or 0x2600 <= c <= 0x27BF or 0x2300 <= c <= 0x23FF or 0x2B00 <= c <= 0x2BFF \
                or c in (0x20E3, 0x3030, 0x303D, 0x3297, 0x3299):
            return True
    return False


def main(paths):
    names = {}
    for p in paths:
        for a in ET.parse(p).getroot().iter('annotation'):
            if a.get('type') != 'tts':
                continue
            key = a.get('cp').replace('️', '')
            if key and is_emoji(key):
                name = TONE.sub('', a.text.strip()).strip(' :,')
                if name:
                    names[key] = name
    print(HEADER)
    for k in sorted(names):
        print(f"{k}\t{names[k]}")


if __name__ == '__main__':
    main(sys.argv[1:])
