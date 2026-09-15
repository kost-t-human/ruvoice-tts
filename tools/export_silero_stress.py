"""Экспорт Silero Stress (pip install silero-stress, MIT) вместо accentor-а и homosolver-а из v5_5_ru:
accentor.ptl, homo.ptl, стрессовая часть silero_ru.json (exceptions, homodict, bert, phrases) и
golden.json (accented, bert). tts.ptl и остальной json — из tools/export_silero.py, не трогаем.
Запуск из venv с silero-stress: python3 tools/export_silero_stress.py"""
import json, os, sys
from typing import List
import torch
from torch.jit.mobile import _load_for_lite_interpreter
import silero_stress

HERE = os.path.dirname(os.path.abspath(__file__)); ROOT = os.path.dirname(HERE)
ASSETS = os.path.join(ROOT, 'app/src/main/assets/silero'); TEST_RES = os.path.join(ROOT, 'app/src/test/resources')
PT = os.path.join(os.path.dirname(silero_stress.__file__), 'data', 'accentor.pt')

# сырой пакет: эмбеддинги BERT ещё int8 (load_accentor их деквантует в float)
ss = torch.package.PackageImporter(PT).load_pickle('accentor_models', 'accentor')
hs = ss.homosolver; hm = hs.model; acc = ss.accentor


class HomoQ(torch.nn.Module):
    """BERT с int8-таблицей эмбеддингов: деквантуем только строки текущего входа."""
    def __init__(self, hm):
        super().__init__()
        self.register_buffer('qweight', hm.bert.embeddings.word_embeddings.weight.data.clone())
        self.register_buffer('scale', hm.bert.scale.clone().float())
        self.register_buffer('zero_point', hm.bert.zero_point.clone().float())
        self.bert = hm.bert
        self.homo_clf = hm.homo_clf

    def forward(self, input_ids: torch.Tensor, homo_start_ids: torch.Tensor, homo_end_ids: torch.Tensor) -> torch.Tensor:
        emb = self.scale * (self.qweight[input_ids].float() - self.zero_point)
        hidden = self.bert(None, None, None, None, None, emb)[0]
        feats: List[torch.Tensor] = []
        for i in range(homo_start_ids.size(0)):
            s = int(homo_start_ids[i]); e = int(homo_end_ids[i])
            feats.append(torch.cat([hidden[i, s], hidden[i, s + 1:e].mean(0)]))
        return self.homo_clf(torch.stack(feats))


def export_models():
    wrapq = HomoQ(hm)
    full_w = hm.bert.embeddings.word_embeddings.weight.data
    hm.bert.embeddings.word_embeddings.weight.data = torch.zeros(1, full_w.shape[1], dtype=torch.int8)
    torch.jit.script(wrapq)._save_for_lite_interpreter(os.path.join(ASSETS, 'homo.ptl'))
    acc.model._save_for_lite_interpreter(os.path.join(ASSETS, 'accentor.ptl'))
    # эталон для проверки: деквантованные эмбеддинги, как делает load_accentor
    hm.bert.embeddings.word_embeddings.weight.data = hm.bert.scale * (full_w - hm.bert.zero_point)


def export_json():
    path = os.path.join(ASSETS, 'silero_ru.json')
    with open(path, encoding='utf-8') as f: data = json.load(f)
    t = hs.tokenizer
    data.update({
        'exceptions': {k: list(v) for k, v in acc.exceptions.items()},
        'homodict': hs.homodict,
        'bert': {'vocab': dict(t.vocab), 'cls': t.cls_token_id, 'sep': t.sep_token_id, 'pad': t.pad_token_id, 'unk': t.unk_token_id,
                 'homo_start': t.homo_start_id, 'homo_end': t.homo_end_id, 'max_len': t.model_max_length},
        'phrases': phrases(),
    })
    sys.path.insert(0, HERE); import stress_fixes, phrases_extra
    stress_fixes.apply(data); phrases_extra.apply(data)  # tools/stress_fixes.txt и phrases_extra.txt поверх пакета
    with open(path, 'w', encoding='utf-8') as f: json.dump(data, f, ensure_ascii=False)


def phrases():
    """phrases.json в пакете не сохранён, только скомпилированные regex — разбираем их обратно:
    (?P<зАмок>(?<!…)(?:фраза1|фраза2)(?!…)) → {"замок": [["фраза1", "з+амок"], …]} — список, а не
    словарь: порядок вариантов и фраз (длинные раньше) важен при совпадении в одной позиции."""
    import re
    out = {}
    for word, rx in hs.compiled_phrases.items():
        d = []
        for name, body in re.findall(r'\(\?P<(\w+)>\(\?<!\[а-яА-ЯёЁ\\-\]\)\(\?:(.*?)\)\(\?!\[а-яА-ЯёЁ\\-\]\)\)', rx.pattern):
            variant = next(name[:i] + '+' + name[i:].lower() for i, c in enumerate(name) if c.isupper())
            for alt in body.split('|'):
                phrase = re.sub(r'\\(.)', r'\1', alt).replace(f'[HOMO] {word} [/HOMO]', word)
                d.append([phrase, variant])
        assert d, word
        out[word] = d
    return out


def update_golden():
    """accented и bert пересчитываются новым конвейером; text/prepared/type — от v5, ids — из accented."""
    path = os.path.join(TEST_RES, 'golden.json')
    with open(path, encoding='utf-8') as f: golden = json.load(f)
    with open(os.path.join(ASSETS, 'silero_ru.json'), encoding='utf-8') as f: d = json.load(f)
    s2i = d['symbol_to_id']
    for item in golden:
        sentence = item['prepared']
        item['accented'] = ss(sentence)
        item['ids'] = [s2i[d['sos']]] + [s2i[c] for c in item['accented'] if c in s2i] + [s2i[d['eos']]]
        tagged = [t for t in hs._find_and_tag_homos(sentence) if t[4] is not None]
        item.pop('bert', None)
        if tagged: item['bert'] = [{'marked': t[4], 'ids': hs.tokenizer(t[4])} for t in tagged]
    with open(path, 'w', encoding='utf-8') as f: json.dump(golden, f, ensure_ascii=False, indent=1)
    return golden


def verify():
    lite_h = _load_for_lite_interpreter(os.path.join(ASSETS, 'homo.ptl'))
    lite_a = _load_for_lite_interpreter(os.path.join(ASSETS, 'accentor.ptl'))
    ids = torch.tensor(hs.tokenizer('На двери висел старый [HOMO] замок [/HOMO] , а на холме стоял замок.')).unsqueeze(0)
    st = torch.where(ids[0] == hs.tokenizer.homo_start_id)[0]; en = torch.where(ids[0] == hs.tokenizer.homo_end_id)[0]
    assert (lite_h(ids, st, en) - hm(ids, st, en)).abs().max().item() < 1e-5, 'homo mismatch'
    words = ['привет', 'замок', 'молоко', 'ёжик', 'а', 'ктулхуобразный']
    ls, ly = lite_a(words); rs, ry = acc.model(words)
    assert (ls - rs).abs().max().item() == 0 and (ly - ry).abs().max().item() == 0, 'accentor mismatch'
    print('verify: ok')


if __name__ == '__main__':
    export_models(); export_json(); golden = update_golden(); verify()
    for n in ('accentor.ptl', 'homo.ptl', 'silero_ru.json'):
        print(n, round(os.path.getsize(os.path.join(ASSETS, n)) / 1048576, 1), 'MB')
    print('golden:', len(golden), 'items;', sum('bert' in g for g in golden), 'с омографами')
