package dev.wplayer.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.wplayer.app.R
import dev.wplayer.core.player.PlaybackState

/** Playback numbers over the video, for checking decoding and network on a device. */
@Composable
fun StatsOverlay(playback: PlaybackState, modifier: Modifier = Modifier) {
    val stats = playback.stats
    val decoder = playback.hardwareDecoder?.let { stringResource(R.string.player_hwdec, it) } ?: stringResource(R.string.player_swdec)
    val hdr = when (stats.gamma) {
        "pq" -> " · HDR10/PQ"
        "hlg" -> " · HLG"
        else -> ""
    }
    val lines = listOfNotNull(
        listOfNotNull(
            playback.videoCodec?.uppercase(),
            "${playback.videoWidth}×${playback.videoHeight}".takeIf { playback.videoWidth > 0 },
            stats.pixelFormat,
        ).joinToString(" ") + hdr,
        decoder,
        stringResource(
            R.string.stats_frames,
            stats.fps?.let { "%.2f".format(it) } ?: "-",
            stats.containerFps?.let { "%.3f".format(it) } ?: "-",
            stats.droppedFrames,
            stats.decoderDroppedFrames,
        ),
        stringResource(
            R.string.stats_bitrate,
            stats.videoBitrate?.let(::megabits) ?: "-",
            stats.audioCodec ?: "-",
            stats.audioBitrate?.let { "%.0f kbps".format(it / 1000) } ?: "-",
        ),
        stringResource(
            R.string.stats_cache,
            stats.cachedSeconds?.let { "%.0f".format(it) } ?: "-",
            stats.cacheBytesPerSecond?.let { "%.1f".format(it / 1_000_000.0) } ?: "-",
        ),
    )
    Text(
        text = lines.joinToString("\n"),
        style = MaterialTheme.typography.labelMedium,
        fontFamily = FontFamily.Monospace,
        color = Color.White,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

private fun megabits(bitsPerSecond: Double) = "%.1f Mbps".format(bitsPerSecond / 1_000_000)
