#!/usr/bin/env python3
"""Сборка app/src/main/assets/symbols_ru.tsv: русские имена символов из Unicode CLDR (type="tts") для
правила «Служебные символы словами» (SymbolNames). Своя таблица в SymbolNames.NAMES — поверх этой:
там названия как у TalkBack («стрелка вправо», а не «стрелка «направо»»).

Берутся одиночные знаки, не буквы и не цифры, не эмодзи (их читает emoji_ru.tsv) и не обычная
пунктуация — точку, запятую, кавычки, скобки, тире фильтр модели выкидывает нарочно (паузы),
называть их нельзя. Кавычки из имён убираются: «стрелка «направо»» → «стрелка направо».

    python3 tools/symbols_ru.py ru.xml ru_derived.xml > app/src/main/assets/symbols_ru.tsv
Данные: Unicode CLDR, Unicode License v3 (app/src/main/assets/cldr.LICENSE.txt).
"""
import sys
import unicodedata
import xml.etree.ElementTree as ET

from emoji_ru import is_emoji

HEADER = """# Имена символов — Unicode CLDR (common/annotations/ru.xml, annotationsDerived/ru.xml), tools/symbols_ru.py.
# Copyright © 2001–2026 Unicode, Inc. Distributed under the Unicode License v3 (SPDX: Unicode-3.0):
# полный текст уведомления — cldr.LICENSE.txt рядом.
# Формат: знак <TAB> имя."""

# пунктуация предложения и кавычки — паузы, не слова
SENTENCE = set(".,!?:;…¡¿‼⁉'\"`´")


def wanted(ch: str) -> bool:
    if ch.isalnum() or ch.isspace() or is_emoji(ch) or ch in SENTENCE:
        return False
    cat = unicodedata.category(ch)
    # скобки, кавычки-ёлочки, тире: Ps/Pe открывающие и закрывающие, Pi/Pf кавычки, Pd тире; Mn — надстрочные
    return cat not in ('Ps', 'Pe', 'Pi', 'Pf', 'Pd', 'Mn', 'Cc', 'Cf')


def main(paths):
    names = {}
    for p in paths:
        for a in ET.parse(p).getroot().iter('annotation'):
            if a.get('type') != 'tts':
                continue
            k = a.get('cp').replace('️', '')
            if len(k) == 1 and ord(k) <= 0xFFFF and wanted(k):
                names[k] = ' '.join(a.text.replace('«', '').replace('»', '').split())
    print(HEADER)
    for k in sorted(names):
        print(f"{k}\t{names[k]}")


if __name__ == '__main__':
    main(sys.argv[1:])
