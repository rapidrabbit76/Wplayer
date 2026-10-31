package dev.wplayer.app.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class VisibleVideoRowsTest {
    @Test
    fun `a wide film fits between black bars`() {
        val rows = visibleVideoRows(1000f, 750f, 2.5f, ScaleMode.FIT, 1f)
        assertEquals(175f, rows.start, 0.01f)
        assertEquals(575f, rows.endInclusive, 0.01f)
    }

    @Test
    fun `crop and stretch fill the view`() {
        assertEquals(0f..750f, visibleVideoRows(1000f, 750f, 2.5f, ScaleMode.CROP, 1f))
        assertEquals(0f..750f, visibleVideoRows(1000f, 750f, 2.5f, ScaleMode.STRETCH, 1f))
    }

    @Test
    fun `zoom grows the picture up to the view`() {
        val rows = visibleVideoRows(1000f, 750f, 2.5f, ScaleMode.FIT, 1.5f)
        assertEquals(75f, rows.start, 0.01f)
        assertEquals(675f, rows.endInclusive, 0.01f)
    }

    @Test
    fun `unknown video size uses the whole view`() {
        assertEquals(0f..750f, visibleVideoRows(1000f, 750f, null, ScaleMode.FIT, 1f))
    }
}
