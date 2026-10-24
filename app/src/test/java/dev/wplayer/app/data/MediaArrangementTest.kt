package dev.wplayer.app.data

import dev.wplayer.app.data.model.BrowserSettings
import dev.wplayer.app.data.model.MediaEntry
import dev.wplayer.app.data.model.MediaKind
import dev.wplayer.app.data.model.SortField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaArrangementTest {
    private fun entry(name: String, size: Long = 0, modified: Long? = null, dir: Boolean = false) =
        MediaEntry(name, "/$name", MediaEntry.kindOf(name, dir), size, modified)

    private val listing = listOf(
        entry("b.mkv", size = 30, modified = 300),
        entry("Season 10", dir = true),
        entry("a.mp4", size = 20, modified = 100),
        entry("Season 2", dir = true),
        entry("a.smi"),
        entry("notes.txt"),
        entry(".hidden.mkv"),
        entry("c.avi", size = 10, modified = 200),
    )

    @Test
    fun `default shows folders first in natural order and hides subtitles, other and hidden files`() {
        val names = arrange(listing, BrowserSettings()).map { it.name }
        assertEquals(listOf("Season 2", "Season 10", "a.mp4", "b.mkv", "c.avi"), names)
    }

    @Test
    fun `font files show while other files are hidden, so they can be added as subtitle fonts`() {
        val fonts = listOf(entry("DX영화자막Medium.ttf"), entry("Nanum.OTF"), entry("notes.txt"))
        assertEquals(listOf("DX영화자막Medium.ttf", "Nanum.OTF"), arrange(fonts, BrowserSettings()).map { it.name })
    }

    @Test
    fun `pictures show like media while other files stay hidden`() {
        val files = listOf(entry("photo.jpg"), entry("scan.PNG"), entry("notes.txt"), entry("a.mp4"))
        assertEquals(listOf("a.mp4", "photo.jpg", "scan.PNG"), arrange(files, BrowserSettings()).map { it.name })
    }

    @Test
    fun `descending size keeps folders on top`() {
        val names = arrange(listing, BrowserSettings(sortField = SortField.SIZE, ascending = false)).map { it.name }
        assertEquals(listOf("Season 10", "Season 2", "b.mkv", "a.mp4", "c.avi"), names)
    }

    @Test
    fun `date order without folders first mixes folders in`() {
        val names = arrange(listing, BrowserSettings(sortField = SortField.DATE, foldersFirst = false)).map { it.name }
        assertEquals(listOf("Season 2", "Season 10", "a.mp4", "c.avi", "b.mkv"), names)
    }

    @Test
    fun `extension order groups by type`() {
        val names = arrange(listing, BrowserSettings(sortField = SortField.EXTENSION)).filter { it.kind != MediaKind.DIRECTORY }.map { it.name }
        assertEquals(listOf("c.avi", "b.mkv", "a.mp4"), names)
    }

    @Test
    fun `filters can reveal subtitle, other and hidden files`() {
        val shown = arrange(
            listing,
            BrowserSettings(showSubtitleFiles = true, showOtherFiles = true, showHiddenFiles = true),
        ).map { it.name }.toSet()
        assertEquals(listing.map { it.name }.toSet(), shown)
    }

    @Test
    fun `name search ignores case and Hangul normalization`() {
        val decomposed = java.text.Normalizer.normalize("눈물의 여왕 E01.mkv", java.text.Normalizer.Form.NFD)
        assertTrue(nameMatches(decomposed, "여왕"))
        assertTrue(nameMatches("Queen.Of.Tears.E01.MKV", "of.tears"))
        assertFalse(nameMatches("선재 업고 튀어.mkv", "여왕"))
    }
}
