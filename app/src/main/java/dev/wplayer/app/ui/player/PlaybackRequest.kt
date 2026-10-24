package dev.wplayer.app.ui.player

import android.content.Intent
import android.net.Uri
import dev.wplayer.app.data.model.mediaKey

/** Where the files around the current one come from, for next/previous and auto-play. */
sealed interface PlayQueue {
    /** The playable files in the folder of the current file. */
    data object Folder : PlayQueue

    data class Playlist(val playlistId: Long) : PlayQueue
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

        /**
         * Parses an intent from this app or a `VIEW` intent from another app. A shared
         * file is titled by its URI until the player asks its provider for the name.
         */
        fun from(intent: Intent): PlaybackRequest? {
            val sourceKey = intent.getStringExtra(EXTRA_SOURCE_KEY)
            val path = intent.getStringExtra(EXTRA_PATH)
            if (sourceKey != null && path != null) {
                val queue = if (intent.hasExtra(EXTRA_PLAYLIST_ID)) {
                    PlayQueue.Playlist(intent.getLongExtra(EXTRA_PLAYLIST_ID, 0))
                } else {
                    PlayQueue.Folder
                }
                return FromSource(sourceKey, path, intent.getStringExtra(EXTRA_TITLE) ?: path.substringAfterLast('/'), queue)
            }
            val uri = intent.data ?: return null
            return FromUri(uri, uri.lastPathSegment ?: uri.toString())
        }
    }
}
