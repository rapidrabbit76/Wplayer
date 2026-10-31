package dev.wplayer.app.ui.player

import android.view.KeyEvent

/** What a key on a Bluetooth remote or keyboard asks the player to do. */
enum class PlayerKeyCommand {
    SEEK_BACK,
    SEEK_FORWARD,
    TOGGLE_PAUSE,
    NEXT_FILE,

    /** Back to the start, or to the previous file when already near the start. */
    RESTART_OR_PREVIOUS,
    BRIGHTNESS_UP,
    BRIGHTNESS_DOWN,

    /** Show the controls with focus on them, to move through them with the D-pad. */
    FOCUS_CONTROLS,

    /** The system changed the volume; re-read it so swipes continue from there. */
    VOLUME_CHANGED,
}

/**
 * Turns key events into [PlayerKeyCommand]s. Left and right seek by the seek
 * steps when tapped; held, right skips to the next file and left restarts the
 * file. Up, down and menu bring the controls up for D-pad navigation; while
 * they (or a panel) are navigated, the D-pad and OK key are left to the views.
 * Volume keys keep their system behaviour. Brightness keys change the
 * player's own brightness, because the window brightness the player sets
 * would hide a change of the system setting.
 *
 * Holding a key makes the system repeat it; the first repeat marks a long
 * press, so the tap action runs on release only when no repeat came.
 */
class PlayerKeys {
    private val longPressed = mutableSetOf<Int>()

    /**
     * Handles one event; returns whether it was consumed. [navigating]: focus
     * is on the controls or a panel, so D-pad keys move it.
     */
    fun handle(
        keyCode: Int,
        action: Int,
        repeatCount: Int,
        canceled: Boolean,
        navigating: Boolean,
        emit: (PlayerKeyCommand) -> Unit,
    ): Boolean {
        val down = action == KeyEvent.ACTION_DOWN
        val up = action == KeyEvent.ACTION_UP
        if (navigating && keyCode in NAVIGATION_KEYS) {
            longPressed -= keyCode
            return false
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val forward = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                when {
                    down && repeatCount == 0 -> longPressed -= keyCode
                    down && longPressed.add(keyCode) ->
                        emit(if (forward) PlayerKeyCommand.NEXT_FILE else PlayerKeyCommand.RESTART_OR_PREVIOUS)
                    up && !canceled && keyCode !in longPressed ->
                        emit(if (forward) PlayerKeyCommand.SEEK_FORWARD else PlayerKeyCommand.SEEK_BACK)
                }
                if (up) longPressed -= keyCode
            }
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            -> if (down && repeatCount == 0) emit(PlayerKeyCommand.TOGGLE_PAUSE)
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> if (down) emit(PlayerKeyCommand.SEEK_FORWARD)
            KeyEvent.KEYCODE_MEDIA_REWIND -> if (down) emit(PlayerKeyCommand.SEEK_BACK)
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_MENU,
            -> if (down && repeatCount == 0) emit(PlayerKeyCommand.FOCUS_CONTROLS)
            KeyEvent.KEYCODE_BRIGHTNESS_UP -> if (down) emit(PlayerKeyCommand.BRIGHTNESS_UP)
            KeyEvent.KEYCODE_BRIGHTNESS_DOWN -> if (down) emit(PlayerKeyCommand.BRIGHTNESS_DOWN)
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (up) emit(PlayerKeyCommand.VOLUME_CHANGED)
                return false
            }
            else -> return false
        }
        return true
    }

    private companion object {
        /** Keys that move or use focus; a keyboard's space stays play/pause. */
        val NAVIGATION_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MENU,
        )
    }
}
