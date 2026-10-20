package dev.wplayer.app.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** A file or folder name from a listing, so searches below a folder need not list it again. */
@Entity(
    tableName = "search_index",
    primaryKeys = ["sourceKey", "path"],
    indices = [Index(value = ["sourceKey", "parentPath"])],
)
data class IndexedEntryEntity(
    val sourceKey: String,
    val path: String,
    val parentPath: String,
    val name: String,
    /** [name] NFC-normalized and lower-cased; what a search matches. */
    val nameKey: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedAtMillis: Long?,
)

/** When a folder's listing was last written to the index. */
@Entity(tableName = "indexed_folders", primaryKeys = ["sourceKey", "path"])
data class IndexedFolderEntity(
    val sourceKey: String,
    val path: String,
    val listedAtMillis: Long,
)

@Dao
abstract class SearchIndexDao {
    @Query("SELECT * FROM search_index WHERE sourceKey = :sourceKey AND parentPath = :folder")
    abstract suspend fun children(sourceKey: String, folder: String): List<IndexedEntryEntity>

    @Query("SELECT listedAtMillis FROM indexed_folders WHERE sourceKey = :sourceKey AND path = :folder")
    abstract suspend fun listedAt(sourceKey: String, folder: String): Long?

    /**
     * Entries whose path matches [pathPattern] and name key matches [namePattern]
     * (LIKE patterns, `\` escaping), shallowest first.
     */
    @Query(
        """
        SELECT * FROM search_index
        WHERE sourceKey = :sourceKey AND path LIKE :pathPattern ESCAPE '\' AND nameKey LIKE :namePattern ESCAPE '\'
        ORDER BY length(path) - length(replace(path, '/', '')), path
        LIMIT :limit
        """,
    )
    abstract fun search(sourceKey: String, pathPattern: String, namePattern: String, limit: Int): Flow<List<IndexedEntryEntity>>

    /**
     * Replaces [folder]'s entries with [entries]. The subtrees of folders that
     * are gone ([removedFolders]) go too, so their files stop matching.
     */
    @Transaction
    open suspend fun replaceFolder(
        sourceKey: String,
        folder: String,
        entries: List<IndexedEntryEntity>,
        removedFolders: List<String>,
        listedAtMillis: Long,
    ) {
        deleteChildren(sourceKey, folder)
        for (removed in removedFolders) {
            val below = escapeLike(removed) + "/%"
            deleteEntriesLike(sourceKey, below)
            deleteFoldersLike(sourceKey, below)
            deleteFolder(sourceKey, removed)
        }
        insertEntries(entries)
        upsertFolder(IndexedFolderEntity(sourceKey, folder, listedAtMillis))
    }

    @Query("DELETE FROM search_index WHERE sourceKey = :sourceKey AND parentPath = :folder")
    protected abstract suspend fun deleteChildren(sourceKey: String, folder: String)

    @Query("DELETE FROM search_index WHERE sourceKey = :sourceKey AND path LIKE :pattern ESCAPE '\\'")
    protected abstract suspend fun deleteEntriesLike(sourceKey: String, pattern: String)

    @Query("DELETE FROM indexed_folders WHERE sourceKey = :sourceKey AND path LIKE :pattern ESCAPE '\\'")
    protected abstract suspend fun deleteFoldersLike(sourceKey: String, pattern: String)

    @Query("DELETE FROM indexed_folders WHERE sourceKey = :sourceKey AND path = :folder")
    protected abstract suspend fun deleteFolder(sourceKey: String, folder: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertEntries(entries: List<IndexedEntryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsertFolder(folder: IndexedFolderEntity)

    @Transaction
    open suspend fun deleteSource(sourceKey: String) {
        deleteSourceEntries(sourceKey)
        deleteSourceFolders(sourceKey)
    }

    @Query("DELETE FROM search_index WHERE sourceKey = :sourceKey")
    protected abstract suspend fun deleteSourceEntries(sourceKey: String)

    @Query("DELETE FROM indexed_folders WHERE sourceKey = :sourceKey")
    protected abstract suspend fun deleteSourceFolders(sourceKey: String)

    @Transaction
    open suspend fun clear() {
        clearEntries()
        clearFolders()
    }

    @Query("DELETE FROM search_index")
    protected abstract suspend fun clearEntries()

    @Query("DELETE FROM indexed_folders")
    protected abstract suspend fun clearFolders()
}

/** Escapes LIKE wildcards in [text] for patterns declared with `ESCAPE '\'`. */
fun escapeLike(text: String): String =
    text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
