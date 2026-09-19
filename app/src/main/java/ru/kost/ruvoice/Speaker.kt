package ru.kost.ruvoice

/** Голос по имени из Prefs или от читалки: штатный («xenia») или из пака («cis_ru/ru_alexandr»).
 * В сервисе класс android.speech.tts.Voice, поэтому не Voice. */
class Speaker(val name: String, val pack: Pack?, val id: Int, val sym: Symbols, val types: Boolean) {
    companion object {
        const val DEFAULT = "xenia"
        /** Пак штатной модели для сборки lite: голые имена («xenia» из старых prefs) ищем в нём. */
        const val RU_PACK = "ru"
        /** Есть ли модель в APK (сборка full). Тесты подменяют. */
        var builtin = BuildConfig.BUILTIN_MODEL
        private fun packName(pack: Pack, speaker: String) = "${pack.id}/$speaker"
        private fun of(pack: Pack, speaker: String): Speaker? = pack.speakers[speaker]?.let { Speaker(packName(pack, speaker), pack, it, pack.sym, pack.types) }

        fun resolve(name: String?, d: SileroData, packs: List<Pack>): Speaker? {
            if (name == null) return null
            val i = name.indexOf('/')
            if (i < 0) {
                if (builtin) return d.speakers[name]?.let { Speaker(name, null, it, d.sym, true) }
                return packs.firstOrNull { it.id == RU_PACK }?.let { of(it, name) }
            }
            val pack = packs.firstOrNull { it.id == name.substring(0, i) } ?: return null
            return of(pack, name.substring(i + 1))
        }

        /** Голос по умолчанию: «xenia» (штатная или из пака ru), иначе первый голос первого пака; null — голосов нет. */
        fun default(d: SileroData, packs: List<Pack>): Speaker? =
            resolve(DEFAULT, d, packs) ?: names(d, packs).firstOrNull()?.let { resolve(it, d, packs) }

        /** Есть ли хоть один голос: встроенная модель или пак с голосами. Без разбора silero_ru.json. */
        fun hasVoices(packs: List<Pack>) = builtin || packs.any { it.speakers.isNotEmpty() }

        /** Все имена: штатные по алфавиту (в full), затем по пакам. */
        fun names(d: SileroData, packs: List<Pack>): List<String> =
            (if (builtin) d.speakers.keys.sorted() else emptyList()) + packs.flatMap { p -> p.speakers.keys.sorted().map { packName(p, it) } }

        /** Голоса того же движка, что [main] — для прямой речи: в памяти одна тройка моделей,
         * перегружать 90 МБ на каждую реплику нельзя. */
        fun sameEngine(main: Speaker, d: SileroData, packs: List<Pack>): List<String> =
            main.pack?.let { p -> p.speakers.keys.sorted().map { packName(p, it) } } ?: d.speakers.keys.sorted()

        /** Имя для TTS API читалок: «xenia-ru», «marat-ru-cis». Без «/» и «_» (AlReaderX такие не опознаёт),
         * «ru» один раз: голос, язык, пак; в lite «ru/xenia» — «xenia-ru», как в full. */
        fun ttsName(name: String): String {
            val (pack, speaker) = name.split('/').let { if (it.size == 2) it else listOf("", it[0]) }
            fun parts(s: String) = s.split('_').filter { it.isNotEmpty() && it != "ru" }
            return (parts(speaker) + "ru" + parts(pack)).joinToString("-")
        }
        /** Обратно из [ttsName]; имя в старой форме «ru-ru-cis_ru/ru_marat» (до 0.15) — тоже. */
        fun fromTtsName(tts: String?, d: SileroData, packs: List<Pack>): String? =
            tts?.let { t -> names(d, packs).firstOrNull { ttsName(it) == t } ?: t.removePrefix("ru-ru-") }

        /** Подпись в списке: «ru_alexandr (cis_ru)», штатные как есть. */
        fun label(name: String): String = name.indexOf('/').let { i -> if (i < 0) name else "${name.substring(i + 1)} (${name.substring(0, i)})" }
    }
}
