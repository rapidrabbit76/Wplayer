package dev.wplayer.app.data.transcription

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.Closeable
import java.io.File
import java.io.Writer
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** AI subtitles on the device, one SRT file per media (keyed like resume positions). */
@Singleton
class GeneratedSubtitles @Inject constructor(@ApplicationContext context: Context) {
    private val directory = File(context.filesDir, "subtitles")

    fun file(mediaKey: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(mediaKey.toByteArray())
        return File(directory, digest.take(KEY_BYTES).joinToString("") { "%02x".format(it) } + ".srt")
    }

    /** The subtitles made for [mediaKey], if any lines were recognized. */
    fun existing(mediaKey: String): File? = file(mediaKey).takeIf { it.length() > 0 }

    fun delete(mediaKey: String) {
        file(mediaKey).delete()
    }

    private companion object {
        const val KEY_BYTES = 16
    }
}

/**
 * Writes SRT cues as they come, flushing each so a player reading the file
 * sees it grow. Cues may arrive out of time order (a second pass fills in the
 * start); players sort them by time.
 */
class SrtWriter(private val out: Writer) : Closeable {
    private var index = 0

    fun add(startMs: Long, endMs: Long, text: String) {
        index++
        out.write("$index\n${srtTime(startMs)} --> ${srtTime(endMs)}\n${text.trim()}\n\n")
        out.flush()
    }

    override fun close() = out.close()

    companion object {
        fun open(file: File): SrtWriter {
            file.parentFile?.mkdirs()
            return SrtWriter(file.bufferedWriter())
        }

        /** `HH:MM:SS,mmm` */
        fun srtTime(ms: Long): String {
            val clamped = ms.coerceAtLeast(0)
            return "%02d:%02d:%02d,%03d".format(clamped / 3_600_000, clamped / 60_000 % 60, clamped / 1000 % 60, clamped % 1000)
        }
    }
}
