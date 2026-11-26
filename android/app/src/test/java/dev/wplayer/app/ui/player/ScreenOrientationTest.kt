package dev.wplayer.app.ui.player

import android.content.pm.ActivityInfo
import dev.wplayer.app.data.model.OrientationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenOrientationTest {
    private fun requested(mode: OrientationMode, locked: Boolean = false, tablet: Boolean = false, television: Boolean = false) =
        requestedOrientationFor(mode, locked, tablet, television)

    @Test
    fun `automatic turns a phone with the sensor whatever the video`() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR, requested(OrientationMode.AUTO))
    }

    @Test
    fun `automatic leaves tablets and TVs to the system`() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, requested(OrientationMode.AUTO, tablet = true))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, requested(OrientationMode.AUTO, television = true))
    }

    @Test
    fun `follow system turns only when the system's auto-rotate is on`() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, requested(OrientationMode.SENSOR))
    }

    @Test
    fun `a locked rotation keeps the screen as it is`() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LOCKED, requested(OrientationMode.AUTO, locked = true))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LOCKED, requested(OrientationMode.AUTO, locked = true, tablet = true))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LOCKED, requested(OrientationMode.SENSOR, locked = true))
    }

    @Test
    fun `fixed orientations and TVs ignore the lock`() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, requested(OrientationMode.LANDSCAPE, locked = true))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, requested(OrientationMode.PORTRAIT, locked = true))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, requested(OrientationMode.AUTO, locked = true, television = true))
    }

    @Test
    fun `the rotation lock is offered only where the screen turns`() {
        assertTrue(rotatesWithDevice(OrientationMode.AUTO, television = false))
        assertTrue(rotatesWithDevice(OrientationMode.SENSOR, television = false))
        assertFalse(rotatesWithDevice(OrientationMode.LANDSCAPE, television = false))
        assertFalse(rotatesWithDevice(OrientationMode.PORTRAIT, television = false))
        assertFalse(rotatesWithDevice(OrientationMode.AUTO, television = true))
    }
}
