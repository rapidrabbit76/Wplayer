package dev.wplayer.app.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseSliderStateTest {
    @Test
    fun showsTheDragUntilReleasedThenTheSavedValue() {
        val slider = ReleaseSliderState()
        slider.drag(42.6f)
        assertEquals(42.6f, slider.shown(saved = 10), 0f)
        assertEquals(43, slider.release())
        assertEquals(10f, slider.shown(saved = 10), 0f)
    }

    @Test
    fun releaseWithoutADragSavesNothing() {
        assertNull(ReleaseSliderState().release())
    }
}
