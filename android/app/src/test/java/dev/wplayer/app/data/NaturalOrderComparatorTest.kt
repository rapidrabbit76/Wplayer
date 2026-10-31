package dev.wplayer.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class NaturalOrderComparatorTest {
    @Test
    fun `episode numbers sort by value`() {
        val names = listOf("드라마 10화.mkv", "드라마 2화.mkv", "드라마 1화.mkv", "드라마 02화.mkv")
        assertEquals(
            listOf("드라마 1화.mkv", "드라마 2화.mkv", "드라마 02화.mkv", "드라마 10화.mkv"),
            names.sortedWith(NaturalOrderComparator),
        )
    }

    @Test
    fun `case is ignored and shorter prefix sorts first`() {
        assertEquals(listOf("a", "B", "b2"), listOf("b2", "B", "a").sortedWith(NaturalOrderComparator))
    }

    @Test
    fun `season and episode markers sort naturally`() {
        val names = listOf("Show.S01E10.mkv", "Show.S01E09.mkv", "Show.S02E01.mkv")
        assertEquals(
            listOf("Show.S01E09.mkv", "Show.S01E10.mkv", "Show.S02E01.mkv"),
            names.sortedWith(NaturalOrderComparator),
        )
    }
}
