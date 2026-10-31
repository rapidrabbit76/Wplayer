package dev.wplayer.core.player

import dev.wplayer.core.ffi.PropertyValue
import dev.wplayer.core.ffi.Track

/** Numbers for the stats overlay; only reported while it is shown. Rates are per second. */
data class PlaybackStats(
    val fps: Double? = null,
    val containerFps: Double? = null,
    val droppedFrames: Long = 0,
    val decoderDroppedFrames: Long = 0,
    val videoBitrate: Double? = null,
    val audioBitrate: Double? = null,
    val cacheBytesPerSecond: Long? = null,
    val cachedSeconds: Double? = null,
    val audioCodec: String? = null,
    val pixelFormat: String? = null,
    /** Transfer function, e.g. `pq` for HDR10 or `bt.1886` for SDR. */
    val gamma: String? = null,
)

/** Snapshot of everything the player UI renders. Times are in seconds. */
data class PlaybackState(
    val fileLoaded: Boolean = false,
    /** Frames are flowing: set by mpv's playback-restart after a load or seek. */
    val playbackStarted: Boolean = false,
    val position: Double = 0.0,
    val duration: Double = 0.0,
    val paused: Boolean = true,
    val buffering: Boolean = false,
    val seeking: Boolean = false,
    val endReached: Boolean = false,
    val speed: Double = 1.0,
    val cachedUntil: Double = 0.0,
    val hardwareDecoder: String? = null,
    val videoCodec: String? = null,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val abLoopStart: Double? = null,
    val abLoopEnd: Double? = null,
    val subtitleDelay: Double = 0.0,
    /** The subtitle line on screen, empty when none is shown. */
    val subtitleText: String = "",
    val tracks: List<Track> = emptyList(),
    val stats: PlaybackStats = PlaybackStats(),
    val error: String? = null,
) {
    /**
     * State for a newly loaded file. Player-wide settings survive because mpv
     * only reports properties when they change, not again for each file.
     */
    fun forNewFile(): PlaybackState = PlaybackState(paused = paused, speed = speed, subtitleDelay = subtitleDelay)

    /** Applies one mpv property change; unknown properties are ignored. */
    internal fun with(name: String, value: PropertyValue): PlaybackState = when (name) {
        "time-pos" -> copy(position = value.double ?: 0.0)
        "duration" -> copy(duration = value.double ?: 0.0)
        "pause" -> copy(paused = value.flag ?: true)
        "paused-for-cache" -> copy(buffering = value.flag ?: false)
        "seeking" -> copy(seeking = value.flag ?: false)
        "eof-reached" -> copy(endReached = value.flag ?: false)
        "speed" -> copy(speed = value.double ?: 1.0)
        "demuxer-cache-time" -> copy(cachedUntil = value.double ?: 0.0)
        "hwdec-current" -> copy(hardwareDecoder = value.text?.takeUnless { it == "no" })
        "current-tracks/video/codec" -> copy(videoCodec = value.text)
        "video-params/dw" -> copy(videoWidth = value.int?.toInt() ?: 0)
        "video-params/dh" -> copy(videoHeight = value.int?.toInt() ?: 0)
        "ab-loop-a" -> copy(abLoopStart = value.double)
        "ab-loop-b" -> copy(abLoopEnd = value.double)
        "sub-delay" -> copy(subtitleDelay = value.double ?: 0.0)
        "sub-text" -> copy(subtitleText = value.text.orEmpty())
        "estimated-vf-fps" -> copy(stats = stats.copy(fps = value.double))
        "container-fps" -> copy(stats = stats.copy(containerFps = value.double))
        "frame-drop-count" -> copy(stats = stats.copy(droppedFrames = value.int ?: 0))
        "decoder-frame-drop-count" -> copy(stats = stats.copy(decoderDroppedFrames = value.int ?: 0))
        "video-bitrate" -> copy(stats = stats.copy(videoBitrate = value.double))
        "audio-bitrate" -> copy(stats = stats.copy(audioBitrate = value.double))
        "cache-speed" -> copy(stats = stats.copy(cacheBytesPerSecond = value.int))
        "demuxer-cache-duration" -> copy(stats = stats.copy(cachedSeconds = value.double))
        "current-tracks/audio/codec" -> copy(stats = stats.copy(audioCodec = value.text))
        "video-params/pixelformat" -> copy(stats = stats.copy(pixelFormat = value.text))
        "video-params/gamma" -> copy(stats = stats.copy(gamma = value.text))
        else -> this
    }
}

private val PropertyValue.double: Double?
    get() = when (this) {
        is PropertyValue.Double -> v1
        is PropertyValue.Int -> v1.toDouble()
        else -> null
    }

private val PropertyValue.int: Long?
    get() = (this as? PropertyValue.Int)?.v1

private val PropertyValue.flag: Boolean?
    get() = (this as? PropertyValue.Flag)?.v1

private val PropertyValue.text: String?
    get() = (this as? PropertyValue.Text)?.v1
