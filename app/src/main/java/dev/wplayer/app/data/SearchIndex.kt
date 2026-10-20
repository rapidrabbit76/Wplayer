package dev.wplayer.app.data

import dev.wplayer.app.data.local.IndexedEntryEntity
import dev.wplayer.app.data.local.SearchIndexDao
import dev.wplayer.app.data.local.escapeLike
import dev.wplayer.app.data.model.MediaEntry
import dev.wplayer.app.data.model.MediaKind
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * File names seen in folder listings, kept in the database so a search below
 * a folder answers at once instead of listing every subfolder over the network.
 * Every listing updates it; a search refreshes the folders it covers.
 */
@Singleton
class SearchIndex @Inject constructor(private val dao: SearchIndexDao) {
    /** Writes [folder]'s fresh listing, dropping the subtrees of folders that are gone. */
    suspend fun record(sourceKey: String, folder: String, entries: List<MediaEntry>, nowMillis: Long = System.currentTimeMillis()) {
        val previousFolders = dao.children(sourceKey, folder).filter { it.isDirectory }.map { it.path }.toSet()
        val currentFolders = entries.filter { it.kind == MediaKind.DIRECTORY }.map { it.path }.toSet()
        dao.replaceFolder(
            sourceKey = sourceKey,
            folder = folder,
            entries = entries.map { it.toIndexed(sourceKey, folder) },
            removedFolders = (previousFolders - currentFolders).toList(),
            listedAtMillis = nowMillis,
        )
    }

    /** [folder]'s subfolders from the index if it was listed within [maxAgeMillis]; null when it must be listed. */
    suspend fun freshSubfolders(sourceKey: String, folder: String, maxAgeMillis: Long, nowMillis: Long = System.currentTimeMillis()): List<String>? {
        val listedAt = dao.listedAt(sourceKey, folder) ?: return null
        if (nowMillis - listedAt > maxAgeMillis) return null
        return dao.children(sourceKey, folder).filter { it.isDirectory }.map { it.path }
    }

    /** Indexed entries below [root] whose names contain [query], shallowest first; updates as the index changes. */
    fun search(sourceKey: String, root: String, query: String, limit: Int): Flow<List<MediaEntry>> {
        val below = if (root == "/") "/%" else escapeLike(root.trimEnd('/')) + "/%"
        val name = "%" + escapeLike(searchKey(query)) + "%"
        return dao.search(sourceKey, below, name, limit).map { rows -> rows.map { it.toEntry() } }
    }

    suspend fun forgetSource(sourceKey: String) = dao.deleteSource(sourceKey)

    suspend fun clear() = dao.clear()

    private fun MediaEntry.toIndexed(sourceKey: String, folder: String) = IndexedEntryEntity(
        sourceKey = sourceKey,
        path = path,
        parentPath = folder,
        name = name,
        nameKey = searchKey(name),
        isDirectory = kind == MediaKind.DIRECTORY,
        size = size,
        modifiedAtMillis = modifiedAtMillis,
    )

    private fun IndexedEntryEntity.toEntry() =
        MediaEntry(name = name, path = path, kind = MediaEntry.kindOf(name, isDirectory), size = size, modifiedAtMillis = modifiedAtMillis)
}
