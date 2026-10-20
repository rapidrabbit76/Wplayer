package dev.wplayer.app.ui.player

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerKeysTest {
    private val keys = PlayerKeys()
    private val commands = mutableListOf<PlayerKeyCommand>()

    private fun down(keyCode: Int, repeat: Int = 0, navigating: Boolean = false) =
        keys.handle(keyCode, KeyEvent.ACTION_DOWN, repeat, canceled = false, navigating) { commands += it }

    private fun up(keyCode: Int, navigating: Boolean = false) =
        keys.handle(keyCode, KeyEvent.ACTION_UP, 0, canceled = false, navigating) { commands += it }

    @Test
    fun `tapping left and right seeks on release`() {
        assertTrue(down(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(emptyList<PlayerKeyCommand>(), commands)
        assertTrue(up(KeyEvent.KEYCODE_DPAD_RIGHT))
        down(KeyEvent.KEYCODE_DPAD_LEFT)
        up(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(listOf(PlayerKeyCommand.SEEK_FORWARD, PlayerKeyCommand.SEEK_BACK), commands)
    }

    @Test
    fun `holding acts once and skips the seek`() {
        down(KeyEvent.KEYCODE_DPAD_RIGHT)
        repeat(5) { down(KeyEvent.KEYCODE_DPAD_RIGHT, repeat = it + 1) }
        up(KeyEvent.KEYCODE_DPAD_RIGHT)
        down(KeyEvent.KEYCODE_DPAD_LEFT)
        down(KeyEvent.KEYCODE_DPAD_LEFT, repeat = 1)
        up(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(listOf(PlayerKeyCommand.NEXT_FILE, PlayerKeyCommand.RESTART_OR_PREVIOUS), commands)

        // The next tap seeks again.
        down(KeyEvent.KEYCODE_DPAD_RIGHT)
        up(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(PlayerKeyCommand.SEEK_FORWARD, commands.last())
    }

    @Test
    fun `volume keys stay with the system`() {
        assertFalse(down(KeyEvent.KEYCODE_VOLUME_UP))
        assertFalse(up(KeyEvent.KEYCODE_VOLUME_UP))
        assertEquals(listOf(PlayerKeyCommand.VOLUME_CHANGED), commands)
    }

    @Test
    fun `brightness keys repeat while held`() {
        assertTrue(down(KeyEvent.KEYCODE_BRIGHTNESS_UP))
        down(KeyEvent.KEYCODE_BRIGHTNESS_UP, repeat = 1)
        up(KeyEvent.KEYCODE_BRIGHTNESS_UP)
        assertEquals(listOf(PlayerKeyCommand.BRIGHTNESS_UP, PlayerKeyCommand.BRIGHTNESS_UP), commands)
    }

    @Test
    fun `play-pause toggles once per press`() {
        down(KeyEvent.KEYCODE_SPACE)
        down(KeyEvent.KEYCODE_SPACE, repeat = 1)
        up(KeyEvent.KEYCODE_SPACE)
        assertEquals(listOf(PlayerKeyCommand.TOGGLE_PAUSE), commands)
        assertFalse(down(KeyEvent.KEYCODE_A))
    }

    @Test
    fun `up, down and menu bring the controls into focus`() {
        assertTrue(down(KeyEvent.KEYCODE_DPAD_UP))
        up(KeyEvent.KEYCODE_DPAD_UP)
        down(KeyEvent.KEYCODE_DPAD_DOWN)
        down(KeyEvent.KEYCODE_DPAD_DOWN, repeat = 1)
        down(KeyEvent.KEYCODE_MENU)
        assertEquals(List(3) { PlayerKeyCommand.FOCUS_CONTROLS }, commands)
    }

    @Test
    fun `while navigating the d-pad moves focus and media keys still work`() {
        assertFalse(down(KeyEvent.KEYCODE_DPAD_RIGHT, navigating = true))
        assertFalse(up(KeyEvent.KEYCODE_DPAD_RIGHT, navigating = true))
        assertFalse(down(KeyEvent.KEYCODE_DPAD_CENTER, navigating = true))
        assertFalse(down(KeyEvent.KEYCODE_DPAD_UP, navigating = true))
        assertTrue(down(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, navigating = true))
        assertTrue(down(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, navigating = true))
        assertEquals(listOf(PlayerKeyCommand.TOGGLE_PAUSE, PlayerKeyCommand.SEEK_FORWARD), commands)
    }

    @Test
    fun `a hold that turns into navigation does not seek or skip later`() {
        down(KeyEvent.KEYCODE_DPAD_RIGHT)
        down(KeyEvent.KEYCODE_DPAD_RIGHT, repeat = 1)
        up(KeyEvent.KEYCODE_DPAD_RIGHT, navigating = true)
        down(KeyEvent.KEYCODE_DPAD_RIGHT)
        up(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(listOf(PlayerKeyCommand.NEXT_FILE, PlayerKeyCommand.SEEK_FORWARD), commands)
    }
}
