package dev.wplayer.app.ui.player

import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Publishes the player's media session: the media notification, lock-screen
 * controls and headset/Bluetooth buttons, and keeps the process alive while
 * sound plays in the background.
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {
    @Inject
    lateinit var sessions: MediaSessionHolder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        sessions.session?.let { if (!isSessionAdded(it)) addSession(it) }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = sessions.session

    /** Swiping the app away from recents stops background playback. */
    @OptIn(UnstableApi::class)
    override fun onTaskRemoved(rootIntent: Intent?) {
        pauseAllPlayersAndStopSelf()
    }
}
