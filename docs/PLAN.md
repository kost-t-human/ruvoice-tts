# Мини-план: морфология для нормализатора и добор ударений

Состояние на 15.09.2026: ветка `dev`, последний коммит `b21aba6`. Все JVM-тесты зелёные, на Galaxy A32
HomographEvalTest 82 %, YoEvalTest 96 %, golden OK. Релиз и дообучение BERT отложены (корпуса нет).

Три задачи, каждая — своя спека, свой субагент, свой измеритель. Порядок важен: п. 3 использует таблицу из п. 1.

| № | Задача | Спека | Измеритель | Оценка |
|---|--------|-------|------------|--------|
| 1 | Таблица морфологии AOT (`morph.bin`, mmap + бинарный поиск по хэшу) и её подключение в Normalizer: род после «1/2/3-й», падеж числительного по форме соседнего слова, раскрытие сокращений-прилагательных | [spec-morph.md](spec-morph.md) | `RuNormalizrCorpusTest` ≥ 485/495, новые кейсы из NORMALIZER.md; старт приложения не медленнее | 1–1,5 дня |
| 2 | Промахи акцентора вне омографов: три голоса (словарь Демагога, AOT, Викисловарь) против модели → системный словарь; частотные имена — руками | [spec-stress-accentor.md](spec-stress-accentor.md) | `tools/homo_eval.py` (82 %), `aot_survey` 1,59 %, `DemagogCorpusTest`, `HomographEvalTest` | полдня + просмотр порциями |
| 3 | Промахи BERT: контексты ё-омографов из корпуса Википедии → фразы системного словаря; согласование с прилагательным в gramPass через таблицу п. 1; ручные фразы по частым словам | [spec-stress-homographs.md](spec-stress-homographs.md) | `HomographEvalTest` ≥ 82 % (порог поднять по факту), `YoEvalTest` ≥ 92 %, `GramPassCorpusTest` | день + просмотр порциями |

## Общие правила для всех спек

- Коммитить только по просьбе пользователя. Никаких трейлеров `Co-Authored-By` / `Claude-Session`.
- Словарь Демагога — чужой: файл не в репо, его имя файла нигде не упоминается (в коде, коммитах, доках —
  «словарь Демагога»). Для тестов он лежит симлинком в гитигнорной `app/src/test/resources/local/`.
- Тесты на телефоне только `adb install -r` + `adb shell am instrument -w -e class …`; **никогда**
  `./gradlew connectedAndroidTest` — он сносит пакет вместе с данными пользователя. После прогона
  `adb uninstall ru.kost.ruvoice.test`.
- Чужие словари (AOT LGPL, Викисловарь CC BY-SA, Википедия CC BY-SA) — не копировать оптом: только
  проверенные списки, происхождение указывать в README «Лицензии».
- Большие входные данные лежат вне репо, в scratchpad сессии
  `/tmp/claude-1000/-home-kost-serverApp-ruvoice/748723da-ad5b-4ba1-ac6d-6d37fdd0e81b/scratchpad/`:
  `aot/{morphs,gramtab}.json` (github.com/sokirko74/morph_dict, data/Russian), `kaikki_ru.jsonl`
  (kaikki.org, Russian), `ru_full.txt` (github.com/hermitdave/FrequencyWords, ru), `ruwiki1.xml.bz2`,
  `ruwiki2.xml.bz2` (dumps.wikimedia.org, ruwiki pages-articles части 1–2), `sstress/` — venv с
  пакетом silero-stress (`sstress/bin/python`). Производные — в `app/build/` (гитигнор):
  `aot_forms.tsv`, `wikt_forms.tsv`, `aot_survey.txt`, `ss_rows.tsv`, `yo_corpus.txt`, `homo_eval_miss.txt`.
- Стиль: код и комментарии как в соседних файлах, по-русски, коротко. README и NORMALIZER.md обновлять
  в том же коммите, что и функциональность.
