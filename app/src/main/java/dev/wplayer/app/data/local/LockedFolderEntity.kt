package dev.wplayer.app.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** A folder that opens only after the device's own authentication. */
@Entity(tableName = "locked_folders", primaryKeys = ["sourceKey", "path"])
data class LockedFolderEntity(
    val sourceKey: String,
    val path: String,
)

@Dao
interface LockedFolderDao {
    @Query("SELECT * FROM locked_folders")
    fun observeAll(): Flow<List<LockedFolderEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(folder: LockedFolderEntity)

    @Query("DELETE FROM locked_folders WHERE sourceKey = :sourceKey AND path = :path")
    suspend fun delete(sourceKey: String, path: String)

    @Query("DELETE FROM locked_folders WHERE sourceKey = :sourceKey")
    suspend fun deleteSource(sourceKey: String)
}
