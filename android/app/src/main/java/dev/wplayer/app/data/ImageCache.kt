package dev.wplayer.app.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.wplayer.app.di.ApplicationScope
import dev.wplayer.app.di.IoDispatcher
import dev.wplayer.core.ffi.CoreException
import dev.wplayer.core.ffi.TransferListener
import dev.wplayer.core.ffi.downloadFile
import dev.wplayer.core.ffi.readArchiveEntry
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Pictures and books as local files for the image viewer and the reader.
 * Device storage is read where it is; files from network sources, pages taken
 * out of archives and pages drawn from PDFs are kept in the app cache, which keeps the most recently viewed
 * up to [MAX_CACHE_BYTES]. Copies from locked folders
 * go to a folder of their own, emptied when the viewer closes and whenever the
 * folders lock again, so they never stay in the cache.
 */
@Singleton
class ImageCache @Inject constructor(
    @ApplicationContext context: Context,
    private val sources: SourceRepository,
    private val lockedFolders: LockedFolders,
    @IoDispatcher private val io: CoroutineDispatcher,
    @ApplicationScope scope: CoroutineScope,
) {
    private val dir = File(context.cacheDir, "images")
    private val lockedDir = File(context.cacheDir, "images-locked")

    /** One copy of a file at a time: two would append to the same partial file. */
    private val copies = ConcurrentHashMap<String, Mutex>()

    init {
        scope.launch(io) {
            lockedFolders.state.map { it.unlocked }.distinctUntilChanged().filter { !it }.collect { lockedDir.deleteRecursively() }
        }
    }

    /**
     * The picture at [path] of [sourceKey] as a local file, copying it first if
     * needed; [onProgress] gets the fraction copied, or null while the size is unknown.
     */
    suspend fun file(sourceKey: String, path: String, onProgress: (Float?) -> Unit): File {
        if (sourceKey == SourceRepository.LOCAL_KEY) {
            return withContext(io) {
                File(sources.localStorage.rootPath, path).takeIf { it.isFile } ?: throw FileNotFoundException(path)
            }
        }
        return cached(sourceKey, path, cacheName("$sourceKey\u0000$path", path)) { target ->
            copy(sourceKey, path, target, onProgress)
        }
    }

    /**
     * The picture [entryPath] inside the archive at [archivePath] of [sourceKey]
     * as a local file, taking it out of the archive first if needed. Reading
     * entries in archive order is cheapest (see [readArchiveEntry]).
     */
    suspend fun archivePage(sourceKey: String, archivePath: String, entryPath: String): File =
        cached(sourceKey, archivePath, cacheName("$sourceKey\u0000$archivePath\u0000$entryPath", entryPath)) { target ->
            val bytes = sources.withConnection(sourceKey) { readArchiveEntry(sourceKey, archivePath, entryPath) }
            target.parentFile?.mkdirs()
            val partial = File(target.path + PARTIAL_SUFFIX)
            partial.writeBytes(bytes)
            if (!partial.renameTo(target)) throw IOException("cannot keep page $entryPath of $archivePath")
        }

    /**
     * Page [pagePath] of the PDF at [pdfPath] of [sourceKey] as a local
     * picture, drawn first by [render] if needed.
     */
    suspend fun pdfPage(sourceKey: String, pdfPath: String, pagePath: String, render: suspend (File) -> Unit): File =
        cached(sourceKey, pdfPath, cacheName("$sourceKey\u0000$pdfPath\u0000$pagePath", pagePath)) { target ->
            target.parentFile?.mkdirs()
            val partial = File(target.path + PARTIAL_SUFFIX)
            render(partial)
            if (!partial.renameTo(target)) throw IOException("cannot keep page $pagePath of $pdfPath")
        }

    /**
     * The cache file [name] for a file at [path] of [sourceKey], made by [fetch]
     * unless it is there; in the locked folder when [path] is in a locked folder.
     */
    private suspend fun cached(sourceKey: String, path: String, name: String, fetch: suspend (File) -> Unit): File {
        val locked = lockedFolders.isLocked(sourceKey, path)
        val target = File(if (locked) lockedDir else dir, name)
        return copies.getOrPut(name) { Mutex() }.withLock {
            withContext(io) {
                if (target.isFile) {
                    target.setLastModified(System.currentTimeMillis())
                } else {
                    fetch(target)
                    if (!locked) trimToSize(cachedFiles(keep = target), MAX_CACHE_BYTES, keep = target)
                }
                target
            }
        }
    }

    /** Deletes the copies of pictures from locked folders. */
    suspend fun forgetLocked() {
        withContext(io) { lockedDir.deleteRecursively() }
    }

    private suspend fun copy(sourceKey: String, path: String, target: File, onProgress: (Float?) -> Unit) {
        target.parentFile?.mkdirs()
        val partial = File(target.path + PARTIAL_SUFFIX)
        val job = currentCoroutineContext().job
        val listener = object : TransferListener {
            override fun onProgress(copied: ULong, total: ULong?): Boolean {
                onProgress(total?.takeIf { it > 0u }?.let { copied.toFloat() / it.toFloat() })
                // Returning false makes the copy stop with Cancelled; the partial file is continued next time.
                return job.isActive
            }
        }
        try {
            sources.withConnection(sourceKey) { downloadFile(sourceKey, path, partial.absolutePath, listener) }
        } catch (e: CoreException.Cancelled) {
            currentCoroutineContext().ensureActive()
            throw e
        }
        if (!partial.renameTo(target)) throw IOException("cannot keep the copy of $path")
    }

    /** The cache's files, leaving out partial copies still being written. */
    private fun cachedFiles(keep: File): List<File> = dir.listFiles().orEmpty().filter { file ->
        file == keep || copies[file.name.removeSuffix(PARTIAL_SUFFIX)]?.isLocked != true
    }

    /** A name unique to [identity], keeping the extension of [path] for the decoder. */
    private fun cacheName(identity: String, path: String): String {
        val hash = MessageDigest.getInstance("SHA-1").digest(identity.toByteArray()).toHex()
        val extension = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        return if (extension.isEmpty()) hash else "$hash.$extension"
    }

    private companion object {
        const val MAX_CACHE_BYTES = 256L * 1024 * 1024
        const val PARTIAL_SUFFIX = ".part"
    }
}

/**
 * Deletes the least recently used of [files] (by modification time) until the
 * rest fit in [maxBytes]. [keep], the file just added, always stays.
 */
internal fun trimToSize(files: List<File>, maxBytes: Long, keep: File) {
    var total = 0L
    files.sortedByDescending { it.lastModified() }.forEach { file ->
        val size = file.length()
        if (file != keep && total + size > maxBytes) {
            file.delete()
        } else {
            total += size
        }
    }
}
