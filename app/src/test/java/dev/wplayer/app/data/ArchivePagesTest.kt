package dev.wplayer.app.data

import dev.wplayer.core.ffi.ArchiveEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArchivePagesTest {
    private fun entry(path: String, index: Int) = ArchiveEntry(path, size = null, index = index.toUInt())

    /** Pages in page order, stored in the archive in the same order. */
    private val book = (0 until 6).map { entry("원피스/%03d.jpg".format(it + 1), it) }

    @Test
    fun `pages keep the core's page order and are named by file`() {
        val entries = listOf(entry("표지.jpg", 4), entry("1화/2.jpg", 0), entry("1화/10.jpg", 3), entry("2화/2.jpg", 1))
        val pages = archivePages(entries)
        assertEquals(listOf("표지.jpg", "2.jpg", "10.jpg", "2.jpg"), pages.map { it.name })
        assertEquals(listOf("표지.jpg", "1화/2.jpg", "1화/10.jpg", "2화/2.jpg"), pages.map { it.path })
    }

    @Test
    fun `the page on screen is read first, even behind the last read`() {
        assertEquals(1, nextArchivePage(book, wanted = setOf(1, 2, 3), current = 1, lastRead = 4u))
    }

    @Test
    fun `then pages ahead are read before the one behind`() {
        // Opened at page 3 (index 2): the pager also wants pages 2 and 4, and 5 is fetched ahead.
        val read = mutableListOf<Int>()
        val wanted = mutableSetOf(1, 2, 3, 4)
        var lastRead: UInt? = null
        while (true) {
            val next = nextArchivePage(book, wanted, current = 2, lastRead) ?: break
            read += next
            wanted -= next
            lastRead = book[next].index
        }
        assertEquals(listOf(2, 3, 4, 1), read)
    }

    @Test
    fun `pages stored out of order are read in archive order`() {
        val shuffled = listOf(entry("1.jpg", 2), entry("2.jpg", 0), entry("3.jpg", 1))
        assertEquals(1, nextArchivePage(shuffled, wanted = setOf(0, 1, 2), current = 5, lastRead = null))
        assertEquals(2, nextArchivePage(shuffled, wanted = setOf(0, 2), current = 5, lastRead = 0u))
        assertEquals(0, nextArchivePage(shuffled, wanted = setOf(0), current = 5, lastRead = 1u))
    }

    @Test
    fun `nothing wanted, nothing to read`() {
        assertNull(nextArchivePage(book, wanted = emptySet(), current = 0, lastRead = null))
    }
}
