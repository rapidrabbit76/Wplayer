package dev.wplayer.app.ui.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one media session of the app, for the player currently on screen (or
 * playing in the background). [PlaybackService] publishes it to the system,
 * which shows the media notification and routes headset buttons to it.
 */
@Singleton
class MediaSessionHolder @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    var session: MediaSession? = null
        private set

    fun attach(player: Player) {
        release()
        session = MediaSession.Builder(context, player)
            .setSessionActivity(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, PlayerActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()
        // Started while the player is in the foreground; Media3 promotes it to a foreground service while playing.
        context.startService(Intent(context, PlaybackService::class.java))
    }

    fun release() {
        session?.release()
        session = null
        context.stopService(Intent(context, PlaybackService::class.java))
    }
}
