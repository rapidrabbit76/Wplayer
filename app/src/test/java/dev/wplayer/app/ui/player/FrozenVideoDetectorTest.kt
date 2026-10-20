package dev.wplayer.app.ui.player

import dev.wplayer.app.data.model.PlaybackSettings
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
        assertEquals(VideoPath.ZERO_COPY, VideoPath.from(PlaybackSettings()))
        assertEquals(VideoPath.DIRECT, VideoPath.from(PlaybackSettings(directVideoOutput = true)))
        assertEquals(VideoPath.COPY, VideoPath.from(PlaybackSettings(zeroCopyFroze = true)))
        assertEquals(VideoPath.SOFTWARE, VideoPath.from(PlaybackSettings(hardwareDecoding = false, directVideoOutput = true)))
    }
}
