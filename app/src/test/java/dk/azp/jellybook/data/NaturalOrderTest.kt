package dk.azp.jellybook.data

import org.junit.Assert.assertEquals
import org.junit.Test

class NaturalOrderTest {

    @Test
    fun numbersWithoutPaddingSortByValue() {
        val names = listOf("10.mp3", "2.mp3", "1.mp3", "12.mp3", "3.mp3")

        assertEquals(listOf("1.mp3", "2.mp3", "3.mp3", "10.mp3", "12.mp3"), names.sortedWith(NaturalOrder))
    }

    @Test
    fun paddedNumbersKeepTheirOrder() {
        val names = (1..12).map { "Book - %03d.mp3".format(it) }

        assertEquals(names, names.shuffled().sortedWith(NaturalOrder))
    }

    @Test
    fun leadingZeroesDoNotChangeValue() {
        assertEquals(0, NaturalOrder.compare("part007.mp3", "part7.mp3"))
    }

    @Test
    fun textAroundTheNumberStillDecidesFirst() {
        val names = listOf("b1.mp3", "a2.mp3", "a10.mp3")

        assertEquals(listOf("a2.mp3", "a10.mp3", "b1.mp3"), names.sortedWith(NaturalOrder))
    }

    @Test
    fun caseIsIgnored() {
        assertEquals(0, NaturalOrder.compare("Chapter 3.mp3", "chapter 3.mp3"))
    }

    @Test
    fun aPrefixSortsBeforeTheLongerName() {
        assertEquals(listOf("part1.mp3", "part1extra.mp3"), listOf("part1extra.mp3", "part1.mp3").sortedWith(NaturalOrder))
    }

    @Test
    fun runsOfDigitsTooLongForAnIntAreStillCompared() {
        val names = listOf("x99999999999999999999.mp3", "x100000000000000000000.mp3")

        assertEquals(listOf("x99999999999999999999.mp3", "x100000000000000000000.mp3"), names.sortedWith(NaturalOrder))
    }

    @Test
    fun multipleNumberGroupsAreComparedInTurn() {
        val names = listOf("d2t10.mp3", "d1t2.mp3", "d2t2.mp3", "d1t10.mp3")

        assertEquals(listOf("d1t2.mp3", "d1t10.mp3", "d2t2.mp3", "d2t10.mp3"), names.sortedWith(NaturalOrder))
    }
}
