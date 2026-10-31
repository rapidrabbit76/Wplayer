package dev.wplayer.app.data.local

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Updating the app keeps what the previous database held and adds the new
 * tables. The old database is built from its exported schema; Room checks the
 * migrated one against the current schema when it opens.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class AppDatabaseMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun version6To7KeepsWatchProgressAndAddsReadingPositions() = runTest {
        createDatabase(version = 6) { db ->
            db.execSQL("INSERT INTO playback_positions (mediaKey, positionSeconds, durationSeconds, updatedAtMillis) VALUES ('nas:/a.mkv', 60.0, 600.0, 1)")
        }
        val database = Room.databaseBuilder(context, AppDatabase::class.java, DB).allowMainThreadQueries().build()
        try {
            assertEquals(60.0, database.playbackPositionDao().get("nas:/a.mkv")?.positionSeconds)
            database.readingPositionDao().upsert(ReadingPositionEntity("nas:/b.cbz", "012.jpg", 2))
            assertEquals("012.jpg", database.readingPositionDao().get("nas:/b.cbz")?.pagePath)
        } finally {
            database.close()
        }
    }

    private fun createDatabase(version: Int, fill: (SQLiteDatabase) -> Unit) {
        val schema = JSONObject(File("schemas/${AppDatabase::class.java.name}/$version.json").readText()).getJSONObject("database")
        val file = context.getDatabasePath(DB).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.version = version
            fill(db)
        }
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
