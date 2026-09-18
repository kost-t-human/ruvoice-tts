"""Экспорт Silero v5_5_ru в .ptl + json для Android. Запуск: python3 tools/export_silero.py
После него — tools/export_silero_stress.py: он перекрывает accentor.ptl, homo.ptl и стрессовую часть json,
и ../venv-et/bin/python tools/vocoder_et.py: бэкбон вокодера в backbone.pte (ExecuTorch, XNNPACK).
tts_mel.ptl — forward без вокодера (отдаёт мел), head.ptl — голова вокодера с iSTFT;
tts_mel.ptl и head.ptl — в app/src/full/assets/silero (сборка full)."""
import json, os, re, sys
from typing import List
import torch
from torch.jit.mobile import _load_for_lite_interpreter
from silero_export import split_tts

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
PT = os.path.join(HERE, 'v5_5_ru.pt')
ASSETS = os.path.join(ROOT, 'app/src/main/assets/silero')
MODEL_ASSETS = os.path.join(ROOT, 'app/src/full/assets/silero')  # модель только в сборке full
TEST_RES = os.path.join(ROOT, 'app/src/test/resources')
os.makedirs(ASSETS, exist_ok=True); os.makedirs(MODEL_ASSETS, exist_ok=True); os.makedirs(TEST_RES, exist_ok=True)

imp = torch.package.PackageImporter(PT)
big = imp.load_pickle('tts_models', 'model')
pk = big.packages[0]
mod = imp.import_module('multi_acc_v3_package')  # константы TYPE2ID, WH_FORMS, classify_sentence
tts_vocoder = pk.models[0].vocoder
hs = pk.accentor.homosolver
acc = pk.accentor.accentor
hm = hs.model


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


def tts_args(text, sr=48000):
    seq, _ = pk.preprocess_tacotron(text); seq = seq.unsqueeze(0); n = seq.shape[1]
    return (seq, torch.LongTensor([4]), sr, None, torch.ones(1, n), torch.ones(1, n), None, None, 'cpu', -1, False,
            torch.zeros(1, n, dtype=torch.long), None)


def export_models():
    global tts_ref, tts_ref24
    with torch.no_grad():  # эталоны до хирургии графа
        tts_ref, _ = pk.models[0](*tts_args('прив+ет, м+ир.'))
        tts_ref24, _ = pk.models[0](*tts_args('прив+ет, м+ир.', 24000))
    wrapq = HomoQ(hm)
    full_w = hm.bert.embeddings.word_embeddings.weight.data
    hm.bert.embeddings.word_embeddings.weight.data = torch.zeros(1, full_w.shape[1], dtype=torch.int8)
    homo = torch.jit.script(wrapq)
    homo._save_for_lite_interpreter(os.path.join(ASSETS, 'homo.ptl'))
    acc.model._save_for_lite_interpreter(os.path.join(ASSETS, 'accentor.ptl'))
    tts = pk.models[0]
    # tts_mel.ptl = tts без вокодера, head.ptl = вокодер без бэкбона (silero_export.split_tts)
    split_tts(tts, os.path.join(MODEL_ASSETS, 'tts_mel.ptl'), os.path.join(MODEL_ASSETS, 'head.ptl'))
    # эталонный (деквантованный) homosolver для проверки
    hm.bert.embeddings.word_embeddings.weight.data = full_w
    big.unpack_q_model()
    return homo


def export_json():
    data = {
        'symbols': pk.symbols, 'symbol_to_id': pk.symbol_to_id, 'sos': pk.sos_token, 'eos': pk.eos_token,
        'alphabet': ''.join(pk.alphabet), 'speakers': pk.speaker_to_ids[0],
        'exceptions': {k: list(v) for k, v in acc.exceptions.items()},
        'homodict': hs.homodict,
        'bert': {'vocab': hs.tokenizer.vocab, 'cls': hs.tokenizer.cls_token_id, 'sep': hs.tokenizer.sep_token_id,
                 'pad': hs.tokenizer.pad_token_id, 'unk': hs.tokenizer.unk_token_id,
                 'homo_start': hs.tokenizer.homo_start_id, 'homo_end': hs.tokenizer.homo_end_id,
                 'max_len': hs.tokenizer.model_max_length},
        'type2id': mod.TYPE2ID, 'wh_forms': sorted(mod.WH_FORMS), 'leading_fillers': sorted(mod.LEADING_FILLERS),
        'tag_patterns': mod.TAG_PATTERNS,
    }
    with open(os.path.join(ASSETS, 'silero_ru.json'), 'w', encoding='utf-8') as f:
        json.dump(data, f, ensure_ascii=False)


GOLDEN_TEXTS = [
    'Поздним вечером старый смотритель запер тяжёлые ворота и пошёл вдоль стены.',
    'На двери висел старый замок, а на холме стоял замок.',
    'Он ел, а она все ещё ждала.',
    'Что могло привести гостей в такой час?',
    'Ты придёшь завтра, правда?',
    'Пойдём в кино или останемся дома?',
    'Никто не приезжал сюда уже много лет!',
    'Ну и когда это было?',
    'Кто-то постучал в дверь.',
    'Большие дороги ведут в города, а маленькие в деревни.',
    'Я узнаю его по походке.',
    'Мука была просеяна, и мука прошла.',
    'Мы видели белки в лесу и белки в яйце.',
    'Атлас мира лежал на столе из атласа.',
    'Ирис расцвёл, и ирис был вкусным.',
    'Пора — сказал он.',
    'Это всё… И больше ничего.',
    'Дом, милый дом.',
    '«Иди сюда», — сказала она.',
    'А что, если нет?',
    'Сколько стоит этот хлеб?',
    'Да ну?',
    'Ветер стих, и стало тихо.',
    'Он плачу за всех, а вы плачу не платите.',
    'Мели, Емеля, твоя неделя.',
    'Зрачки сузились. Он ждал.',
    'Идём же!',
    'Где ты был вчера вечером?',
    'Вот это да!',
    'Он засыпал и засыпал яму.',
    'Разве не так?',
    'Ведь так?',
    'Как же долго я тебя ждал!',
    'Совсем как в детстве.',
    'Не могу поверить.',
    'Село солнце за село.',
    'Стрелки часов замерли.',
    'Лечу на самолёте и лечу зубы.',
    'Ключ от квартиры, где деньги лежат.',
    'Всё хорошо.',
]


def make_golden():
    out = []
    for text in GOLDEN_TEXTS:
        sentence, clean, has = pk.prepare_text_input(text, None)
        accented = pk.accentor(sentence)
        ids, _ = pk.preprocess_tacotron(accented)
        item = {'text': text, 'prepared': sentence, 'accented': accented,
                'type': mod.classify_sentence(text), 'ids': ids.tolist()}
        tagged = [t for t in hs._find_and_tag_homos(sentence) if t[4] is not None]
        if tagged:
            item['bert'] = [{'marked': t[4], 'ids': hs.tokenizer(t[4])} for t in tagged]
        out.append(item)
    with open(os.path.join(TEST_RES, 'golden.json'), 'w', encoding='utf-8') as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    return out


def verify(homo_script):
    """lite-модули дают тот же выход, что полная модель."""
    lite_h = _load_for_lite_interpreter(os.path.join(ASSETS, 'homo.ptl'))
    lite_a = _load_for_lite_interpreter(os.path.join(ASSETS, 'accentor.ptl'))
    lite_m = _load_for_lite_interpreter(os.path.join(MODEL_ASSETS, 'tts_mel.ptl'))
    lite_head = _load_for_lite_interpreter(os.path.join(MODEL_ASSETS, 'head.ptl'))
    ids = torch.tensor(hs.tokenizer('На двери висел старый [HOMO] замок [/HOMO] , а на холме стоял замок.')).unsqueeze(0)
    st = torch.where(ids[0] == hs.tokenizer.homo_start_id)[0]; en = torch.where(ids[0] == hs.tokenizer.homo_end_id)[0]
    assert (lite_h(ids, st, en) - hm(ids, st, en)).abs().max().item() < 1e-5, 'homo mismatch'
    words = ['привет', 'замок', 'молоко', 'ёжик', 'а']
    ls, ly = lite_a(words); rs, ry = acc.model(words)
    assert (ls - rs).abs().max().item() == 0 and (ly - ry).abs().max().item() == 0, 'accentor mismatch'
    with torch.no_grad():
        mel, _ = lite_m(*tts_args('прив+ет, м+ир.')); h = tts_vocoder.backbone(mel)
        a = lite_head(h, 48000, 0., True); a24 = lite_head(h, 24000, 0., True)
    assert (a - tts_ref).abs().max().item() == 0 and (a24 - tts_ref24).abs().max().item() == 0, 'tts_mel+head mismatch'
    torch.save(mel.contiguous(), os.path.join(HERE, 'mel_sample.pt'))  # вход для проверки vocoder_et.py
    tts_ref.numpy().astype('<f4').tofile(os.path.join(ROOT, 'app/src/androidTest/assets/golden_audio.f32'))  # эталон для SileroModelsTest
    print('verify: ok')


if __name__ == '__main__':
    homo_script = export_models()
    export_json()
    golden = make_golden()
    verify(homo_script)
    for d, n in ((MODEL_ASSETS, 'tts_mel.ptl'), (MODEL_ASSETS, 'head.ptl'), (ASSETS, 'accentor.ptl'), (ASSETS, 'homo.ptl'), (ASSETS, 'silero_ru.json')):
        print(n, round(os.path.getsize(os.path.join(d, n)) / 1048576, 1), 'MB')
    print('golden:', len(golden), 'items')
