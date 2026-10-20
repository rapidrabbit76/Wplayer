package dev.wplayer.app.data.model

enum class MediaKind { DIRECTORY, VIDEO, AUDIO, SUBTITLE, OTHER }

data class MediaEntry(
    val name: String,
    val path: String,
    val kind: MediaKind,
    val size: Long,
    val modifiedAtMillis: Long?,
) {
    val isPlayable: Boolean get() = kind == MediaKind.VIDEO || kind == MediaKind.AUDIO

    /** Lower-case extension without the dot; empty for folders and files without one. */
    val extension: String get() = if (kind == MediaKind.DIRECTORY) "" else name.substringAfterLast('.', "").lowercase()

    companion object {
        private val VIDEO = setOf(
            "3gp", "asf", "avi", "divx", "flv", "m2ts", "m4v", "mkv", "mov", "mp4", "mpeg",
            "mpg", "mts", "ogv", "rm", "rmvb", "ts", "vob", "webm", "wmv",
        )
        private val AUDIO = setOf(
            "aac", "alac", "ape", "dsf", "flac", "m4a", "mka", "mp3", "ogg", "opus", "wav", "wma",
        )
        private val SUBTITLE = setOf("ass", "idx", "sami", "smi", "srt", "ssa", "sub", "sup", "vtt")

        fun kindOf(name: String, isDirectory: Boolean): MediaKind {
            if (isDirectory) return MediaKind.DIRECTORY
            return when (name.substringAfterLast('.', "").lowercase()) {
                in VIDEO -> MediaKind.VIDEO
                in AUDIO -> MediaKind.AUDIO
                in SUBTITLE -> MediaKind.SUBTITLE
                else -> MediaKind.OTHER
            }
        }
    }
}
