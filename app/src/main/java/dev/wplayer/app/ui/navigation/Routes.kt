package dev.wplayer.app.ui.navigation

import androidx.navigation3.runtime.NavKey
import dev.wplayer.app.ui.settings.SettingsCategory
import kotlinx.serialization.Serializable

@Serializable
data object SourcesRoute : NavKey

/** A folder of a source; [focusPath] is an entry to scroll to and highlight, when opened to show a file. */
@Serializable
data class BrowserRoute(val sourceKey: String, val path: String = "/", val focusPath: String? = null) : NavKey

/** Adds a source, or edits the one with [sourceKey]. */
@Serializable
data class SourceEditorRoute(val sourceKey: String? = null) : NavKey

@Serializable
data object SettingsRoute : NavKey

@Serializable
data class SettingsCategoryRoute(val category: SettingsCategory) : NavKey

@Serializable
data object DownloadsRoute : NavKey

/** The playlists tab: recently played and the user's playlists. */
@Serializable
data object LibraryRoute : NavKey

@Serializable
data class PlaylistRoute(val playlistId: Long) : NavKey

@Serializable
data object RecentRoute : NavKey
