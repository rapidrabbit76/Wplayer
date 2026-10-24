package dev.wplayer.app.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert

/** A subtitle file picked by hand for a video, loaded again the next time it plays. */
@Entity(tableName = "subtitle_choices", primaryKeys = ["mediaKey"])
data class SubtitleChoiceEntity(
    /** The video's source key and path. */
    val mediaKey: String,
    /** The subtitle's path in the same source. */
    val subtitlePath: String,
    val chosenAtMillis: Long,
)

@Dao
interface SubtitleChoiceDao {
    @Query("SELECT * FROM subtitle_choices WHERE mediaKey = :mediaKey")
    suspend fun get(mediaKey: String): SubtitleChoiceEntity?

    @Upsert
    suspend fun upsert(choice: SubtitleChoiceEntity)

    @Query(
        """
        DELETE FROM subtitle_choices WHERE mediaKey NOT IN
        (SELECT mediaKey FROM subtitle_choices ORDER BY chosenAtMillis DESC LIMIT :keep)
        """,
    )
    suspend fun trim(keep: Int)
}
