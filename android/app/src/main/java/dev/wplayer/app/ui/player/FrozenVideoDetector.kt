package dev.wplayer.app.ui.player

/**
 * Notices a picture that stopped during playback: some hardware decoders hand
 * over the first frames and then none. Depending on the decoder mpv's core then
 * waits for it (nothing updates any more) or the sound plays on over a still
 * image; either way the count of shown frames stops.
 *
 * Fed about once a second with the time and that count; reports a freeze once
 * the count has not moved for [limitSeconds] while playing. Wall-clock time,
 * because the playback position stops too when the core is stuck.
 */
class FrozenVideoDetector(private val limitSeconds: Double = 4.0) {
    private var frames = -1L

    /** When [frames] last changed; null while not playing. */
    private var since: Double? = null

    /**
     * [playing] means frames are expected: playing, not paused, buffering or
     * seeking, with video and audio. Returns true when the picture is frozen.
     */
    fun update(seconds: Double, presentedFrames: Long, playing: Boolean): Boolean {
        val start = since
        if (!playing || presentedFrames != frames || start == null) {
            frames = presentedFrames
            since = seconds.takeIf { playing }
            return false
        }
        return seconds - start >= limitSeconds
    }
}
