package dev.wplayer.app.data

import android.content.Context
import android.os.storage.StorageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Where the player keeps its on-disk network cache, and how much may go there. */
@Singleton
class CacheStorage @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val storage = context.getSystemService(StorageManager::class.java)

    /** mpv writes one unlinked temporary file per stream here, so nothing is left behind. */
    val playerCacheDir: File = File(context.cacheDir, "mpv").apply { mkdirs() }

    /** Seek-bar preview frames of the file being played; short-lived. */
    val seekPreviewDir: File = File(context.cacheDir, "seek-preview")

    /**
     * The largest cache that still leaves [RESERVE_MEGABYTES] free. Counts space
     * the system would free by clearing other apps' caches, as it does on demand.
     */
    fun maxCacheMegabytes(): Long {
        val allocatable = try {
            storage.getAllocatableBytes(storage.getUuidForPath(playerCacheDir))
        } catch (e: IOException) {
            playerCacheDir.usableSpace
        }
        return (allocatable / MEGABYTE - RESERVE_MEGABYTES).coerceAtLeast(0)
    }

    private companion object {
        const val MEGABYTE = 1024L * 1024
        const val RESERVE_MEGABYTES = 1024L
    }
}
