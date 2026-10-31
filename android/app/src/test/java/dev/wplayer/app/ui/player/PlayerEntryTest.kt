package dev.wplayer.app.ui.player

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.wplayer.app.ui.player.PlaybackRequest.Companion.writeTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Other apps open files and links in the player, but cannot reach saved sources or hand mpv other protocols. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class PlayerEntryTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val lockedFile = PlaybackRequest.FromSource("smb-1", "/Private/movie.mkv", "movie.mkv")

    /** The entry Android picks for another app's `VIEW` intent, as the chooser would. */
    private fun entryFor(intent: Intent): ActivityInfo {
        val matches = app.packageManager.queryIntentActivities(intent, 0).filter { it.activityInfo.packageName == app.packageName }
        assertEquals("one Wplayer entry for $intent", 1, matches.size)
        return matches.single().activityInfo
    }

    /** What the player receives when another app's intent arrives through [entry]. */
    private fun delivered(intent: Intent, entry: ActivityInfo): Intent =
        intent.setComponent(ComponentName(entry.packageName, entry.name))

    @Test
    fun `files and links from other apps open the player`() {
        val shared = listOf(
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://media/external/video/media/12"), "video/mp4"),
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("file:///sdcard/Movies/a.mkv"), "video/x-matroska"),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/live/index.m3u8")).addCategory(Intent.CATEGORY_BROWSABLE),
        )
        for (intent in shared) {
            val entry = entryFor(intent)
            assertEquals(PlayerActivity::class.java.name, entry.targetActivity)
            assertTrue(entry.exported)
            assertEquals(intent.data, (PlaybackRequest.from(delivered(intent, entry)) as PlaybackRequest.FromUri).uri)
        }
    }

    @Test
    fun `other apps cannot start the player itself`() {
        // Robolectric does not implement the ComponentInfoFlags overload.
        @Suppress("DEPRECATION")
        val player = app.packageManager.getActivityInfo(ComponentName(app, PlayerActivity::class.java), 0)
        assertFalse(player.exported)
    }

    @Test
    fun `another app naming a file in a saved source is ignored`() {
        val video = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://media/external/video/media/12"), "video/mp4")
        val entry = entryFor(video)

        val withoutFile = lockedFile.writeTo(Intent(Intent.ACTION_VIEW))
        assertNull(PlaybackRequest.from(delivered(withoutFile, entry)))
        val alongsideFile = lockedFile.writeTo(Intent(video))
        assertEquals(PlaybackRequest.FromUri(video.data!!, "12"), PlaybackRequest.from(delivered(alongsideFile, entry)))
    }

    @Test
    fun `the app's own player intent opens a file in a saved source`() {
        assertEquals(lockedFile, PlaybackRequest.from(PlayerActivity.createIntent(app, lockedFile)))
    }

    @Test
    fun `only files and web links reach the engine`() {
        val entry = entryFor(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/a.mp4")).addCategory(Intent.CATEGORY_BROWSABLE))
        for (uri in listOf("fdclose://3", "edl://%10%/proc/self/fd/3", "av://lavfi:anullsrc", "smb://nas/share/a.mkv", "javascript:alert(1)")) {
            assertNull(uri, PlaybackRequest.from(delivered(Intent(Intent.ACTION_VIEW, Uri.parse(uri)), entry)))
        }
        assertEquals(
            PlaybackRequest.FromUri(Uri.parse("https://example.com/a.mp4"), "a.mp4"),
            PlaybackRequest.from(delivered(Intent(Intent.ACTION_VIEW, Uri.parse("HTTPS://example.com/a.mp4")), entry)),
        )
    }

    @Test
    fun `a page shared as text opens as a link`() {
        val share = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "Watch this https://video.example.test/watch/42 now")
        val entry = entryFor(share)

        assertEquals(PlayerActivity::class.java.name, entry.targetActivity)
        val link = "https://video.example.test/watch/42"
        assertEquals(PlaybackRequest.FromLink(link, link), PlaybackRequest.from(delivered(share, entry)))
        assertNull(PlaybackRequest.from(delivered(Intent(share).putExtra(Intent.EXTRA_TEXT, "no address"), entry)))
    }

    @Test
    fun `the app's own link intent keeps its title and playlist`() {
        val link = PlaybackRequest.FromLink("https://video.example.test/watch/42", "Clip", PlayQueue.Playlist(7))

        assertEquals(link, PlaybackRequest.from(PlayerActivity.createIntent(app, link)))
    }

    @Test
    fun `another app's link extras are ignored`() {
        val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "nothing")
        val entry = entryFor(share)
        val forged = PlaybackRequest.FromLink("https://video.example.test/watch/1", "Clip").writeTo(Intent(share))

        assertNull(PlaybackRequest.from(delivered(forged, entry)))
    }
}
