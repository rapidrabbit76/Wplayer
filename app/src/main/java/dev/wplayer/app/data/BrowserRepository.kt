package dev.wplayer.app.data

import android.util.Log
import android.util.LruCache
import dev.wplayer.app.data.model.BrowserSettings
import dev.wplayer.app.data.model.MediaEntry
import dev.wplayer.app.data.model.MediaKind
import dev.wplayer.app.data.model.MediaRef
import dev.wplayer.core.ffi.listDirectory
import dev.wplayer.core.ffi.mediaUri
import dev.wplayer.core.ffi.subtitleUri as nativeSubtitleUri
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Matches found so far by [BrowserRepository.search]. */
data class SearchProgress(
    val matches: List<MediaEntry>,
    /** The index below the searched folder has been refreshed. */
    val finished: Boolean,
    /** Stopped at the depth, folder or result limit; more matches may exist. */
    val truncated: Boolean,
)

/** How far [BrowserRepository.refreshIndex] got. */
data class IndexRefresh(val foldersVisited: Int, val stoppedEarly: Boolean)

const val MAX_SEARCH_RESULTS = 500
const val MAX_SEARCH_DEPTH = 10
private const val MAX_SEARCH_FOLDERS = 5_000
private const val MAX_CACHED_FOLDERS = 200
/** Folders listed this recently are searched from the index without asking the server again. */
private const val SEARCH_REFRESH_AGE_MILLIS = 15 * 60 * 1000L
/** Folders listed at once while crawling; FTP and NFS sources serialize them themselves. */
private const val CRAWL_PARALLELISM = 4
private const val TAG = "BrowserRepository"

/**
 * Hidden files, and the bookkeeping folders file servers put in every share
 * (Synology thumbnails, recycle bins, snapshots), which a crawl never enters.
 */
internal fun isHiddenName(name: String): Boolean = name.startsWith('.') || name in SERVER_SYSTEM_FOLDERS

private val SERVER_SYSTEM_FOLDERS = setOf("@eaDir", "#recycle", "@Recycle", "#snapshot", "@Recently-Snapshot", "\$RECYCLE.BIN", "System Volume Information")

/**
 * Picks subtitle files belonging to [videoName]: `Movie.smi`, `Movie.ko.srt`,
 * but not `Movie 2.smi` or `Movie.E10.srt` for `Movie.E1.mkv`. Case-insensitive.
 * Korean-tagged files sort first so they are selected by default.
 */
internal fun matchSubtitles(videoName: String, candidates: Collection<String>): List<String> {
    val base = videoName.substringBeforeLast('.').lowercase()
    return candidates
        .filter { name ->
            val lower = name.lowercase()
            MediaEntry.kindOf(name, isDirectory = false) == MediaKind.SUBTITLE && lower.startsWith("$base.")
        }
        .sortedWith(compareBy<String> { !it.isKoreanTagged() }.thenBy(NaturalOrderComparator) { it })
}

private val KOREAN_TAGS = setOf("ko", "kor", "kr", "korean", "한국어", "한글")

private fun String.isKoreanTagged(): Boolean =
    lowercase().split('.', '_', '-', ' ').any { it in KOREAN_TAGS } || endsWith(".smi", ignoreCase = true)

@Singleton
class BrowserRepository @Inject constructor(
    private val sources: SourceRepository,
    private val index: SearchIndex,
    private val lockedFolders: LockedFolders,
) {
    /** Folders listed this session, so reopening one shows it at once while it is listed again. */
    private val listings = LruCache<String, List<MediaEntry>>(MAX_CACHED_FOLDERS)

    /** Everything in the folder, unordered; see [arrange] for what the browser shows. */
    suspend fun list(sourceKey: String, path: String): List<MediaEntry> =
        sources.withConnection(sourceKey) { listDirectory(sourceKey, path) }.map {
            MediaEntry(
                name = it.name,
                path = it.path,
                kind = MediaEntry.kindOf(it.name, it.isDir),
                size = it.size.toLong(),
                modifiedAtMillis = it.modifiedEpochMs,
            )
        }.also { entries ->
            listings.put(listingKey(sourceKey, path), entries)
            recordInIndex(sourceKey, path, entries)
        }

    /** A listing that cannot be indexed (database full, say) is still a good listing. */
    private suspend fun recordInIndex(sourceKey: String, path: String, entries: List<MediaEntry>) {
        try {
            index.record(sourceKey, path, entries)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "cannot index $path", e)
        }
    }

    /** The folder as last listed this session, or null; possibly out of date, so list it again too. */
    fun cachedListing(sourceKey: String, path: String): List<MediaEntry>? = listings.get(listingKey(sourceKey, path))

    private fun listingKey(sourceKey: String, path: String) = "$sourceKey\u0000$path"

    /**
     * Entries below [root] whose names contain [query], shallowest first.
     *
     * Matches come from the [SearchIndex], so folders seen before answer at
     * once; meanwhile the folders below [root] are listed again (those not
     * refreshed within [SEARCH_REFRESH_AGE_MILLIS]) and the matches update as
     * the index does. [SearchProgress.finished] turns true when that refresh ends.
     */
    fun search(sourceKey: String, root: String, query: String, settings: BrowserSettings): Flow<SearchProgress> = channelFlow {
        val refresh = MutableStateFlow<IndexRefresh?>(null)
        launch {
            refresh.value = refreshIndex(
                sourceKey = sourceKey,
                root = root,
                maxDepth = MAX_SEARCH_DEPTH,
                maxAgeMillis = SEARCH_REFRESH_AGE_MILLIS,
                maxFolders = MAX_SEARCH_FOLDERS,
                includeHidden = settings.showHiddenFiles,
            )
        }
        combine(index.search(sourceKey, root, query, MAX_SEARCH_RESULTS), refresh, lockedFolders.state) { found, done, locks ->
            SearchProgress(
                matches = found.filter { entry ->
                    entry.isShown(settings) &&
                        (settings.showHiddenFiles || !entry.isInHiddenFolder(root)) &&
                        !locks.isHidden(sourceKey, entry.path)
                },
                finished = done != null,
                truncated = found.size >= MAX_SEARCH_RESULTS || done?.stoppedEarly == true,
            )
        }.collect { send(it) }
    }

    /**
     * Brings the index below [root] up to date: lists, [CRAWL_PARALLELISM] at a
     * time, each folder not listed within [maxAgeMillis] (fresher ones are read
     * from the index), level by level down to [maxDepth] or [maxFolders] folders.
     * Folders that cannot be read are skipped.
     */
    suspend fun refreshIndex(
        sourceKey: String,
        root: String,
        maxDepth: Int,
        maxAgeMillis: Long,
        maxFolders: Int,
        includeHidden: Boolean,
    ): IndexRefresh = coroutineScope {
        val permits = Semaphore(CRAWL_PARALLELISM)
        var level = listOf(root)
        var depth = 0
        var visited = 0
        while (level.isNotEmpty() && depth <= maxDepth && visited < maxFolders) {
            val batch = level.take(maxFolders - visited)
            visited += batch.size
            level = batch
                .map { folder -> async { permits.withPermit { subfolders(sourceKey, folder, maxAgeMillis) } } }
                .awaitAll()
                .flatten()
                .filter { includeHidden || !isHiddenName(it.substringAfterLast('/')) }
            depth++
        }
        IndexRefresh(foldersVisited = visited, stoppedEarly = level.isNotEmpty())
    }

    private suspend fun subfolders(sourceKey: String, folder: String, maxAgeMillis: Long): List<String> =
        index.freshSubfolders(sourceKey, folder, maxAgeMillis)
            ?: try {
                list(sourceKey, folder).filter { it.kind == MediaKind.DIRECTORY }.map { it.path }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }

    /** Whether a folder between [root] and this entry is hidden, so the entry is too. */
    private fun MediaEntry.isInHiddenFolder(root: String): Boolean =
        path.removePrefix(root.trimEnd('/')).split('/').dropLast(1).any(::isHiddenName)

    /** Playable files in [folder], in the order the browser shows them. */
    suspend fun playableFiles(sourceKey: String, folder: String, settings: BrowserSettings): List<MediaRef> =
        arrange(list(sourceKey, folder), settings)
            .filter { it.isPlayable }
            .map { MediaRef(sourceKey, it.path, it.name, isDirectory = false) }

    /** Subtitles for [videoPath] in the same folder, best match first (see [matchSubtitles]). */
    suspend fun siblingSubtitles(sourceKey: String, videoPath: String): List<MediaEntry> {
        val dir = videoPath.substringBeforeLast('/', "").ifEmpty { "/" }
        val names = sources.withConnection(sourceKey) { listDirectory(sourceKey, dir) }
            .filter { !it.isDir }
            .associateBy { it.name }
        return matchSubtitles(videoPath.substringAfterLast('/'), names.keys).map { name ->
            val entry = names.getValue(name)
            MediaEntry(entry.name, entry.path, MediaKind.SUBTITLE, entry.size.toLong(), entry.modifiedEpochMs)
        }
    }

    /** Re-establishes a source whose connection went stale. */
    suspend fun reconnect(sourceKey: String) = sources.reconnect(sourceKey)

    suspend fun playbackUri(sourceKey: String, path: String): String {
        sources.ensureConnected(sourceKey)
        return mediaUri(sourceKey, path)
    }

    /** URI for an external text subtitle; the core re-encodes it (e.g. CP949 SMI) to UTF-8. */
    suspend fun subtitleUri(sourceKey: String, path: String): String {
        sources.ensureConnected(sourceKey)
        return nativeSubtitleUri(sourceKey, path)
    }
}
