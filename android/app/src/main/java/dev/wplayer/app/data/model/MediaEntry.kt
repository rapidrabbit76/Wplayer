package dev.wplayer.app.data.model

enum class MediaKind {
    DIRECTORY,
    VIDEO,
    AUDIO,
    IMAGE,

    /** Zip, 7z and RAR archives, opened as books of the pictures inside. */
    ARCHIVE,

    /** PDF documents, opened like archives as books of their pages. */
    PDF,

    /** EPUB books, opened in the reader. */
    EPUB,
    SUBTITLE,
    OTHER,

    /** A web page or stream address in a playlist or the recent list; never a file in a folder. */
    LINK,
    ;

    /** Opens in the image viewer or the reader rather than the player, so playlists cannot hold it. */
    val isViewed: Boolean get() = this == IMAGE || this == ARCHIVE || this == PDF || this == EPUB
}

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
        /** What libarchive reads, with the comic book names of each. */
        private val ARCHIVE = setOf("7z", "cb7", "cbr", "cbz", "rar", "zip")
        private val SUBTITLE = setOf("ass", "idx", "sami", "smi", "srt", "ssa", "sub", "sup", "vtt")

        fun kindOf(name: String, isDirectory: Boolean): MediaKind {
            if (isDirectory) return MediaKind.DIRECTORY
            return when (name.substringAfterLast('.', "").lowercase()) {
                in VIDEO -> MediaKind.VIDEO
                in AUDIO -> MediaKind.AUDIO
                in IMAGE -> MediaKind.IMAGE
                in ARCHIVE -> MediaKind.ARCHIVE
                "pdf" -> MediaKind.PDF
                "epub" -> MediaKind.EPUB
                in SUBTITLE -> MediaKind.SUBTITLE
                else -> MediaKind.OTHER
            }
        }
    }
}
