package dev.wplayer.app.ui.player

import android.content.Intent
import android.net.Uri
import dev.wplayer.app.data.SourceRepository
import dev.wplayer.app.data.folderOf
import dev.wplayer.app.data.link.firstWebUrl
import dev.wplayer.app.data.model.BrowserSettings
import dev.wplayer.app.data.model.MediaRef
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
        /** Null when [request] plays alone: media from other apps, or a link opened outside a playlist. */
        fun of(request: PlaybackRequest, browser: BrowserSettings): QueueOrigin? = when (request) {
            is PlaybackRequest.FromSource -> when (val queue = request.queue) {
                PlayQueue.Folder -> Folder(request.sourceKey, folderOf(request.path), browser)
                is PlayQueue.Playlist -> Playlist(queue.playlistId)
            }
            is PlaybackRequest.FromLink -> request.playlist?.let { Playlist(it.playlistId) }
            is PlaybackRequest.FromUri -> null
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

    /**
     * A web address the user opened, shared or kept in a playlist. A link plugin that
     * matches it turns it into a stream (see LinkResolver); otherwise mpv opens it as is.
     * [title] is the address until the plugin names the page.
     */
    data class FromLink(val url: String, override val title: String, val playlist: PlayQueue.Playlist? = null) : PlaybackRequest {
        override val mediaKey: String get() = mediaKey(SourceRepository.LINK_KEY, url)
    }

    companion object {
        private const val EXTRA_SOURCE_KEY = "dev.wplayer.extra.SOURCE_KEY"
        private const val EXTRA_PATH = "dev.wplayer.extra.PATH"
        private const val EXTRA_TITLE = "dev.wplayer.extra.TITLE"
        private const val EXTRA_PLAYLIST_ID = "dev.wplayer.extra.PLAYLIST_ID"
        private const val EXTRA_LINK = "dev.wplayer.extra.LINK"

        fun FromSource.writeTo(intent: Intent): Intent = intent
            .putExtra(EXTRA_SOURCE_KEY, sourceKey)
            .putExtra(EXTRA_PATH, path)
            .putExtra(EXTRA_TITLE, title)
            .apply { if (queue is PlayQueue.Playlist) putExtra(EXTRA_PLAYLIST_ID, queue.playlistId) }

        fun FromLink.writeTo(intent: Intent): Intent = intent
            .putExtra(EXTRA_LINK, url)
            .putExtra(EXTRA_TITLE, title)
            .apply { playlist?.let { putExtra(EXTRA_PLAYLIST_ID, it.playlistId) } }

        /** What other apps may hand the player; mpv would also take its own protocols, such as `fdclose://`. */
        private val SHAREABLE_SCHEMES = setOf("file", "content", "http", "https")

        /** Only this app may name a file in a saved source: that skips the lock of locked folders. */
        fun from(intent: Intent): PlaybackRequest? {
            // Other apps can only reach the player through its exported alias, whose name the intent then carries.
            val fromThisApp = intent.component?.className == PlayerActivity::class.java.name
            val sourceKey = intent.getStringExtra(EXTRA_SOURCE_KEY)
            val path = intent.getStringExtra(EXTRA_PATH)
            val link = intent.getStringExtra(EXTRA_LINK)
            val playlist = if (intent.hasExtra(EXTRA_PLAYLIST_ID)) PlayQueue.Playlist(intent.getLongExtra(EXTRA_PLAYLIST_ID, 0)) else null
            if (fromThisApp && sourceKey != null && path != null) {
                return FromSource(sourceKey, path, intent.getStringExtra(EXTRA_TITLE) ?: path.substringAfterLast('/'), playlist ?: PlayQueue.Folder)
            }
            if (fromThisApp && link != null) return FromLink(link, intent.getStringExtra(EXTRA_TITLE) ?: link, playlist)
            // A page shared from a browser or another app: the text holds its address, often after its title.
            if (intent.action == Intent.ACTION_SEND) {
                return intent.getStringExtra(Intent.EXTRA_TEXT)?.let(::firstWebUrl)?.let { FromLink(it, it) }
            }
            val uri = intent.data?.normalizeScheme()?.takeIf { it.scheme in SHAREABLE_SCHEMES } ?: return null
            return FromUri(uri, uri.lastPathSegment ?: uri.toString())
        }
    }
}

/**
 * What playlists, the recent list and resume positions store for [this]; null for
 * media from other apps, which they never hold. The inverse of [toPlaybackRequest].
 */
val PlaybackRequest.storedRef: MediaRef?
    get() = when (this) {
        is PlaybackRequest.FromSource -> MediaRef(sourceKey, path, title, isDirectory = false)
        is PlaybackRequest.FromLink -> MediaRef(SourceRepository.LINK_KEY, url, title, isDirectory = false)
        is PlaybackRequest.FromUri -> null
    }

/** The request that plays a stored file or link, inside [queue]; a link has no folder, so a folder queue plays it alone. */
fun MediaRef.toPlaybackRequest(queue: PlayQueue = PlayQueue.Folder): PlaybackRequest =
    if (sourceKey == SourceRepository.LINK_KEY) {
        PlaybackRequest.FromLink(path, name, queue as? PlayQueue.Playlist)
    } else {
        PlaybackRequest.FromSource(sourceKey, path, name, queue)
    }
