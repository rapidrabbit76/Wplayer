package dev.wplayer.app.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "playback_positions", primaryKeys = ["mediaKey"])
data class PlaybackPositionEntity(
    /** Source key and path, or the raw URI for media opened from other apps. */
    val mediaKey: String,
    val positionSeconds: Double,
    val durationSeconds: Double,
    val updatedAtMillis: Long,
)

@Dao
interface PlaybackPositionDao {
    @Query("SELECT * FROM playback_positions WHERE mediaKey = :mediaKey")
    suspend fun get(mediaKey: String): PlaybackPositionEntity?

    @Upsert
    suspend fun upsert(position: PlaybackPositionEntity)

    @Query("DELETE FROM playback_positions WHERE mediaKey = :mediaKey")
    suspend fun delete(mediaKey: String)

    @Query("DELETE FROM playback_positions")
    suspend fun clear()

    /** Every saved position; the table only holds unfinished files, so it stays small. */
    @Query("SELECT * FROM playback_positions")
    fun observeAll(): Flow<List<PlaybackPositionEntity>>
}
