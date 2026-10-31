package dev.wplayer.app.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.wplayer.app.data.local.AppDatabase
import dev.wplayer.app.data.model.MediaRef
import dev.wplayer.app.data.model.mediaKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class PlaybackHistoryForgetTest {
    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    @After
    fun close() = database.close()

    @Test
    fun forgettingAFolderForgetsEveryFileBelowItButNotItsNamesakes() = runTest {
        val history = PlaybackHistoryRepository(
            database.playbackPositionDao(),
            database.readingPositionDao(),
            database.recentMediaDao(),
            database.subtitleChoiceDao(),
            LockedFolders(database.lockedFolderDao(), backgroundScope),
        )
        val paths = listOf("/시즌_1/e01.mkv", "/시즌_1/자막/e02.mkv", "/시즌x1/e01.mkv", "/시즌_1 외전/e01.mkv")
        for (path in paths) {
            history.save(mediaKey(SOURCE, path), positionSeconds = 300.0, durationSeconds = 3_000.0)
            history.recordPlayed(MediaRef(SOURCE, path, path.substringAfterLast('/'), isDirectory = false))
        }

        history.forget(MediaRef(SOURCE, "/시즌_1", "시즌_1", isDirectory = true))

        assertNull(history.resumePosition(mediaKey(SOURCE, "/시즌_1/e01.mkv")))
        assertNull(history.resumePosition(mediaKey(SOURCE, "/시즌_1/자막/e02.mkv")))
        assertNotNull(history.resumePosition(mediaKey(SOURCE, "/시즌x1/e01.mkv")))
        assertNotNull(history.resumePosition(mediaKey(SOURCE, "/시즌_1 외전/e01.mkv")))
        assertEquals(
            listOf("/시즌_1 외전/e01.mkv", "/시즌x1/e01.mkv"),
            history.recent().first().map { it.ref.path }.sorted(),
        )
    }

    private companion object {
        const val SOURCE = "source-1"
    }
}
