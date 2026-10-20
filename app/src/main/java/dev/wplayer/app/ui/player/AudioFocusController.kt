package dev.wplayer.app.ui.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Audio focus and "becoming noisy" for the player: pauses for calls and other
 * apps' playback (resuming after a short interruption), lowers the volume
 * while ducked, and pauses when headphones are unplugged.
 */
class AudioFocusController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    interface Listener {
        fun onPause()
        fun onResume()
        fun onDuck(ducked: Boolean)
    }

    private val audio = context.getSystemService(AudioManager::class.java)
    private var listener: Listener? = null
    private var held = false
    private var resumeOnGain = false

    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build(),
        )
        .setOnAudioFocusChangeListener(::onFocusChange)
        .build()

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) listener?.onPause()
        }
    }

    fun setListener(listener: Listener) {
        this.listener = listener
    }

    /** Call when playback starts; false means another app (e.g. a call) keeps the focus. */
    fun acquire(): Boolean {
        resumeOnGain = false
        if (held) return true
        if (audio.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        held = true
        ContextCompat.registerReceiver(
            context,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        return true
    }

    /** Call when the user pauses or playback ends. Kept during a short interruption so playback can resume. */
    fun release() {
        if (!held || resumeOnGain) return
        held = false
        audio.abandonAudioFocusRequest(request)
        context.unregisterReceiver(noisyReceiver)
    }

    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnGain = false
                listener?.onPause()
                release()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                resumeOnGain = true
                listener?.onPause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> listener?.onDuck(true)
            AudioManager.AUDIOFOCUS_GAIN -> {
                listener?.onDuck(false)
                if (resumeOnGain) {
                    resumeOnGain = false
                    listener?.onResume()
                }
            }
        }
    }
}
