package dev.wplayer.app.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.wplayer.app.data.local.AppDatabase
import dev.wplayer.app.data.model.MediaEntry
import java.text.Normalizer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class SearchIndexTest {
    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val index = SearchIndex(database.searchIndexDao())

    @After
    fun close() = database.close()

    private fun dir(path: String) = MediaEntry(path.substringAfterLast('/'), path, MediaEntry.kindOf("", true), 0, null)
    private fun file(path: String) = MediaEntry(path.substringAfterLast('/'), path, MediaEntry.kindOf(path, false), 10, null)

    private suspend fun names(root: String, query: String) = index.search(SOURCE, root, query, 100).first().map { it.path }

    @Test
    fun findsNamesBelowTheFolderShallowestFirst() = runTest {
        index.record(SOURCE, "/", listOf(dir("/드라마"), dir("/영화"), file("/여왕 예고편.mp4")))
        index.record(SOURCE, "/드라마", listOf(dir("/드라마/눈물의 여왕"), file("/드라마/선재.mkv")))
        // Written on a Mac: Hangul decomposed into jamo.
        val decomposed = "/드라마/눈물의 여왕/" + Normalizer.normalize("눈물의 여왕 E01.mkv", Normalizer.Form.NFD)
        index.record(SOURCE, "/드라마/눈물의 여왕", listOf(file(decomposed)))

        assertEquals(listOf("/여왕 예고편.mp4", "/드라마/눈물의 여왕", decomposed), names("/", "여왕"))
        assertEquals(listOf("/드라마/눈물의 여왕", decomposed), names("/드라마", "여왕"))
        assertEquals(emptyList<String>(), names("/영화", "여왕"))
    }

    @Test
    fun wildcardsInTheQueryAreLiteral() = runTest {
        index.record(SOURCE, "/", listOf(file("/100% 완료.mp4"), file("/1000 완료.mp4"), file("/a_b.mkv"), file("/axb.mkv")))
        assertEquals(listOf("/100% 완료.mp4"), names("/", "100%"))
        assertEquals(listOf("/a_b.mkv"), names("/", "a_b"))
    }

    @Test
    fun aFolderThatIsGoneTakesItsSubtreeWithIt() = runTest {
        index.record(SOURCE, "/", listOf(dir("/old"), dir("/new")))
        index.record(SOURCE, "/old", listOf(dir("/old/deep"), file("/old/x.mkv")))
        index.record(SOURCE, "/old/deep", listOf(file("/old/deep/y.mkv")))

        index.record(SOURCE, "/", listOf(dir("/new")))

        assertEquals(emptyList<String>(), names("/", ".mkv"))
        assertNull(index.freshSubfolders(SOURCE, "/old", maxAgeMillis = Long.MAX_VALUE))
    }

    @Test
    fun aDeletedFileIsNoLongerFound() = runTest {
        index.record(SOURCE, "/드라마", listOf(file("/드라마/선재 01.mkv"), file("/드라마/선재 02.mkv")))
        index.forget(SOURCE, "/드라마/선재 01.mkv")
        assertEquals(listOf("/드라마/선재 02.mkv"), names("/", "선재"))
    }

    @Test
    fun recentListingsAreReusedOlderOnesAreNot() = runTest {
        index.record(SOURCE, "/", listOf(dir("/a"), file("/b.mkv")), nowMillis = 1_000)
        assertEquals(listOf("/a"), index.freshSubfolders(SOURCE, "/", maxAgeMillis = 500, nowMillis = 1_400))
        assertNull(index.freshSubfolders(SOURCE, "/", maxAgeMillis = 500, nowMillis = 1_600))
        assertNull(index.freshSubfolders(SOURCE, "/never-listed", maxAgeMillis = 500, nowMillis = 1_400))
    }

    private companion object {
        const val SOURCE = "source-1"
    }
}
