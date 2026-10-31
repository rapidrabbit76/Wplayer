package dev.wplayer.core.player

import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Surface
import dev.wplayer.core.NativeCore
import dev.wplayer.core.ffi.EndReason
import dev.wplayer.core.ffi.Player
import dev.wplayer.core.ffi.PlayerConfig
import dev.wplayer.core.ffi.PlayerEvent
import dev.wplayer.core.ffi.PlayerListener
import dev.wplayer.core.ffi.PropertyValue
import dev.wplayer.core.ffi.SeekMode
import dev.wplayer.core.ffi.Track
import dev.wplayer.core.ffi.TrackKind
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Kotlin-facing wrapper around one libmpv core.
 *
 * libmpv calls wait for the core, and a misbehaving hardware decoder can hold
 * the core indefinitely. Every command therefore runs, in order, on a private
 * thread: callers (typically the main thread) never block, and a stuck core
 * stalls only this engine. State arrives asynchronously through [state].
 */
class MpvEngine(config: PlayerConfig) : AutoCloseable {
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val commands: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mpv-commands").apply { isDaemon = true }
    }
    private val player: Player

    /** Touched only on the command thread. */
    private var surfaceRef = 0L

    init {
        NativeCore.ensureLoaded()
        player = Player(config, Listener())
    }

    /** Must be called on the thread that owns [surface] (the main thread). */
    fun attachSurface(surface: Surface) {
        val reference = SurfaceBridge.acquire(surface)
        val accepted = offer {
            try {
                detachCurrentSurface()
            } finally {
                surfaceRef = reference
            }
            player.attachSurface(reference)
        }
        if (!accepted) SurfaceBridge.release(reference)
    }

    fun setSurfaceSize(width: Int, height: Int) = post { player.setSurfaceSize(width.toUInt(), height.toUInt()) }

    fun detachSurface() = post { detachCurrentSurface() }

    private fun detachCurrentSurface() {
        if (surfaceRef == 0L) return
        try {
            player.detachSurface()
        } finally {
            SurfaceBridge.release(surfaceRef)
            surfaceRef = 0L
        }
    }

    fun load(uri: String, startSeconds: Double? = null) {
        _state.update { it.forNewFile() }
        post { player.load(uri, startSeconds) }
    }

    /** Plays an open file. The engine owns [file] from here on: libmpv closes it, or this does if libmpv never gets it. */
    fun load(file: ParcelFileDescriptor, startSeconds: Double? = null) {
        _state.update { it.forNewFile() }
        val accepted = offer {
            val fd = file.detachFd()
            try {
                player.load("fdclose://$fd", startSeconds)
            } catch (e: Exception) {
                ParcelFileDescriptor.adoptFd(fd).close()
                throw e
            }
        }
        if (!accepted) file.close()
    }

    fun stop() {
        _state.update { it.forNewFile() }
        post { player.stop() }
    }

    fun setPaused(paused: Boolean) = post { player.setPaused(paused) }

    fun togglePause() = setPaused(!state.value.paused)

    fun seekTo(seconds: Double, precise: Boolean = true) =
        post { player.seek(seconds.coerceAtLeast(0.0), SeekMode.ABSOLUTE, precise) }

    /** Exact by default: keyframe seeks can land many seconds away from `±seconds`. */
    fun seekBy(seconds: Double, precise: Boolean = true) = post { player.seek(seconds, SeekMode.RELATIVE, precise) }

    fun setSpeed(speed: Double) = post { player.setSpeed(speed) }

    fun setAbLoop(start: Double?, end: Double?) = post { player.setAbLoop(start, end) }

    fun selectTrack(kind: TrackKind, id: Long?) = post { player.selectTrack(kind, id) }

    fun addSubtitle(uri: String, select: Boolean) = post { player.addSubtitle(uri, select) }

    /** Adds a subtitle file that is still being written, or reloads it when already added. */
    fun showGrowingSubtitle(path: String, title: String, select: Boolean) = post { player.showGrowingSubtitle(path, title, select) }

    fun setSubtitleDelay(seconds: Double) = post { player.setSubtitleDelay(seconds) }

    /** Jumps to the next (`lines` > 0) or previous subtitle line. */
    fun stepSubtitle(lines: Int) = post { player.stepSubtitle(lines) }

    fun setProperty(name: String, value: String) = post { player.setProperty(name, value) }

    /**
     * Roughly how many video frames have been shown, for noticing a picture
     * that froze while the sound plays on. Does not wait for the core.
     */
    fun presentedFrames(): Long = runCatching { player.presentedFrames().toLong() }.getOrDefault(0)

    /** Reports [PlaybackStats] while [enabled]; they change often, so only for the stats overlay. */
    fun observeStats(enabled: Boolean) = post { player.observeStats(enabled) }

    /**
     * Shuts the core down in the background. If the core is stuck, its thread
     * is abandoned rather than blocking the caller.
     */
    override fun close() {
        post {
            player.use {
                try {
                    detachCurrentSurface()
                } finally {
                    it.shutdown()
                }
            }
        }
        commands.shutdown()
    }

    /** Queues [block]; failures are logged, since nothing upstream awaits them. */
    private fun post(block: () -> Unit) {
        offer(block)
    }

    /** Like [post]; false when the engine is closed and [block] will never run. */
    private fun offer(block: () -> Unit): Boolean = try {
        commands.execute {
            try {
                block()
            } catch (e: Exception) {
                Log.w(TAG, "mpv command failed", e)
            }
        }
        true
    } catch (e: RejectedExecutionException) {
        false
    }

    private inner class Listener : PlayerListener {
        override fun onPropertyChanged(name: String, value: PropertyValue) {
            _state.update { it.with(name, value) }
        }

        override fun onTracksChanged(tracks: List<Track>) {
            _state.update { it.copy(tracks = tracks) }
        }

        override fun onEvent(event: PlayerEvent) {
            when (event) {
                PlayerEvent.FileLoaded -> _state.update { it.copy(fileLoaded = true, error = null) }
                PlayerEvent.PlaybackRestart -> _state.update { it.copy(playbackStarted = true) }
                is PlayerEvent.EndFile -> if (event.reason == EndReason.ERROR) {
                    _state.update { it.copy(error = event.error ?: "playback failed") }
                }
                else -> Unit
            }
        }
    }

    private companion object {
        const val TAG = "MpvEngine"
    }
}
