package dev.wplayer.app.ui.player

import android.app.Application
import android.os.Looper
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class MpvSessionPlayerTest {
    private class Recorder : SessionControls {
        val calls = mutableListOf<String>()
        override fun play() { calls += "play" }
        override fun pause() { calls += "pause" }
        override fun seekTo(positionMs: Long) { calls += "seek:$positionMs" }
        override fun next() { calls += "next" }
        override fun previous() { calls += "previous" }
        override fun stop() { calls += "stop" }
    }

    private fun player(snapshot: SessionSnapshot): Pair<MpvSessionPlayer, Recorder> {
        val recorder = Recorder()
        val player = MpvSessionPlayer(recorder)
        player.update(snapshot)
        shadowOf(Looper.getMainLooper()).idle()
        return player to recorder
    }

    @Test
    fun `next and previous buttons reach the queue, even well into the file`() {
        val (player, recorder) = player(
            SessionSnapshot(title = "a", loaded = true, playing = true, positionMs = 60_000, durationMs = 600_000, hasPrevious = true, hasNext = true),
        )
        assertTrue(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        player.seekToNext()
        player.seekToPrevious()
        assertEquals(listOf("next", "previous"), recorder.calls)
    }

    @Test
    fun `without neighbours next and previous are not offered`() {
        val (player, _) = player(SessionSnapshot(title = "a", loaded = true, durationMs = 600_000))
        assertFalse(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        assertFalse(player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS))
    }

    @Test
    fun `play pause and seeking map to the engine`() {
        val (player, recorder) = player(SessionSnapshot(title = "a", loaded = true, durationMs = 600_000))
        player.play()
        player.pause()
        player.seekTo(42_000)
        assertEquals(listOf("play", "pause", "seek:42000"), recorder.calls)
    }
}
