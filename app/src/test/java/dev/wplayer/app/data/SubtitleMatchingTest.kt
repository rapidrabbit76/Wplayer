package dev.wplayer.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleMatchingTest {
    @Test
    fun `matches only the video's own subtitles`() {
        val names = listOf(
            "Show.E1.mkv", "Show.E1.srt", "show.e1.SMI", "Show.E10.srt", "Show.E1 extra.srt", "Show.E1.nfo",
        )
        assertEquals(listOf("show.e1.SMI", "Show.E1.srt"), matchSubtitles("Show.E1.mkv", names))
    }

    @Test
    fun `korean tagged subtitles come first`() {
        val names = listOf("Movie.en.srt", "Movie.ko.srt", "Movie.ass")
        assertEquals(listOf("Movie.ko.srt", "Movie.ass", "Movie.en.srt"), matchSubtitles("Movie.mkv", names))
    }
}
