package dev.wplayer.app.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.wplayer.app.data.local.AppDatabase
import dev.wplayer.app.data.model.MediaRef
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A link added to playlists by its address takes the page title once played; names it already had stay. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class PlaylistLinkNameTest {
    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val playlists = PlaylistRepository(database.playlistDao())
    private val url = "https://video.example.test/watch/1"
    private val other = "https://video.example.test/watch/2"

    private fun link(path: String, name: String) = MediaRef(SourceRepository.LINK_KEY, path, name, isDirectory = false)

    @After
    fun close() = database.close()

    @Test
    fun linksNamedByTheirAddressTakeTheTitle() = runTest {
        val unnamed = playlists.create("Later")
        val named = playlists.create("Picked")
        playlists.add(unnamed, listOf(link(url, url), link(other, other), MediaRef("source-1", url, url, isDirectory = false)))
        playlists.add(named, listOf(link(url, "My favourite")))

        playlists.nameUnnamed(link(url, "A clip"))

        assertEquals(listOf("A clip", other, url), playlists.items(unnamed).first().map { it.ref.name })
        assertEquals(listOf("My favourite"), playlists.items(named).first().map { it.ref.name })
    }
}
