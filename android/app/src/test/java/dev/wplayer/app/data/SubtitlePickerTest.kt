package dev.wplayer.app.data

import dev.wplayer.app.data.model.MediaEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitlePickerTest {
    private fun entry(name: String, isDirectory: Boolean = false) =
        MediaEntry(name, "/v/$name", MediaEntry.kindOf(name, isDirectory), size = 0, modifiedAtMillis = null)

    @Test
    fun `lists folders first, then subtitles, in natural order`() {
        val entries = listOf(
            entry("Show.E10.srt"), entry("Show.E2.srt"), entry("Show.E2.mkv"), entry("notes.txt"),
            entry("Extras", isDirectory = true), entry("Season 10", isDirectory = true), entry("Season 2", isDirectory = true),
        )
        assertEquals(
            listOf("Extras", "Season 2", "Season 10", "Show.E2.srt", "Show.E10.srt"),
            subtitlePickerEntries(entries).map { it.name },
        )
    }

    @Test
    fun `leaves out hidden files and server folders`() {
        val entries = listOf(entry(".Show.srt"), entry("@eaDir", isDirectory = true), entry("Show.ass"))
        assertEquals(listOf("Show.ass"), subtitlePickerEntries(entries).map { it.name })
    }

    @Test
    fun `image subtitles are not re-encoded`() {
        assertTrue(entry("Movie.SMI").isTextSubtitle())
        assertTrue(entry("Movie.vtt").isTextSubtitle())
        assertFalse(entry("Movie.sup").isTextSubtitle())
        assertFalse(entry("Movie.idx").isTextSubtitle())
    }
}
