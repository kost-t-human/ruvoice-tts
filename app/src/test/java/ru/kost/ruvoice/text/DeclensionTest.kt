package ru.kost.ruvoice.text

import org.junit.Assert.assertEquals
import org.junit.Test

class DeclensionTest {
    private fun c(n: Long, case: Case, feminine: Boolean = false) = Declension.cardinal(n, case, feminine)

    @Test fun briefCases() {
        assertEquals("пятисот", c(500, Case.GEN))
        assertEquals("двадцати одной", c(21, Case.DAT, feminine = true))
        assertEquals("двумя", c(2, Case.INS))
        assertEquals("одной тысяче девятистах семнадцати", c(1917, Case.PRE))
        assertEquals("трёх тысяч", c(3000, Case.GEN))
        assertEquals("одному миллиону", c(1_000_000, Case.DAT))
        assertEquals("сорока", c(40, Case.INS))
        assertEquals("восьмьюдесятью восьмью", c(88, Case.INS))
        assertEquals("нулём", c(0, Case.INS))
        assertEquals("двумстам пятидесяти трём", c(253, Case.DAT))
        assertEquals("одну", c(1, Case.ACC, feminine = true))
        assertEquals("две", c(2, Case.NOM, feminine = true))
        assertEquals("двенадцати тысяч", c(12000, Case.GEN))
    }

    @Test fun zero() {
        assertEquals("ноль", c(0, Case.NOM))
        assertEquals("нуля", c(0, Case.GEN))
        assertEquals("нулю", c(0, Case.DAT))
        assertEquals("ноль", c(0, Case.ACC))
        assertEquals("нуле", c(0, Case.PRE))
    }

    @Test fun oneAndTwoGendered() {
        assertEquals("один", c(1, Case.NOM))
        assertEquals("один", c(1, Case.ACC)) // неодушевлённое: ACC муж. = NOM
        assertEquals("одна", c(1, Case.NOM, feminine = true))
        assertEquals("одной", c(1, Case.INS, feminine = true))
        assertEquals("два", c(2, Case.NOM))
        assertEquals("два", c(2, Case.ACC))
        assertEquals("двух", c(2, Case.GEN))
        assertEquals("двумя", c(2, Case.INS, feminine = true)) // род не влияет на 2 в INS
    }

    @Test fun threeAndFour() {
        assertEquals("три", c(3, Case.NOM))
        assertEquals("трёх", c(3, Case.GEN))
        assertEquals("тремя", c(3, Case.INS))
        assertEquals("четыре", c(4, Case.NOM))
        assertEquals("четырёх", c(4, Case.GEN))
        assertEquals("четырьмя", c(4, Case.INS))
    }

    @Test fun fiveToTwenty() {
        assertEquals("пять", c(5, Case.NOM))
        assertEquals("пяти", c(5, Case.GEN))
        assertEquals("пятью", c(5, Case.INS))
        assertEquals("восьми", c(8, Case.GEN))
        assertEquals("восьмью", c(8, Case.INS))
        assertEquals("одиннадцати", c(11, Case.GEN))
        assertEquals("одиннадцатью", c(11, Case.INS))
        assertEquals("девятнадцати", c(19, Case.DAT))
        assertEquals("двадцати", c(20, Case.GEN))
        assertEquals("двадцатью", c(20, Case.INS))
        assertEquals("тридцати", c(30, Case.GEN))
        assertEquals("тридцатью", c(30, Case.INS))
    }

    @Test fun fortyToHundred() {
        assertEquals("сорок", c(40, Case.NOM))
        assertEquals("сорока", c(40, Case.GEN))
        assertEquals("сорок", c(40, Case.ACC))
        assertEquals("девяносто", c(90, Case.NOM))
        assertEquals("девяноста", c(90, Case.GEN))
        assertEquals("сто", c(100, Case.NOM))
        assertEquals("ста", c(100, Case.GEN))
    }

    @Test fun fiftyToEighty() {
        assertEquals("пятьдесят", c(50, Case.NOM))
        assertEquals("пятидесяти", c(50, Case.GEN))
        assertEquals("пятьюдесятью", c(50, Case.INS))
        assertEquals("шестидесяти", c(60, Case.GEN))
        assertEquals("семидесяти", c(70, Case.GEN))
        assertEquals("восьмидесяти", c(80, Case.GEN))
        assertEquals("восьмьюдесятью", c(80, Case.INS))
    }

    @Test fun hundreds() {
        assertEquals("двести", c(200, Case.NOM))
        assertEquals("двухсот", c(200, Case.GEN))
        assertEquals("двумстам", c(200, Case.DAT))
        assertEquals("двести", c(200, Case.ACC))
        assertEquals("двумястами", c(200, Case.INS))
        assertEquals("двухстах", c(200, Case.PRE))
        assertEquals("триста", c(300, Case.NOM))
        assertEquals("трёхсот", c(300, Case.GEN))
        assertEquals("трёмстам", c(300, Case.DAT))
        assertEquals("тремястами", c(300, Case.INS))
        assertEquals("трёхстах", c(300, Case.PRE))
        assertEquals("четыреста", c(400, Case.NOM))
        assertEquals("четырёхсот", c(400, Case.GEN))
        assertEquals("четырёмстам", c(400, Case.DAT))
        assertEquals("четырьмястами", c(400, Case.INS))
        assertEquals("четырёхстах", c(400, Case.PRE))
        assertEquals("пятьсот", c(500, Case.NOM))
        assertEquals("пятистам", c(500, Case.DAT))
        assertEquals("пятьюстами", c(500, Case.INS))
        assertEquals("пятистах", c(500, Case.PRE))
        assertEquals("шестисот", c(600, Case.GEN))
        assertEquals("семисот", c(700, Case.GEN))
        assertEquals("восьмисот", c(800, Case.GEN))
        assertEquals("восьмьюстами", c(800, Case.INS))
        assertEquals("девятисот", c(900, Case.GEN))
    }

    @Test fun compoundNumbersDeclineEveryWord() {
        assertEquals("двадцати одного", c(21, Case.GEN))
        assertEquals("ста двадцати пяти", c(125, Case.GEN))
    }

    @Test fun thousand() {
        assertEquals("одна тысяча", c(1000, Case.NOM))
        assertEquals("одной тысячи", c(1000, Case.GEN))
        assertEquals("одной тысяче", c(1000, Case.DAT))
        assertEquals("одну тысячу", c(1000, Case.ACC))
        assertEquals("одной тысячей", c(1000, Case.INS))
        assertEquals("одной тысяче", c(1000, Case.PRE))

        assertEquals("две тысячи", c(2000, Case.NOM))
        assertEquals("двух тысяч", c(2000, Case.GEN))
        assertEquals("двум тысячам", c(2000, Case.DAT))
        assertEquals("две тысячи", c(2000, Case.ACC))
        assertEquals("двумя тысячами", c(2000, Case.INS))
        assertEquals("двух тысячах", c(2000, Case.PRE))

        assertEquals("пять тысяч", c(5000, Case.NOM))
        assertEquals("пяти тысяч", c(5000, Case.GEN))
        assertEquals("пяти тысячам", c(5000, Case.DAT))
        assertEquals("пять тысяч", c(5000, Case.ACC))
        assertEquals("пятью тысячами", c(5000, Case.INS))
        assertEquals("пяти тысячах", c(5000, Case.PRE))
    }

    @Test fun millionAndBillion() {
        assertEquals("один миллион", c(1_000_000, Case.NOM))
        assertEquals("одного миллиона", c(1_000_000, Case.GEN))
        assertEquals("одному миллиону", c(1_000_000, Case.DAT))
        assertEquals("одним миллионом", c(1_000_000, Case.INS))
        assertEquals("одном миллионе", c(1_000_000, Case.PRE))

        assertEquals("два миллиона", c(2_000_000, Case.NOM))
        assertEquals("двух миллионов", c(2_000_000, Case.GEN))
        assertEquals("двум миллионам", c(2_000_000, Case.DAT))
        assertEquals("двумя миллионами", c(2_000_000, Case.INS))
        assertEquals("двух миллионах", c(2_000_000, Case.PRE))

        assertEquals("пять миллионов", c(5_000_000, Case.NOM))
        assertEquals("пяти миллионов", c(5_000_000, Case.GEN))
        assertEquals("пяти миллионам", c(5_000_000, Case.DAT))
        assertEquals("пятью миллионами", c(5_000_000, Case.INS))
        assertEquals("пяти миллионах", c(5_000_000, Case.PRE))

        assertEquals("пять миллиардов", c(5_000_000_000, Case.NOM))
        assertEquals("пяти миллиардов", c(5_000_000_000, Case.GEN))
        assertEquals("пятью миллиардами", c(5_000_000_000, Case.INS))
    }

    @Test fun remainderDeclinesLikeStandaloneBelow1000() {
        assertEquals("двумя тысячами пятьюстами", c(2500, Case.INS))
        assertEquals("одного миллиона двухсот пятидесяти тысяч", c(1_250_000, Case.GEN))
    }

    // Declension.cardinal(n, NOM) должен совпадать с Normalizer.cardinal(n) — кроме «тысячи» без «одна».
    @Test fun matchesNormalizerInNominative() {
        for (n in listOf(5L, 21L, 100L, 1917L, 2_000_000L)) {
            assertEquals(Normalizer.cardinal(n), Declension.cardinal(n, Case.NOM))
        }
    }
}
