package dev.wplayer.app.data

import dev.wplayer.app.data.model.MediaEntry
import dev.wplayer.app.data.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MediaEntryTest {
    @Test
    fun `classifies by extension case-insensitively`() {
        assertEquals(MediaKind.VIDEO, MediaEntry.kindOf("Movie.MKV", isDirectory = false))
        assertEquals(MediaKind.AUDIO, MediaEntry.kindOf("song.flac", isDirectory = false))
        assertEquals(MediaKind.IMAGE, MediaEntry.kindOf("IMG_0001.HEIC", isDirectory = false))
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
    fun `pictures, camera raw files included, are images and not playable`() {
        val names = listOf("a.jpg", "a.jpeg", "a.jpe", "a.png", "a.gif", "a.bmp", "a.ico", "a.webp", "a.heic", "a.heif", "a.avif") +
            listOf("a.dng", "a.arw", "a.cr2", "a.nef", "a.nrw", "a.orf", "a.pef", "a.raf", "a.rw2", "a.srw")
        names.forEach { name ->
            val entry = MediaEntry(name, "/$name", MediaEntry.kindOf(name, isDirectory = false), size = 1, modifiedAtMillis = null)
            assertEquals(name, MediaKind.IMAGE, entry.kind)
            assertFalse(name, entry.isPlayable)
        }
    }

    @Test
    fun `directories win over extensions`() {
        assertEquals(MediaKind.DIRECTORY, MediaEntry.kindOf("Season.1.mkv", isDirectory = true))
    }
}
