package dev.wplayer.app.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A user-added network source. The device storage source is built in and not stored. */
@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val protocol: String,
    val host: String,
    val port: Int?,
    val rootPath: String,
    val username: String?,
    val allowUntrustedCertificates: Boolean,
    /** Password sealed by [CredentialCipher] (Base64); never stored in plain text. */
    val encryptedPassword: String?,
)

@Dao
interface SourceDao {
    @Query("SELECT * FROM sources ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun get(id: Long): SourceEntity?

    @Upsert
    suspend fun upsert(source: SourceEntity): Long

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun delete(id: Long)
}
