package ru.kost.ruvoice

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.kost.ruvoice.text.Replacements
import ru.kost.ruvoice.text.Rules
import ru.kost.ruvoice.text.Segment

class PipelineTest {
    private val d = TestData.data()

    @Test fun plainTextToSentencesWithPauses() {
        val s = Pipeline.plan("Раз. Два!\nТри?", d, sentencePauseMs = 100, paragraphPauseMs = 300)
        assertEquals(listOf(
            Segment("Раз.", breakMs = 100), Segment("Два!", breakMs = 400, paragraph = true), Segment("Три?", breakMs = 100)
        ), s)
    }

    @Test fun ssmlKeepsProsodyPerSentence() {
        val s = Pipeline.plan("<speak><prosody rate=\"fast\">Раз. Два.</prosody><break time=\"1s\"/></speak>", d, 0, 300)
        assertEquals(listOf(Segment("{prosody:120:100}Раз."), Segment("{prosody:120:100}Два.", breakMs = 1000)), s)
    }

    @Test fun prosodyCarriedUntilReset() {
        val s = Pipeline.plan("<speak><prosody rate=\"fast\">Раз. Два.</prosody> Три. Четыре.</speak>", d, 0, 0)
        assertEquals(listOf(Segment("{prosody:120:100}Раз."), Segment("{prosody:120:100}Два."), Segment("{prosody}Три."), Segment("Четыре.")), s)
    }

    @Test fun prosodyBeforeSpeechDash() {
        val s = Pipeline.plan("<speak><prosody rate=\"fast\">— Привет, — сказал он.</prosody></speak>", d, 0, 0)
        assertEquals(listOf(Segment("{prosody:120:100}— Привет,", speech = true), Segment("{prosody:120:100}сказал он.")), s)
    }

    @Test fun pauseMarkerInPlainTextBecomesBreak() {
        // маркер прямо в исходном тексте, без словаря замен
        val s = Pipeline.plan("Раз.{pause:700}Два.", d, sentencePauseMs = 0, paragraphPauseMs = 300)
        assertEquals(listOf(Segment("Раз.", breakMs = 700), Segment("Два.")), s)
    }

    @Test fun pauseMarkerInsideSentenceStaysInText() {
        // внутри предложения маркер не режет — Marks сделает из него запятую заданной длины
        val s = Pipeline.plan("Он ушёл{pause:500}и всё. Два.", d, sentencePauseMs = 0, paragraphPauseMs = 0)
        assertEquals(listOf(Segment("Он ушёл{pause:500}и всё."), Segment("Два.")), s)
        val r = Pipeline.plan("Он ушёл {pause:500} и всё.", d, sentencePauseMs = 0, paragraphPauseMs = 0)
        assertEquals(listOf(Segment("Он ушёл {pause:500} и всё.")), r)
    }

    @Test fun pauseMarkerAtStartProducesEmptyLeadingSegment() {
        val s = Pipeline.plan("{pause:300}Раз.", d, sentencePauseMs = 0, paragraphPauseMs = 0)
        assertEquals(listOf(Segment("", breakMs = 300), Segment("Раз.")), s)
    }

    @Test fun twoConsecutivePauseMarkersSum() {
        val s = Pipeline.plan("Раз.{pause:200}{pause:300}Два.", d, sentencePauseMs = 0, paragraphPauseMs = 0)
        assertEquals(listOf(Segment("Раз.", breakMs = 500), Segment("Два.")), s)
    }

    @Test fun pauseMarkerThroughReplacement() {
        val r = Replacements.parse(listOf("*** = {pause:800}"))
        val s = Pipeline.plan("Раз. *** Два.", d, sentencePauseMs = 0, paragraphPauseMs = 0, replacements = r)
        assertEquals(listOf(Segment("Раз.", breakMs = 800), Segment("Два.")), s)
    }

    @Test fun pauseMarkerOverflowClampedTo10000() {
        val s = Pipeline.plan("Раз.{pause:99999}Два.", d, sentencePauseMs = 0, paragraphPauseMs = 0)
        assertEquals(listOf(Segment("Раз.", breakMs = 10000), Segment("Два.")), s)
    }

    @Test fun severalSentencesInPieceBeforeMarker() {
        val s = Pipeline.plan("Раз. Два.{pause:500}Три.", d, sentencePauseMs = 0, paragraphPauseMs = 0)
        assertEquals(listOf(Segment("Раз."), Segment("Два.", breakMs = 500), Segment("Три.")), s)
    }

    @Test fun pauseMarkerAtEndOfSegmentDoesNotDuplicateText() {
        // финальный fix-раунд п.4: пустой кусок после {pause:N} в конце сегмента раньше добавлял
        // весь исходный сегмент (с текстом и неразобранным маркером) ещё раз.
        val s = Pipeline.plan("<speak>Привет.{pause:300}<break time=\"200ms\"/>Пока.</speak>", d, 0, 0)
        assertEquals(1, s.count { it.text == "Привет." })
    }

    @Test fun pauseMarkerAtParagraphEndKeepsParagraphPause() {
        // финальный fix-раунд п.11: {pause:N} на конце абзаца — маркер про паузу предложения
        // (ruling), но пауза абзаца и флаг paragraph должны сохраниться, а не потеряться.
        val s = Pipeline.plan("Раз.{pause:300}\n\nДва.", d, sentencePauseMs = 100, paragraphPauseMs = 500)
        assertEquals(listOf(Segment("Раз.", breakMs = 800, paragraph = true), Segment("Два.", breakMs = 100)), s)
    }

    @Test fun directSpeechDashAndQuoteDetected() {
        // Splitter режет по «!»; «— сказал он.» — тире + строчная, это автор, не реплика.
        // ««Тише», — шепнул кто-то.» — реплика и авторская часть отдельными сегментами.
        val s = Pipeline.plan("Он вошёл. — Привет! — сказал он. «Тише», — шепнул кто-то.", d, 0, 0)
        assertEquals(listOf("Он вошёл.", "— Привет!", "— сказал он.", "«Тише»,", "шепнул кто-то."), s.map { it.text })
        assertEquals(listOf(false, true, false, true, false), s.map { it.speech })
    }

    @Test fun authorInsertInsideDirectSpeech() {
        // «— Пойдём, — сказал он, — нам пора.» → реплика / автор / реплика; пауза предложения и
        // флаг абзаца — только у последнего куска.
        val s = Pipeline.plan("— Пойдём, — сказал он, — нам пора.\n\nОн встал.", d, sentencePauseMs = 100, paragraphPauseMs = 500)
        assertEquals(listOf("— Пойдём,", "сказал он,", "нам пора.", "Он встал."), s.map { it.text })
        assertEquals(listOf(true, false, true, false), s.map { it.speech })
        assertEquals(listOf(0, 0, 600, 100), s.map { it.breakMs })
        assertEquals(listOf(false, false, true, false), s.map { it.paragraph })
    }

    @Test fun dashInsideDirectSpeechIsNotAuthorInsert() {
        // Перед авторской вставкой стоит знак («— Пойдём, — сказал»), перед тире внутри речи — нет.
        val s = Pipeline.plan("— Нам пора — уже поздно. — Да? — удивился он. — Ну — пошли.", d, 0, 0)
        assertEquals(listOf("— Нам пора — уже поздно.", "— Да?", "— удивился он.", "— Ну — пошли."), s.map { it.text })
        assertEquals(listOf(true, true, false, true), s.map { it.speech })
    }

    @Test fun replyContinuesAcrossSentencesInParagraph() {
        // Реплика из нескольких предложений в одном абзаце: второе без тире — продолжение речи,
        // авторский хвост рвёт цепочку, дальше снова автор. Новый абзац цепочку не наследует.
        val s = Pipeline.plan("— Привет. Как дела? — сказал он. Потом ушёл.\n\nНастала тишина.", d, 0, 0)
        assertEquals(listOf("— Привет.", "Как дела?", "— сказал он.", "Потом ушёл.", "Настала тишина."), s.map { it.text })
        assertEquals(listOf(true, true, false, false, false), s.map { it.speech })
        // Кавычки: закрывающая «»» кончает реплику.
        val q = Pipeline.plan("«Привет. Как дела?» — спросил он. Потом ушёл.", d, 0, 0)
        assertEquals(listOf(true, true, false, false), q.map { it.speech })
    }

    @Test fun replyWithoutDashDetectedByAuthorTail() {
        // Читалка отдаёт по предложению: «Как дела? — спросил он.» приходит без начала реплики.
        // Авторская вставка (знак + « — » + строчная) или авторский хвост следом — признак речи.
        assertEquals(listOf(true, false), Pipeline.plan("Как дела? — спросил он.", d, 0, 0).map { it.speech })
        assertEquals(listOf(true, false, true), Pipeline.plan("Как дела, — спросил он, — всё хорошо?", d, 0, 0).map { it.speech })
        assertEquals(listOf(false, false), Pipeline.plan("Он вышел. Дверь хлопнула.", d, 0, 0).map { it.speech })
    }

    @Test fun dashInsideWordIsNotSpeech() {
        val s = Pipeline.plan("Дефис-внутри слова.", d, 0, 0)
        assertEquals(listOf(false), s.map { it.speech })
    }

    @Test fun ssmlDirectSpeechDetectedBySameCriterion() {
        val s = Pipeline.plan("<speak>— Реплика.</speak>", d, 0, 0)
        assertEquals(listOf(true), s.map { it.speech })
    }

    // TalkBack при посимвольной навигации/эхе ввода шлёт запрос из одной буквы — читаем её по имени.
    @Test fun loneLetterRequestIsSpelled() {
        fun first(t: String, rules: Rules = Rules()) = Pipeline.plan(t, d, 0, 0, rules = rules).single().text
        assertEquals("б+э", first("б"))
        assertEquals("б+э", first(" Б. "))
        assertEquals("прописная буква в+э", first("прописная буква В."))
        assertEquals("м+ягкий знак", first("ь"))
        assertEquals("б+и", first("b"))
        assertEquals("в+э", first("в"))
        // внутри текста «в» — предлог, не трогаем
        assertEquals("в 1917 году.", first("в 1917 году."))
        assertEquals("б", first("б", Rules(off = setOf("letter_name"))))
    }
    @Test fun fastStartCutsOnlyFirstSegment() {
        val long = "Поздним вечером старый смотритель запер тяжёлые ворота, пошёл вдоль стены, а потом долго стоял у окна и смотрел, как гаснут огни в деревне за рекой, где его никто не ждал."
        val text = "$long\n$long"
        val off = Pipeline.plan(text, d, 100, 300)
        assertEquals(2, off.size)
        val on = Pipeline.plan(text, d, 100, 300, rules = Rules(off = setOf("fast_start")))
        assertEquals(3, on.size)
        assertEquals(Segment("Поздним вечером старый смотритель запер тяжёлые ворота, пошёл вдоль стены"), on[0])
        assertEquals(Segment(long.substring(on[0].text.length + 2), breakMs = 400, paragraph = true), on[1])
        assertEquals(off[1], on[2])
    }

    @Test fun fastStartLeavesShortFirstSegment() {
        val on = Pipeline.plan("Раз. Два!", d, 100, 0, rules = Rules(off = setOf("fast_start")))
        assertEquals(listOf(Segment("Раз.", breakMs = 100), Segment("Два!", breakMs = 100)), on)
    }

}
