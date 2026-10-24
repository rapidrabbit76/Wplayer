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
    fun `lists the audio formats nPlayer played`() {
        val nPlayerAudio = "a52 aac ac3 aif aifc aiff amr ape dts flac m4a mka mp1 mp2 mp3 m4b mpc oga ogg oma ra tta voc wav wma wv"
        nPlayerAudio.split(' ').forEach { extension ->
            assertEquals(extension, MediaKind.AUDIO, MediaEntry.kindOf("track.$extension", isDirectory = false))
        }
    }

    @Test
    fun `directories win over extensions`() {
        assertEquals(MediaKind.DIRECTORY, MediaEntry.kindOf("Season.1.mkv", isDirectory = true))
    }
}
