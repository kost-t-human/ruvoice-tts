#!/usr/bin/env python3
"""Ёфицированный корпус из дампа русской Википедии (там «ё» пишется по правилам) → app/build/yo_corpus.txt.
Берутся предложения 40–200 символов из основного текста статей (шаблоны, ссылки, таблицы, сноски вырезаны
грубыми регулярками), только кириллица и пунктуация, хотя бы одна «ё». Из каждой статьи не больше двух.
Запуск: python3 tools/wiki_yo_corpus.py <dump.xml.bz2>... [--max N]"""
import bz2, os, re, sys, random
import xml.etree.ElementTree as ET

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'app', 'build', 'yo_corpus.txt')
MAX = 30000
NS = '{http://www.mediawiki.org/xml/export-0.11/}'
re_ref = re.compile(r'<ref[^>/]*/>|<ref[^>]*>.*?</ref>', re.S)
re_tag = re.compile(r'<[^>]+>')
re_file = re.compile(r'\[\[(?:Файл|File|Изображение|Image|Категория|Category):[^\]]*\]\]')
re_link = re.compile(r'\[\[(?:[^|\]]*\|)?([^\]]*)\]\]')
re_ext = re.compile(r'\[https?://[^\s\]]*\s*([^\]]*)\]')
re_sent = re.compile(r'[^.!?]*[.!?]')
re_ok = re.compile(r'^[А-ЯЁ][а-яё\s,.!?:;«»()\-–—0-9]+[.!?]$')


def strip_templates(t):
    while True:
        n = re.sub(r'\{\{[^{}]*\}\}', '', t)
        if n == t: return t
        t = n


def clean(text):
    t = strip_templates(text)
    t = re.sub(r'\{\|.*?\|\}', '', t, flags=re.S)
    t = re_ref.sub('', t); t = re_file.sub('', t); t = re_link.sub(r'\1', t); t = re_ext.sub(r'\1', t); t = re_tag.sub('', t)
    t = t.replace("'''", '').replace("''", '')
    return t


def sentences(text):
    for line in clean(text).split('\n'):
        s = line.strip()
        if not s or s[0] in '*#;:=|{!' or len(s) < 40: continue
        for m in re_sent.finditer(s):
            sent = m.group().strip()
            if 40 <= len(sent) <= 200 and 'ё' in sent and re_ok.match(sent) and '  ' not in sent: yield sent


def pages(path, log=lambda n: None):
    """Викитекст статей основного пространства (не перенаправлений) из дампа; log(n) — каждые 20000 страниц."""
    n = 0
    with bz2.open(path, 'rb') as f:
        for ev, el in ET.iterparse(f):
            if el.tag != NS + 'page': continue
            ns = el.find(NS + 'ns').text
            rev = el.find(NS + 'revision'); txt = rev.find(NS + 'text').text if rev is not None else None
            if ns == '0' and txt and not txt.startswith('#'): yield txt
            el.clear(); n += 1
            if n % 20000 == 0: log(n)


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    limit = int(sys.argv[sys.argv.index('--max') + 1]) if '--max' in sys.argv else MAX
    random.seed(1); out = []
    for path in args:
        for txt in pages(path, lambda n: print(path, n, 'страниц,', len(out), 'предложений', flush=True)):
            got = list(sentences(txt))
            if got: out.extend(random.sample(got, min(2, len(got))))
            if len(out) >= limit: break
        if len(out) >= limit: break
    random.shuffle(out); out = out[:limit]
    with open(OUT, 'w', encoding='utf-8') as o:
        for s in out: o.write(s + '\n')
    print('предложений:', len(out), '→', OUT)


if __name__ == '__main__':
    main()
