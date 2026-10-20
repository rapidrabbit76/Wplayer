package dev.wplayer.core.player

import dev.wplayer.core.ffi.PropertyValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackStateTest {
    @Test
    fun `applies typed mpv properties`() {
        val state = PlaybackState()
            .with("time-pos", PropertyValue.Double(12.5))
            .with("duration", PropertyValue.Double(3600.0))
            .with("pause", PropertyValue.Flag(false))
            .with("video-params/dw", PropertyValue.Int(3840))
            .with("hwdec-current", PropertyValue.Text("mediacodec"))
            .with("current-tracks/video/codec", PropertyValue.Text("av1"))

        assertEquals(12.5, state.position, 0.0)
        assertEquals(3600.0, state.duration, 0.0)
        assertEquals(false, state.paused)
        assertEquals(3840, state.videoWidth)
        assertEquals("mediacodec", state.hardwareDecoder)
        assertEquals("av1", state.videoCodec)
    }

    @Test
    fun `software decoding and cleared loop points read as null`() {
        val state = PlaybackState(abLoopStart = 5.0)
            .with("hwdec-current", PropertyValue.Text("no"))
            .with("ab-loop-a", PropertyValue.Unavailable)

        assertNull(state.hardwareDecoder)
        assertNull(state.abLoopStart)
    }

    @Test
    fun `a new file keeps player-wide state that mpv will not report again`() {
        // Regression: resetting `paused` to its default showed a play button during playback.
        val playing = PlaybackState(paused = false, speed = 1.5, subtitleDelay = 0.3, position = 42.0, fileLoaded = true)
        val next = playing.forNewFile()
        assertEquals(false, next.paused)
        assertEquals(1.5, next.speed, 0.0)
        assertEquals(0.3, next.subtitleDelay, 0.0)
        assertEquals(0.0, next.position, 0.0)
        assertEquals(false, next.fileLoaded)
    }

    @Test
    fun `subtitle text clears when the line disappears`() {
        val shown = PlaybackState().with("sub-text", PropertyValue.Text("안녕하세요"))
        assertEquals("안녕하세요", shown.subtitleText)
        assertEquals("", shown.with("sub-text", PropertyValue.Unavailable).subtitleText)
    }

    @Test
    fun `stats collect separately from playback state`() {
        val state = PlaybackState()
            .with("frame-drop-count", PropertyValue.Int(3))
            .with("video-params/gamma", PropertyValue.Text("pq"))
            .with("estimated-vf-fps", PropertyValue.Double(23.976))
        assertEquals(3, state.stats.droppedFrames)
        assertEquals("pq", state.stats.gamma)
        assertEquals(23.976, state.stats.fps!!, 0.0)
    }

    @Test
    fun `unknown properties are ignored`() {
        val state = PlaybackState()
        assertEquals(state, state.with("vsync-jitter", PropertyValue.Double(0.01)))
    }
}
