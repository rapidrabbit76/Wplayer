package dev.wplayer.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.wplayer.app.data.model.MediaRef
import dev.wplayer.app.di.IoDispatcher
import dev.wplayer.core.ffi.mediaUri
import dev.wplayer.core.ffi.renderThumbnail
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Video thumbnails, rendered once by the native core and kept as small JPEGs
 * in the app cache. Rendering opens the file over the network for remote
 * sources, so few run at a time.
 */
@Singleton
class ThumbnailRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val sources: SourceRepository,
    private val lockedFolders: LockedFolders,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val dir = File(context.cacheDir, "thumbnails")
    private val memory = LruCache<String, Bitmap>(MEMORY_ENTRIES)
    private val renders = Semaphore(MAX_CONCURRENT_RENDERS)

    /** Files that could not be rendered this session (no video, unreadable), so scrolling does not retry them. */
    private val failed = ConcurrentHashMap.newKeySet<String>()

    /** The thumbnail for [ref], rendering it first if needed; null when there is none (yet). */
    suspend fun thumbnail(ref: MediaRef): Bitmap? {
        val key = cacheKey(ref)
        memory.get(key)?.let { return it }
        if (key in failed) return null
        val file = File(dir, "$key.jpg")
        withContext(io) { decode(file) }?.let { return it.also { memory.put(key, it) } }

        return renders.withPermit {
            // Another caller may have rendered it while this one waited.
            withContext(io) { decode(file) ?: render(ref, file, key) }
        }?.also { memory.put(key, it) }
    }

    suspend fun clear() {
        memory.evictAll()
        failed.clear()
        withContext(io) { dir.deleteRecursively() }
    }

    private suspend fun render(ref: MediaRef, file: File, key: String): Bitmap? {
        val work = File(dir, "$key.tmp").apply { deleteRecursively(); mkdirs() }
        return try {
            sources.ensureConnected(ref.sourceKey)
            val jpeg = File(renderThumbnail(mediaUri(ref.sourceKey, ref.path), work.absolutePath, WIDTH_PX, POSITION_PERCENT, TIMEOUT_MS))
            // Files in locked folders are kept in memory only, never in the cache directory.
            if (lockedFolders.isLocked(ref.sourceKey, ref.path)) return decode(jpeg)
            jpeg.renameTo(file)
            decode(file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "no thumbnail for ${ref.path}: ${e.message}")
            failed += key
            null
        } finally {
            work.deleteRecursively()
        }
    }

    private fun decode(file: File): Bitmap? = if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null

    // The width is part of the key, so a change of size renders thumbnails again instead of stretching old ones.
    private fun cacheKey(ref: MediaRef): String = MessageDigest.getInstance("SHA-1")
        .digest("${ref.sourceKey}\u0000${ref.path}\u0000$WIDTH_PX".toByteArray())
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "ThumbnailRepository"
        /** Sharp in a grid cell at the largest thumbnail size setting (about 320 dp wide). */
        const val WIDTH_PX = 480u
        /** Far enough in to skip black openings and studio logos. */
        const val POSITION_PERCENT = 15u
        const val TIMEOUT_MS = 20_000u
        const val MAX_CONCURRENT_RENDERS = 2
        const val MEMORY_ENTRIES = 120
    }
}
