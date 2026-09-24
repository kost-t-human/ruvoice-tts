package ru.kost.ruvoice.text

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Страница форума через TalkBack (тема RuVoice на 4PDA): заголовки сообщений, ники, модели телефонов, имена
 * файлов, подписи. Всё — полным prepare(), как уходит в модель; [sr] — с правилами экранного чтеца.
 */
class ForumTextTest {
    private val allowed = "абвгдеёжзийклмнопрстуфхцчшщъыьэюя .,!?-–—:;«»()'\"́+"
    private fun p(s: String) = Normalizer.prepare(s, allowed)
    private fun sr(s: String) = Normalizer.prepare(s, allowed, Rules().screenReader())

    @Test fun postHeader() {
        // дата с годом из двух цифр, время с нулём в минутах
        assertEquals("двадцатого сентября двадцать шестого года, восемнадцать ноль шесть", p("20.09.26, 18:06"))
        assertEquals("вчера, шестнадцать ноль ноль", p("Вчера, 16:00"))
        assertEquals("регистрация: пятого января двадцатого года", p("Регистрация: 05.01.20"))
        // версия — не дата: день «0», число из одной цифры, латиница перед
        assertEquals("версия ноль точка четырнадцать точка десять", p("Версия 0.14.10"))
        assertEquals("иоанна три шестнадцать", p("Иоанна 3:16"))
        // сокращённый месяц
        assertEquals("двадцать второго сентября две тысячи двадцать шестого года в девятнадцать часов", p("22 сент. 2026 г. в 19:00"))
        assertEquals("встреча третье декабря.", p("Встреча 3 дек."))
    }

    @Test fun latinAtDigits() {
        assertEquals("четыре пи ди эй", p("4PDA"))
        assertEquals("официальная группа четыре пи ди эй", p("Официальная группа 4pda"))
        assertEquals("редми ноут тринадцать про", p("Redmi Note 13pro"))
        assertEquals("самсунг галакси эс двадцать фе", p("Samsung Galaxy S20 FE"))
        assertEquals("поко экс три про", p("POCO X3 Pro"))
        assertEquals("процессор ай семь", p("процессор i7"))
        // латиница вплотную к русскому слову — два слова
        assertEquals("прикрепленный файл майкрософт", p("Прикрепленный файлMicrosoft"))
        // опечатка раскладки — как раньше
        assertEquals("проблема", p("прoблема"))
    }

    @Test fun camelCase() {
        assertEquals("эдж ти ти +эс", p("EdgeTTS"))
        assertEquals("мун ридер", p("MoonReader"))
        assertEquals("фокси бук", p("FoxyBook"))
        assertEquals("ру войс ти ти +эс", p("RuVoice TTS"))
        // слова из словаря и приставка из одной строчной — целиком
        assertEquals("айфон и ютуб", p("iPhone и YouTube"))
        assertEquals("минус четырнадцать дб", p("-14 dB"))
        // одиночная заглавная — названием буквы, «P.S.» — сокращение
        assertEquals("ви", p("V"))
        assertEquals("п.с. привет.", p("P.S. Привет."))
    }

    @Test fun files() {
        assertEquals("ру войс-пак-ру точка зип", p("ruvoice-pack-ru.zip"))
        assertEquals("бриан точка вав", p("Brian.wav"))
        // число перед расширением — не «одна точка»
        assertEquals("один точка эм пэ четыре", p("1.mp4"))
        assertEquals("тэ икс тэ файл", p("txt файл"))
    }

    @Test fun signsAndLines() {
        // подпись под чертой: черта у края — ничего, между словами — пауза
        assertEquals("спасибо. редми ноут тринадцать", p("Спасибо. -------------------- Redmi Note 13"))
        assertEquals("спасибо.", p("Спасибо. --------------------"))
        assertEquals("глава – конец.", p("Глава ---- конец."))
        // стрелки из знаков
        assertEquals("проверка – кнопка", p("Проверка -> кнопка"))
        assertEquals("проверка стрелка вправо кнопка", sr("Проверка -> кнопка"))
        assertEquals("меню стрелка вправо настройки", sr("Меню >> Настройки"))
        // «+» не перед гласной — «плюс»; ударение не трогаем
        assertEquals("мун плюс ридер", p("Moon+ Reader"))
        assertEquals("си плюс плюс", p("C++"))
        assertEquals("моего д+ома", p("моего д+ома"))
        // пустые паузы подряд — одна
        assertEquals("лоудинг, пятьдесят процентов", p("Loading…[][][][]50%"))
        assertEquals("внутри, или", p("внутри)) Или"))
    }

    @Test fun smileys() {
        assertEquals("шутка, улыбается, и ещё, подмигивает", p("Шутка :) и ещё ;-)"))
        assertEquals("соберу, смеётся", p("соберу :D"))
        // время со скобкой — не смайл
        assertEquals("в три:", p("В 3:)"))
    }

    @Test fun numbers() {
        assertEquals("стало четырнадцать тысяч тридцать три вместо", p("стало 14 033 вместо"))
        // «1 200 читателей» — по-прежнему два числа
        assertEquals("итого один двести читателей.", p("Итого 1 200 читателей."))
        assertEquals("цена двадцать тысяч", p("Цена 20к"))
        assertEquals("видео в четыре кей.", p("Видео в 4K."))
    }

    @Test fun precomposedStress() {
        // ударение готовой буквой со знаком: раньше буква выпадала целиком («глза», «слва»)
        assertEquals("гл+аза левого", p("Глáза левого"))
        assertEquals("сл+ова сочувствия", p("Слóва сочувствия"))
        assertEquals("письм+а от брата", p("Письмá от брата"))
        assertEquals("рук+и его", p("Рукѝ его"))
        assertEquals("рук+и его", p("Рукѝ его"))
        // «café» — французское слово, не ударение
        assertEquals("кафе", p("кафе"))
    }

    @Test fun words() {
        assertEquals("обновление для токбэк", p("Обновление для TalkBack"))
        assertEquals("смарт хуавэй", p("смарт Huawei"))
        assertEquals("хранение в эс кью лайт", p("Хранение в sqlite"))
        assertEquals("эпистем", p("Episteme"))
    }
}
