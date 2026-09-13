package ru.kost.ruvoice.text

import org.junit.Assert.assertEquals
import org.junit.Test

/** Примеры из assets/regex_help.html должны давать ровно то, что обещает справка. */
class RegexHelpExamplesTest {
    private fun run(key: String, value: String, text: String) =
        Replacements.parse(listOf("~$key = $value")).apply(text)

    @Test fun examplesFromHelp() {
        assertEquals("глава 12", run("""\bгл\.\s*(\d+)""", "глава $1", "гл. 12"))
        assertEquals("5 штук и 12 штук", run("""(\d+)\s*шт\.?""", "$1 штук", "5 шт. и 12шт"))
        assertEquals("стр. 5 по 7", run("""(\d+)-(\d+)""", "$1 по $2", "стр. 5-7"))
        assertEquals("скидка 20 процентов", run("""(\d+)\s*%""", "$1 процентов", "скидка 20%"))
        assertEquals("90 километров в час", run("""(\d+)\s*км/ч""", "$1 километров в час", "90 км/ч"))
        assertEquals("Санкт-Петербург", run("""\bСПб\b""", "Санкт-Петербург", "СПб"))
        assertEquals("айкью 140", run("""\bIQ\b""", "айкью", "IQ 140"))
        assertEquals("Г+арри и Г+аррю", run("""\bГарр(и|ю)\b""", "Г+арр$1", "Гарри и Гаррю"))
        assertEquals("у з+амка, в з+амке", run("""\bзамк(а|у|ом|е)\b""", "з+амк$1", "у замка, в замке"))
        assertEquals("на берег+у, я берегу", run("""\b(на|у)\s+берегу\b""", "$1 берег+у", "на берегу, я берегу"))
        assertEquals("Б+ог и бог войны", run("""(?-i)\bБог\b""", "Б+ог", "Бог и бог войны"))
        assertEquals("текст дальше", run("""\(прим\. (ред|пер)\.\)""", "", "текст (прим. ред.) дальше"))
        assertEquals("текст дальше", run("""\([^)]*\)""", "", "текст (сноска) дальше"))
        assertEquals("а {pause:800} б", run("""\*{3,}""", "{pause:800}", "а *** б"))
        assertEquals("текст\n\nдальше", run("""(?m)^\s*\d+\s*$""", "", "текст\n 42 \nдальше"))
        assertEquals("см. ссылка", run("""https?://\S+""", "ссылка", "см. https://example.com/page"))
        assertEquals("Не-е-ет!", run("""([аеиоуыэюя])\1{2,}""", "$1-$1-$1", "Нееет!"))
        assertEquals("ок ок ок", run("""[:;]-?[)(]+""", "", "ок :) ок ;-) ок :(("))
        assertEquals("мистер Smith", run("""\bMr\.""", "мистер", "Mr. Smith"))
        assertEquals("три дэ", run("""\b3D\b""", "три дэ", "3D"))
        assertEquals("зам+ок висел, замок стоял", run("""замок(?=\s+висел)""", "зам+ок", "замок висел, замок стоял"))
    }
}
