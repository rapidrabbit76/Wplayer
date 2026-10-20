package dev.wplayer.app.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceKey: String,
    val path: String,
    val name: String,
    /** Absolute path of the file being written on device storage. */
    val destPath: String,
    val totalBytes: Long?,
    val downloadedBytes: Long,
    /** A [dev.wplayer.app.data.model.DownloadStatus] name. */
    val status: String,
    val error: String?,
    val createdAtMillis: Long,
)

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAtMillis DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun get(id: Long): DownloadEntity?

    @Query("SELECT destPath FROM downloads")
    suspend fun destPaths(): List<String>

    @Insert
    suspend fun insert(download: DownloadEntity): Long

    @Query("UPDATE downloads SET status = :status, error = :error WHERE id = :id")
    suspend fun setStatus(id: Long, status: String, error: String?)

    @Query("UPDATE downloads SET downloadedBytes = :downloaded, totalBytes = :total WHERE id = :id")
    suspend fun setProgress(id: Long, downloaded: Long, total: Long?)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM downloads WHERE status = :status")
    suspend fun deleteWithStatus(status: String)
}
