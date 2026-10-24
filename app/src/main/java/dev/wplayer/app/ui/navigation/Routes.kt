package dev.wplayer.app.ui.navigation

import androidx.navigation3.runtime.NavKey
import dev.wplayer.app.data.folderOf
import dev.wplayer.app.ui.settings.SettingsCategory
import kotlinx.serialization.Serializable

@Serializable
data object SourcesRoute : NavKey

/** A folder of a source; [focusPath] is an entry to scroll to and highlight, when opened to show a file. */
@Serializable
data class BrowserRoute(val sourceKey: String, val path: String = "/", val focusPath: String? = null) : NavKey

/** The image viewer, paging through a folder's pictures or the pictures inside an archive. */
@Serializable
sealed interface ImageViewerRoute : NavKey {
    val sourceKey: String

    /** The opened file: a picture of the folder, or the archive. */
    val path: String

    /** The pictures of [path]'s folder, opened at [path]. */
    @Serializable
    data class Folder(override val sourceKey: String, override val path: String) : ImageViewerRoute {
        val folder: String get() = folderOf(path)
    }

    /** The pictures inside the archive at [path], opened on the page it was left on. */
    @Serializable
    data class Archive(override val sourceKey: String, override val path: String) : ImageViewerRoute
}

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
