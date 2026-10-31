package dev.wplayer.app.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayFrameRateTest {
    @Test
    fun `videos faster than 60 fps ask for their rate`() {
        assertEquals(120f, displayFrameRateFor(120.0, smoothMotion = false))
        assertEquals(119.88f, displayFrameRateFor(119.88, smoothMotion = false), 0.001f)
    }

    @Test
    fun `slower videos keep the display as it is`() {
        assertEquals(0f, displayFrameRateFor(23.976, smoothMotion = false))
        assertEquals(0f, displayFrameRateFor(59.94, smoothMotion = false))
        assertEquals(0f, displayFrameRateFor(60.0, smoothMotion = false))
    }

    @Test
    fun `no preference when unknown, implausible or with smooth motion`() {
        assertEquals(0f, displayFrameRateFor(null, smoothMotion = false))
        assertEquals(0f, displayFrameRateFor(1000.0, smoothMotion = false))
        assertEquals(0f, displayFrameRateFor(120.0, smoothMotion = true))
    }
}
