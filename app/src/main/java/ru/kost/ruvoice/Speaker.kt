package ru.kost.ruvoice

/** Голос по имени из Prefs или от читалки: штатный («xenia») или из пака («cis_ru/ru_alexandr»).
 * В сервисе класс android.speech.tts.Voice, поэтому не Voice. */
class Speaker(val name: String, val pack: Pack?, val id: Int, val sym: Symbols, val types: Boolean) {
    companion object {
        const val DEFAULT = "xenia"
        private fun packName(pack: Pack, speaker: String) = "${pack.id}/$speaker"

        fun resolve(name: String?, d: SileroData, packs: List<Pack>): Speaker? {
            if (name == null) return null
            d.speakers[name]?.let { return Speaker(name, null, it, d.sym, true) }
            val i = name.indexOf('/'); if (i < 0) return null
            val pack = packs.firstOrNull { it.id == name.substring(0, i) } ?: return null
            val id = pack.speakers[name.substring(i + 1)] ?: return null
            return Speaker(name, pack, id, pack.sym, pack.types)
        }

        /** Штатный голос по умолчанию: «xenia», а если её нет в модели — первый по алфавиту. */
        fun default(d: SileroData): Speaker = resolve(DEFAULT, d, emptyList()) ?: resolve(d.speakers.keys.sorted().first(), d, emptyList())!!

        /** Все имена: штатные по алфавиту, затем по пакам. */
        fun names(d: SileroData, packs: List<Pack>): List<String> =
            d.speakers.keys.sorted() + packs.flatMap { p -> p.speakers.keys.sorted().map { packName(p, it) } }

        /** Голоса того же движка, что [main] — для прямой речи: в памяти одна тройка моделей,
         * перегружать 90 МБ на каждую реплику нельзя. */
        fun sameEngine(main: Speaker, d: SileroData, packs: List<Pack>): List<String> =
            main.pack?.let { p -> p.speakers.keys.sorted().map { packName(p, it) } } ?: d.speakers.keys.sorted()

        /** Подпись в списке: «ru_alexandr (cis_ru)», штатные как есть. */
        fun label(name: String): String = name.indexOf('/').let { i -> if (i < 0) name else "${name.substring(i + 1)} (${name.substring(0, i)})" }
    }
}
