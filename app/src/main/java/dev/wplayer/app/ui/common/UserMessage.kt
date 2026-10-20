package dev.wplayer.app.ui.common

import androidx.annotation.StringRes
import dev.wplayer.app.R

/** One-off confirmations shown in a snackbar; the screen reports back once shown. */
enum class UserMessage(@StringRes val text: Int) {
    ADDED_TO_PLAYLIST(R.string.message_added_to_playlist),
    ALREADY_IN_PLAYLIST(R.string.message_already_in_playlist),
    DOWNLOAD_STARTED(R.string.message_download_started),
    REFRESH_FAILED(R.string.message_refresh_failed),
    FOLDER_LOCKED(R.string.message_folder_locked),
    NO_SCREEN_LOCK(R.string.message_no_screen_lock),
}
