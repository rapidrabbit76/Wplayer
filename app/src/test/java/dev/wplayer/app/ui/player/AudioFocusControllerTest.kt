package dev.wplayer.app.ui.player

import android.app.Application
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class AudioFocusControllerTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val audio = shadowOf(app.getSystemService(AudioManager::class.java))
    private val events = mutableListOf<String>()
    private val controller = AudioFocusController(app).apply {
        setListener(
            object : AudioFocusController.Listener {
                override fun onPause() { events += "pause" }
                override fun onResume() { events += "resume" }
                override fun onDuck(ducked: Boolean) = Unit
            },
        )
    }

    private fun focusChange(change: Int) = audio.lastAudioFocusRequest.listener.onAudioFocusChange(change)

    private fun noisyReceiverRegistered() = shadowOf(app).registeredReceivers.any {
        it.intentFilter.hasAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
    }

    @Test
    fun `closing the player during a call gives up focus and the headphone receiver`() {
        assertTrue(controller.acquire())
        focusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        controller.release()

        controller.close()

        assertNotNull(audio.lastAbandonedAudioFocusRequest)
        assertFalse(noisyReceiverRegistered())
    }

    @Test
    fun `playback paused by a call still resumes when the call ends`() {
        assertTrue(controller.acquire())
        focusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        controller.release()

        assertNull(audio.lastAbandonedAudioFocusRequest)
        assertTrue(noisyReceiverRegistered())
        focusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(listOf("pause", "resume"), events)
    }
}
