package dev.wplayer.app.ui.player

import android.content.Intent
import android.net.Uri
import dev.wplayer.app.data.folderOf
import dev.wplayer.app.data.model.BrowserSettings
import dev.wplayer.app.data.model.mediaKey

/** Where the files around the current one come from, for next/previous and auto-play. */
sealed interface PlayQueue {
    /** The playable files in the folder of the current file. */
    data object Folder : PlayQueue

    data class Playlist(val playlistId: Long) : PlayQueue
}

/** The list a queue was made from; a different one means listing the files again. */
internal sealed interface QueueOrigin {
    /** A folder in the browser's order, so a new sort or filter lists it again. */
    data class Folder(val sourceKey: String, val folder: String, val browser: BrowserSettings) : QueueOrigin

    data class Playlist(val playlistId: Long) : QueueOrigin

    companion object {
        fun of(request: PlaybackRequest.FromSource, browser: BrowserSettings): QueueOrigin = when (val queue = request.queue) {
            PlayQueue.Folder -> Folder(request.sourceKey, folderOf(request.path), browser)
            is PlayQueue.Playlist -> Playlist(queue.playlistId)
        }
    }
}

/** What the player was asked to open. */
sealed interface PlaybackRequest {
    val title: String

    /** Identifies the media for resume positions; stable across launches. */
    val mediaKey: String

    /** A file inside a source registered with the native core. */
    data class FromSource(
        val sourceKey: String,
        val path: String,
        override val title: String,
        val queue: PlayQueue = PlayQueue.Folder,
    ) : PlaybackRequest {
        override val mediaKey: String get() = mediaKey(sourceKey, path)
    }

    /** A URI from another app (`content://`, `file://`, `http(s)://`). */
    data class FromUri(val uri: Uri, override val title: String) : PlaybackRequest {
        override val mediaKey: String get() = uri.toString()
    }

    companion object {
        private const val EXTRA_SOURCE_KEY = "dev.wplayer.extra.SOURCE_KEY"
        private const val EXTRA_PATH = "dev.wplayer.extra.PATH"
        private const val EXTRA_TITLE = "dev.wplayer.extra.TITLE"
        private const val EXTRA_PLAYLIST_ID = "dev.wplayer.extra.PLAYLIST_ID"

        fun FromSource.writeTo(intent: Intent): Intent = intent
            .putExtra(EXTRA_SOURCE_KEY, sourceKey)
            .putExtra(EXTRA_PATH, path)
            .putExtra(EXTRA_TITLE, title)
            .apply { if (queue is PlayQueue.Playlist) putExtra(EXTRA_PLAYLIST_ID, queue.playlistId) }

        /** What other apps may hand the player; mpv would also take its own protocols, such as `fdclose://`. */
        private val SHAREABLE_SCHEMES = setOf("file", "content", "http", "https")

        /** Only this app may name a file in a saved source: that skips the lock of locked folders. */
        fun from(intent: Intent): PlaybackRequest? {
            // Other apps can only reach the player through its exported alias, whose name the intent then carries.
            val fromThisApp = intent.component?.className == PlayerActivity::class.java.name
            val sourceKey = intent.getStringExtra(EXTRA_SOURCE_KEY)
            val path = intent.getStringExtra(EXTRA_PATH)
            if (fromThisApp && sourceKey != null && path != null) {
                val queue = if (intent.hasExtra(EXTRA_PLAYLIST_ID)) {
                    PlayQueue.Playlist(intent.getLongExtra(EXTRA_PLAYLIST_ID, 0))
                } else {
                    PlayQueue.Folder
                }
                return FromSource(sourceKey, path, intent.getStringExtra(EXTRA_TITLE) ?: path.substringAfterLast('/'), queue)
            }
            val uri = intent.data?.normalizeScheme()?.takeIf { it.scheme in SHAREABLE_SCHEMES } ?: return null
            return FromUri(uri, uri.lastPathSegment ?: uri.toString())
        }
    }
}
