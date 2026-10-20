package dev.wplayer.app.ui.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import dev.wplayer.core.ffi.SeekPreview
import java.io.File

/**
 * Seek-bar preview frames for one file, from a native [SeekPreview] that is
 * opened on first use and kept until [close]. Not thread-safe: use from one thread.
 */
class SeekPreviewSession(val uri: String, private val dir: File) : AutoCloseable {
    private var preview: SeekPreview? = null
    private val cache = LruCache<Int, Bitmap>(CACHED_FRAMES)

    /** The frame near [seconds] (rounded to [BUCKET_SECONDS]); blocks while it is decoded. */
    fun frameAt(seconds: Double): Bitmap? {
        val bucket = (seconds / BUCKET_SECONDS).toInt()
        cache.get(bucket)?.let { return it }
        val native = preview ?: run {
            dir.deleteRecursively()
            dir.mkdirs()
            SeekPreview(uri, dir.absolutePath, WIDTH_PX, OPEN_TIMEOUT_MS).also { preview = it }
        }
        val file = File(native.frameAt(bucket * BUCKET_SECONDS, FRAME_TIMEOUT_MS))
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        file.delete()
        return bitmap?.also { cache.put(bucket, it) }
    }

    override fun close() {
        preview?.close()
        preview = null
        cache.evictAll()
        dir.deleteRecursively()
    }

    private companion object {
        /** Frames closer together than this share one preview. */
        const val BUCKET_SECONDS = 2.0
        const val CACHED_FRAMES = 90
        const val WIDTH_PX = 320u
        const val OPEN_TIMEOUT_MS = 15_000u
        const val FRAME_TIMEOUT_MS = 5_000u
    }
}
