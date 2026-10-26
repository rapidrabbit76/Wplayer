package dev.wplayer.app.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert

/**
 * The page a comic archive was left on, opened there next time. Kept apart
 * from playback positions, which hold seconds and feed continue watching.
 */
@Entity(tableName = "reading_positions", primaryKeys = ["mediaKey"])
data class ReadingPositionEntity(
    /** The archive's source key and path. */
    val mediaKey: String,
    /** The page's path inside the archive. */
    val pagePath: String,
    val updatedAtMillis: Long,
)

@Dao
interface ReadingPositionDao {
    @Query("SELECT * FROM reading_positions WHERE mediaKey = :mediaKey")
    suspend fun get(mediaKey: String): ReadingPositionEntity?

    @Upsert
    suspend fun upsert(position: ReadingPositionEntity)

    @Query("DELETE FROM reading_positions WHERE mediaKey = :mediaKey")
    suspend fun delete(mediaKey: String)

    @Query("DELETE FROM reading_positions WHERE mediaKey LIKE :pattern ESCAPE '\\'")
    suspend fun deleteLike(pattern: String)

    @Query("DELETE FROM reading_positions")
    suspend fun clear()

    @Query(
        """
        DELETE FROM reading_positions WHERE mediaKey NOT IN
        (SELECT mediaKey FROM reading_positions ORDER BY updatedAtMillis DESC LIMIT :keep)
        """,
    )
    suspend fun trim(keep: Int)
}
