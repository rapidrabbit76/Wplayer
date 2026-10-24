package dev.wplayer.app.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.wplayer.app.data.local.AppDatabase
import dev.wplayer.app.data.model.MediaRef
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The page a comic archive was left on comes back, apart from video watch progress. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class ReadingPositionTest {
    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val history = PlaybackHistoryRepository(
        positions = database.playbackPositionDao(),
        readingPositions = database.readingPositionDao(),
        recents = database.recentMediaDao(),
        subtitleChoices = database.subtitleChoiceDao(),
        lockedFolders = LockedFolders(database.lockedFolderDao(), TestScope()),
    )
    private val book = MediaRef("nas", "/만화/원피스 01권.cbz", "원피스 01권.cbz", isDirectory = false)

    @After
    fun close() = database.close()

    @Test
    fun theLastSavedPageComesBack() = runTest {
        assertNull(history.readingPage(book.mediaKey))
        history.saveReadingPage(book.mediaKey, "원피스/005.jpg")
        history.saveReadingPage(book.mediaKey, "원피스/012.jpg")
        assertEquals("원피스/012.jpg", history.readingPage(book.mediaKey))
    }

    @Test
    fun aBookLeftHalfReadIsNotWatchProgress() = runTest {
        history.saveReadingPage(book.mediaKey, "원피스/012.jpg")
        assertEquals(emptyMap<String, Any>(), history.progress.first())
    }

    @Test
    fun forgettingTheBookOrAllHistoryForgetsThePage() = runTest {
        val other = MediaRef("nas", "/만화/원피스 02권.cbz", "원피스 02권.cbz", isDirectory = false)
        history.saveReadingPage(book.mediaKey, "원피스/012.jpg")
        history.saveReadingPage(other.mediaKey, "원피스/030.jpg")

        history.forget(book)
        assertNull(history.readingPage(book.mediaKey))
        assertEquals("원피스/030.jpg", history.readingPage(other.mediaKey))

        history.clearAll()
        assertNull(history.readingPage(other.mediaKey))
    }
}
