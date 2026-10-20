package dev.wplayer.app.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class StepSeekBurstTest {
    @Test
    fun `steps in one direction add up`() {
        val burst = null.plus(10.0).plus(10.0).plus(10.0)
        assertEquals(StepSeekBurst(forward = true, seconds = 30, taps = 3), burst)
    }

    @Test
    fun `turning around or a faded burst starts over`() {
        assertEquals(StepSeekBurst(forward = false, seconds = 5), null.plus(10.0).plus(-5.0))
        val faded = StepSeekBurst(forward = true, seconds = 30, taps = 3, active = false)
        assertEquals(StepSeekBurst(forward = true, seconds = 10), faded.plus(10.0))
    }
}
