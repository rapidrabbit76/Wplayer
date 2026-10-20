package dev.wplayer.app.ui.player

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.view.Window
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlin.math.roundToInt

/** Window-local brightness; leaving the player restores the system setting. */
class BrightnessController(private val window: Window?, context: Context) {
    var level: Float = window?.attributes?.screenBrightness?.takeIf { it >= 0f }
        ?: (Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f)
        private set

    /** Applies a remembered [target] level. */
    fun restore(target: Float) {
        adjust(target - level)
    }

    /** Changes brightness by [delta] (fraction of full range) and returns the new level. */
    fun adjust(delta: Float): Float {
        level = (level + delta).coerceIn(MIN_LEVEL, 1f)
        window?.let { it.attributes = it.attributes.apply { screenBrightness = level } }
        return level
    }

    private companion object {
        const val MIN_LEVEL = 0.01f
    }
}

/** Media stream volume with sub-step precision so slow swipes still register. */
class VolumeController(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    private var level = current()

    /** Re-reads the system volume, e.g. after hardware keys changed it. */
    fun sync() {
        level = current()
    }

    fun adjust(delta: Float): Float {
        level = (level + delta).coerceIn(0f, 1f)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (level * max).roundToInt(), 0)
        return level
    }

    private fun current() = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
}

@Composable
fun rememberBrightnessController(): BrightnessController {
    val activity = LocalActivity.current
    val context = LocalContext.current
    return remember(activity) { BrightnessController(activity?.window, context) }
}

@Composable
fun rememberVolumeController(): VolumeController {
    val context = LocalContext.current
    return remember(context) { VolumeController(context) }
}
