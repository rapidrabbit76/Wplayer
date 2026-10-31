package dev.wplayer.app.ui.player

import dev.wplayer.app.data.model.BrowserSettings
import dev.wplayer.app.data.model.MediaKind
import dev.wplayer.app.data.model.MediaRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Playlists and the recent list keep files and links as (source key, path); playing them gives back the right request. */
class StoredRequestTest {
    private val file = PlaybackRequest.FromSource("source-1", "/video/a.mkv", "a.mkv", PlayQueue.Playlist(3))
    private val link = PlaybackRequest.FromLink("https://video.example.test/watch/1", "A clip", PlayQueue.Playlist(3))

    @Test
    fun `stored files and links play as the request they were stored from`() {
        for (request in listOf(file, link)) {
            val stored = request.storedRef!!
            assertEquals(request, stored.toPlaybackRequest(PlayQueue.Playlist(3)))
            assertEquals(request.mediaKey, stored.mediaKey)
        }
    }

    @Test
    fun `a stored link is a link, never a file in a source`() {
        val stored = link.storedRef!!

        assertEquals(MediaKind.LINK, stored.kind)
        assertEquals(MediaKind.VIDEO, file.storedRef!!.kind)
        assertEquals(PlaybackRequest.FromLink(link.url, link.title), stored.toPlaybackRequest())
    }

    @Test
    fun `a link outside a playlist has no queue, a file plays in its folder`() {
        val ref = MediaRef("source-1", "/video/a.mkv", "a.mkv", isDirectory = false)

        assertEquals(PlaybackRequest.FromSource("source-1", "/video/a.mkv", "a.mkv", PlayQueue.Folder), ref.toPlaybackRequest())
        assertNull(QueueOrigin.of(link.copy(playlist = null), BrowserSettings()))
        assertEquals(QueueOrigin.Playlist(3), QueueOrigin.of(link, BrowserSettings()))
    }
}
