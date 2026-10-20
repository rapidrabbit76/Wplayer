package dev.wplayer.app.ui.player

import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlin.math.abs

/** What the media session (notification, lock screen, headset) shows. Times are in milliseconds. */
data class SessionSnapshot(
    val title: String = "",
    val loaded: Boolean = false,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val ended: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = C.TIME_UNSET,
    val speed: Float = 1f,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val seekBackMs: Long = 10_000,
    val seekForwardMs: Long = 10_000,
)

/** What session controllers (notification, lock screen, headset buttons) ask the player to do. */
interface SessionControls {
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun next()
    fun previous()
    fun stop()
}

/**
 * Presents the mpv engine as a Media3 [Player] so a MediaSession can expose it
 * to the system. The playlist holds the previous, current and next file only,
 * which is what next/previous buttons need.
 */
@OptIn(UnstableApi::class) // SimpleBasePlayer is Media3's documented way to adapt another player.
class MpvSessionPlayer(private val controls: SessionControls) : SimpleBasePlayer(Looper.getMainLooper()) {
    private var snapshot = SessionSnapshot()
    private var snapshotAtMs = SystemClock.elapsedRealtime()

    /**
     * Publishes [next]. Position-only changes are skipped while they match the
     * extrapolated position, so playback does not re-publish many times a second.
     */
    fun update(next: SessionSnapshot) {
        val now = SystemClock.elapsedRealtime()
        val expectedMs = snapshot.positionMs + if (snapshot.playing) ((now - snapshotAtMs) * snapshot.speed).toLong() else 0
        if (next.copy(positionMs = 0) == snapshot.copy(positionMs = 0) && abs(next.positionMs - expectedMs) < POSITION_TOLERANCE_MS) return
        snapshot = next
        snapshotAtMs = now
        invalidateState()
    }

    override fun getState(): State {
        val current = snapshot
        val items = buildList {
            if (current.hasPrevious) add(placeholder(PREVIOUS_UID))
            add(currentItem(current))
            if (current.hasNext) add(placeholder(NEXT_UID))
        }
        val commands = Player.Commands.Builder()
            .addAll(
                COMMAND_PLAY_PAUSE,
                COMMAND_STOP,
                COMMAND_GET_CURRENT_MEDIA_ITEM,
                COMMAND_GET_METADATA,
                COMMAND_GET_TIMELINE,
                COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                COMMAND_SEEK_BACK,
                COMMAND_SEEK_FORWARD,
            )
            .addIf(COMMAND_SEEK_TO_PREVIOUS, current.hasPrevious)
            .addIf(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, current.hasPrevious)
            .addIf(COMMAND_SEEK_TO_NEXT, current.hasNext)
            .addIf(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, current.hasNext)
            .build()
        val position = if (current.playing) {
            PositionSupplier.getExtrapolating(current.positionMs, current.speed)
        } else {
            PositionSupplier.getConstant(current.positionMs)
        }
        return State.Builder()
            .setAvailableCommands(commands)
            .setPlaylist(items)
            .setCurrentMediaItemIndex(if (current.hasPrevious) 1 else 0)
            .setPlayWhenReady(current.playing, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(
                when {
                    !current.loaded -> STATE_BUFFERING
                    current.ended -> STATE_ENDED
                    current.buffering -> STATE_BUFFERING
                    else -> STATE_READY
                },
            )
            .setContentPositionMs(position)
            .setPlaybackParameters(PlaybackParameters(current.speed))
            .setSeekBackIncrementMs(current.seekBackMs)
            .setSeekForwardIncrementMs(current.seekForwardMs)
            // "Previous" always means the previous file (or seeking back), like the on-screen button,
            // rather than Media3's default of restarting the current file after 3 seconds.
            .setMaxSeekToPreviousPositionMs(Long.MAX_VALUE / 2)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) controls.play() else controls.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val currentIndex = if (snapshot.hasPrevious) 1 else 0
        when {
            mediaItemIndex > currentIndex -> controls.next()
            mediaItemIndex < currentIndex -> controls.previous()
            positionMs != C.TIME_UNSET -> controls.seekTo(positionMs)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        controls.stop()
        return Futures.immediateVoidFuture()
    }

    private fun currentItem(current: SessionSnapshot): MediaItemData {
        val metadata = MediaMetadata.Builder().setTitle(current.title).build()
        return MediaItemData.Builder(CURRENT_UID)
            .setMediaItem(MediaItem.Builder().setMediaId(CURRENT_UID).setMediaMetadata(metadata).build())
            .setMediaMetadata(metadata)
            .setDurationUs(if (current.durationMs == C.TIME_UNSET) C.TIME_UNSET else current.durationMs * 1000)
            .setIsSeekable(current.durationMs != C.TIME_UNSET)
            .build()
    }

    private fun placeholder(uid: String) = MediaItemData.Builder(uid)
        .setMediaItem(MediaItem.Builder().setMediaId(uid).build())
        .build()

    private companion object {
        const val PREVIOUS_UID = "previous"
        const val CURRENT_UID = "current"
        const val NEXT_UID = "next"
        const val POSITION_TOLERANCE_MS = 1_500L
    }
}
