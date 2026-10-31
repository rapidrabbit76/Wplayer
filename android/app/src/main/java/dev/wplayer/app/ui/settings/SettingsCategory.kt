package dev.wplayer.app.ui.settings

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.ui.graphics.vector.ImageVector
import dev.wplayer.app.R
import kotlinx.serialization.Serializable

/** The settings pages, one per category. */
@Serializable
enum class SettingsCategory(@StringRes val title: Int, @StringRes val summary: Int, val icon: ImageVector) {
    GENERAL(R.string.settings_general, R.string.settings_general_summary, Icons.Outlined.Tune),
    PLAYBACK(R.string.settings_playback, R.string.settings_playback_summary, Icons.Outlined.PlayCircle),
    SCREEN(R.string.settings_screen, R.string.settings_screen_summary, Icons.Outlined.ScreenRotation),
    SUBTITLES(R.string.settings_subtitles, R.string.settings_subtitles_summary, Icons.Outlined.Subtitles),
    AUDIO(R.string.settings_audio, R.string.settings_audio_summary, Icons.Outlined.MusicNote),
    GESTURES(R.string.settings_gestures, R.string.settings_gestures_summary, Icons.Outlined.Gesture),
    NETWORK(R.string.settings_network, R.string.settings_network_summary, Icons.Outlined.Wifi),
    UPDATES(R.string.settings_updates, R.string.settings_updates_summary, Icons.Outlined.SystemUpdate),
    ABOUT(R.string.settings_about, R.string.settings_about_summary, Icons.Outlined.Info),
}
