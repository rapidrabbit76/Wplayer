package dev.wplayer.app.data.model

enum class MediaKind { DIRECTORY, VIDEO, AUDIO, IMAGE, SUBTITLE, OTHER }

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
            "a52", "aac", "ac3", "aif", "aifc", "aiff", "alac", "amr", "ape", "dsf", "dts", "flac", "m4a", "m4b",
            "mka", "mp1", "mp2", "mp3", "mpc", "oga", "ogg", "oma", "opus", "ra", "tta", "voc", "wav", "wma", "wv",
        )
        /** What Android's ImageDecoder reads, camera raw files included. */
        private val IMAGE = setOf(
            "arw", "avif", "bmp", "cr2", "dng", "gif", "heic", "heif", "ico", "jpe", "jpeg", "jpg", "nef",
            "nrw", "orf", "pef", "png", "raf", "rw2", "srw", "webp",
        )
        private val SUBTITLE = setOf("ass", "idx", "sami", "smi", "srt", "ssa", "sub", "sup", "vtt")

        fun kindOf(name: String, isDirectory: Boolean): MediaKind {
            if (isDirectory) return MediaKind.DIRECTORY
            return when (name.substringAfterLast('.', "").lowercase()) {
                in VIDEO -> MediaKind.VIDEO
                in AUDIO -> MediaKind.AUDIO
                in IMAGE -> MediaKind.IMAGE
                in SUBTITLE -> MediaKind.SUBTITLE
                else -> MediaKind.OTHER
            }
        }
    }
}
