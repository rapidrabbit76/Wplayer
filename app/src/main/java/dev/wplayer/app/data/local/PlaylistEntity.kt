package dev.wplayer.app.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAtMillis: Long,
)

/** A file to play, or a folder kept as a bookmark. */
@Entity(
    tableName = "playlist_items",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("playlistId")],
)
data class PlaylistItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val sourceKey: String,
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sortOrder: Int,
)

/** A playlist row with its item count, for the playlist list. */
data class PlaylistSummary(val id: Long, val name: String, val itemCount: Int)

@Dao
interface PlaylistDao {
    @Query(
        """
        SELECT p.id, p.name, COUNT(i.id) AS itemCount FROM playlists p
        LEFT JOIN playlist_items i ON i.playlistId = p.id
        GROUP BY p.id ORDER BY p.createdAtMillis
        """,
    )
    fun observeSummaries(): Flow<List<PlaylistSummary>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun observe(id: Long): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY sortOrder")
    fun observeItems(playlistId: Long): Flow<List<PlaylistItemEntity>>

    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY sortOrder")
    suspend fun items(playlistId: Long): List<PlaylistItemEntity>

    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun lastOrder(playlistId: Long): Int

    @Query("SELECT COUNT(*) FROM playlist_items WHERE playlistId = :playlistId AND sourceKey = :sourceKey AND path = :path")
    suspend fun countOf(playlistId: Long, sourceKey: String, path: String): Int

    @Insert
    suspend fun insertItems(items: List<PlaylistItemEntity>)

    @Query("DELETE FROM playlist_items WHERE id = :itemId")
    suspend fun deleteItem(itemId: Long)

    @Query("UPDATE playlist_items SET sortOrder = :sortOrder WHERE id = :itemId")
    suspend fun setOrder(itemId: Long, sortOrder: Int)

    /** Appends entries not already in the playlist, keeping their order. Returns how many were added. */
    @Transaction
    suspend fun append(playlistId: Long, entries: List<PlaylistItemEntity>): Int {
        var order = lastOrder(playlistId)
        val fresh = entries
            .filter { countOf(playlistId, it.sourceKey, it.path) == 0 }
            .distinctBy { it.sourceKey to it.path }
            .map { it.copy(playlistId = playlistId, sortOrder = ++order) }
        insertItems(fresh)
        return fresh.size
    }

    /** Rewrites the order to match [itemIds]. */
    @Transaction
    suspend fun reorder(itemIds: List<Long>) {
        itemIds.forEachIndexed { index, id -> setOrder(id, index) }
    }
}

@Entity(tableName = "recent_media", primaryKeys = ["sourceKey", "path"])
data class RecentMediaEntity(
    val sourceKey: String,
    val path: String,
    val name: String,
    val playedAtMillis: Long,
)

@Dao
interface RecentMediaDao {
    @Query("SELECT * FROM recent_media ORDER BY playedAtMillis DESC LIMIT :limit")
    fun observeLatest(limit: Int): Flow<List<RecentMediaEntity>>

    @Upsert
    suspend fun upsert(entry: RecentMediaEntity)

    @Query(
        """
        DELETE FROM recent_media WHERE playedAtMillis NOT IN
        (SELECT playedAtMillis FROM recent_media ORDER BY playedAtMillis DESC LIMIT :keep)
        """,
    )
    suspend fun trim(keep: Int)

    @Query("DELETE FROM recent_media WHERE sourceKey = :sourceKey AND path = :path")
    suspend fun delete(sourceKey: String, path: String)

    @Query("DELETE FROM recent_media WHERE sourceKey = :sourceKey AND path LIKE :pathPattern ESCAPE '\\'")
    suspend fun deleteLike(sourceKey: String, pathPattern: String)

    @Query("DELETE FROM recent_media")
    suspend fun clear()
}
