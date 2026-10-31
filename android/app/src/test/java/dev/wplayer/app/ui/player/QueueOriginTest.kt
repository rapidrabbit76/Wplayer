package dev.wplayer.app.ui.player

import dev.wplayer.app.data.model.BrowserSettings
import dev.wplayer.app.data.model.SortField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class QueueOriginTest {
    private val byName = BrowserSettings()
    private fun play(path: String, queue: PlayQueue = PlayQueue.Folder) = PlaybackRequest.FromSource("s", path, path, queue)

    @Test
    fun `the next file in the same folder and order keeps the loaded queue`() {
        assertEquals(QueueOrigin.of(play("/show/1.mkv"), byName), QueueOrigin.of(play("/show/2.mkv"), byName))
    }

    @Test
    fun `a new sort lists the folder again`() {
        val newestFirst = byName.copy(sortField = SortField.DATE, ascending = false)
        assertNotEquals(QueueOrigin.of(play("/show/1.mkv"), byName), QueueOrigin.of(play("/show/2.mkv"), newestFirst))
    }

    @Test
    fun `another folder lists again`() {
        assertNotEquals(QueueOrigin.of(play("/show/1.mkv"), byName), QueueOrigin.of(play("/film/1.mkv"), byName))
    }

    @Test
    fun `a playlist keeps its own order whatever the browser sort`() {
        val playlist = PlayQueue.Playlist(7)
        val bySize = byName.copy(sortField = SortField.SIZE)
        assertEquals(QueueOrigin.of(play("/a.mkv", playlist), byName), QueueOrigin.of(play("/b.mkv", playlist), bySize))
    }
}
