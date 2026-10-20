package dev.wplayer.app.data

import dev.wplayer.app.data.model.MediaEntry
import dev.wplayer.app.data.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaEntryTest {
    @Test
    fun `classifies by extension case-insensitively`() {
        assertEquals(MediaKind.VIDEO, MediaEntry.kindOf("Movie.MKV", isDirectory = false))
        assertEquals(MediaKind.AUDIO, MediaEntry.kindOf("song.flac", isDirectory = false))
        assertEquals(MediaKind.SUBTITLE, MediaEntry.kindOf("Movie.smi", isDirectory = false))
        assertEquals(MediaKind.OTHER, MediaEntry.kindOf("notes.txt", isDirectory = false))
        assertEquals(MediaKind.OTHER, MediaEntry.kindOf("README", isDirectory = false))
    }

    @Test
    fun `directories win over extensions`() {
        assertEquals(MediaKind.DIRECTORY, MediaEntry.kindOf("Season.1.mkv", isDirectory = true))
    }
}
