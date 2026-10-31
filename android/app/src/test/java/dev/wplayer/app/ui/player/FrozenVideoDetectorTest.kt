package dev.wplayer.app.ui.player

import dev.wplayer.app.data.model.AppSettings
import dev.wplayer.app.data.model.PlaybackSettings
import dev.wplayer.app.data.model.ScreenSettings
import dev.wplayer.app.data.model.VideoEnhancement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrozenVideoDetectorTest {
    @Test
    fun `frames that stop while playing are a freeze`() {
        val detector = FrozenVideoDetector(limitSeconds = 4.0)
        assertFalse(detector.update(seconds = 10.0, presentedFrames = 5, playing = true))
        assertFalse(detector.update(seconds = 12.0, presentedFrames = 5, playing = true))
        assertTrue(detector.update(seconds = 14.0, presentedFrames = 5, playing = true))
    }

    @Test
    fun `frames that keep coming are not`() {
        val detector = FrozenVideoDetector(limitSeconds = 4.0)
        (0..10).forEach { second ->
            assertFalse(detector.update(seconds = second.toDouble(), presentedFrames = second * 24L, playing = true))
        }
    }

    @Test
    fun `a pause starts the wait over`() {
        val detector = FrozenVideoDetector(limitSeconds = 4.0)
        detector.update(seconds = 10.0, presentedFrames = 5, playing = true)
        detector.update(seconds = 13.0, presentedFrames = 5, playing = true)
        assertFalse(detector.update(seconds = 20.0, presentedFrames = 5, playing = false))
        assertFalse(detector.update(seconds = 21.0, presentedFrames = 5, playing = true))
        assertFalse(detector.update(seconds = 24.0, presentedFrames = 5, playing = true))
        assertTrue(detector.update(seconds = 25.0, presentedFrames = 5, playing = true))
    }

    @Test
    fun `settings choose the video path`() {
        fun path(playback: PlaybackSettings, enhancement: VideoEnhancement = VideoEnhancement.OFF) =
            VideoPath.from(AppSettings(playback = playback, screen = ScreenSettings(enhancement = enhancement)))
        assertEquals(VideoPath.ZERO_COPY, path(PlaybackSettings()))
        assertEquals(VideoPath.DIRECT, path(PlaybackSettings(directVideoOutput = true)))
        assertEquals(VideoPath.COPY, path(PlaybackSettings(zeroCopyFroze = true)))
        assertEquals(VideoPath.SOFTWARE, path(PlaybackSettings(hardwareDecoding = false, directVideoOutput = true)))
        // Zero-copy frames arrive as RGB, without the planes the enhancement shaders work on.
        assertEquals(VideoPath.COPY, path(PlaybackSettings(), VideoEnhancement.SHARP))
        assertEquals(VideoPath.DIRECT, path(PlaybackSettings(directVideoOutput = true), VideoEnhancement.SHARP))
    }
}
