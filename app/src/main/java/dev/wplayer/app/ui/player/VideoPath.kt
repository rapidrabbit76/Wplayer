package dev.wplayer.app.ui.player

import dev.wplayer.app.data.model.PlaybackSettings

/**
 * How decoded video reaches the screen: mpv's `hwdec` and `vo` values.
 * [ZERO_COPY] suits most devices; the others cover decoders and TVs where it
 * fails or is too slow.
 */
enum class VideoPath(val hwdec: String, val videoOutput: String) {
    /** MediaCodec frames go to libplacebo without a copy; mpv tries [COPY] if this cannot start. */
    ZERO_COPY("mediacodec,mediacodec-copy", "gpu-next"),

    /**
     * MediaCodec frames are copied to memory first. Slower, but works with
     * decoders whose frames never arrive on the zero-copy path (10-bit HEVC on
     * some MStar TVs).
     */
    COPY("mediacodec-copy", "gpu-next"),

    /**
     * The decoder draws onto the surface itself: the lightest path, smooth for
     * 4K HDR on weak TVs. mpv then draws nothing over the video and cannot
     * scale it, so the player sizes the surface and draws subtitles itself.
     */
    DIRECT("mediacodec", "mediacodec_embed"),

    SOFTWARE("no", "gpu-next"),
    ;

    val hardware: Boolean get() = this != SOFTWARE

    companion object {
        /** The path the settings ask for. */
        fun from(playback: PlaybackSettings): VideoPath = when {
            !playback.hardwareDecoding -> SOFTWARE
            playback.directVideoOutput -> DIRECT
            playback.zeroCopyFroze -> COPY
            else -> ZERO_COPY
        }
    }
}
