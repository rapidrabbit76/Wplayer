package dev.wplayer.app.data.model

/** Identifies a file in a source for resume positions and history; stable across launches. */
fun mediaKey(sourceKey: String, path: String): String = "$sourceKey:$path"

/** A file or folder inside a source. */
data class MediaRef(
    val sourceKey: String,
    val path: String,
    val name: String,
    val isDirectory: Boolean,
) {
    val kind: MediaKind get() = MediaEntry.kindOf(name, isDirectory)
    val mediaKey: String get() = mediaKey(sourceKey, path)
}

data class Playlist(val id: Long, val name: String, val itemCount: Int)

/** A playlist entry: a file to play, or a folder kept as a bookmark. */
data class PlaylistItem(val id: Long, val ref: MediaRef)

data class RecentMedia(val ref: MediaRef, val playedAtMillis: Long)

/** How far a file was watched; [fraction] is in 0..1. */
data class WatchProgress(val positionSeconds: Double, val durationSeconds: Double) {
    val fraction: Float get() = if (durationSeconds > 0) (positionSeconds / durationSeconds).toFloat().coerceIn(0f, 1f) else 0f

    /** Far enough in to be worth continuing; earlier positions start over. */
    val isResumable: Boolean get() = positionSeconds >= MIN_RESUME_SECONDS && durationSeconds > positionSeconds

    companion object {
        const val MIN_RESUME_SECONDS = 10.0
    }
}

enum class DownloadStatus { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

data class Download(
    val id: Long,
    val ref: MediaRef,
    val destPath: String,
    val totalBytes: Long?,
    val downloadedBytes: Long,
    val status: DownloadStatus,
    val error: String?,
) {
    val fraction: Float get() = totalBytes?.takeIf { it > 0 }?.let { (downloadedBytes.toFloat() / it).coerceIn(0f, 1f) } ?: 0f
}
