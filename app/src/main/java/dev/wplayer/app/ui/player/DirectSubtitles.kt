package dev.wplayer.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.wplayer.app.data.model.SubtitleSettings

/** Where and how big subtitles are drawn: mpv's `sub-pos` and `sub-scale`, in percent. */
data class SubtitlePlacement(val position: Int = 100, val sizePercent: Int = 100)

/**
 * Subtitles for [VideoPath.DIRECT], where mpv cannot draw over the video: the
 * current line as plain text (ASS styling is lost), styled from the settings at
 * the sizes mpv would use.
 */
@Composable
fun DirectSubtitles(text: String, style: SubtitleSettings, placement: SubtitlePlacement, modifier: Modifier = Modifier) {
    if (text.isBlank()) return
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        // mpv sizes subtitles for a 720-pixel-high window and scales them with the window.
        val unit = with(density) { maxHeight.toPx() } / MPV_REFERENCE_HEIGHT * placement.sizePercent / 100f
        val textStyle = TextStyle(
            fontSize = with(density) { (MPV_FONT_SIZE * unit).toSp() },
            fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            shadow = if (style.shadow && !style.background) {
                Shadow(Color.Black.copy(alpha = 0.8f), Offset(MPV_SHADOW_OFFSET * unit, MPV_SHADOW_OFFSET * unit))
            } else {
                null
            },
        )
        val box = if (style.background) {
            Modifier.background(Color(BACKGROUND_ARGB), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 2.dp)
        } else {
            Modifier
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = maxHeight * (100 - placement.position) / 100f)
                .padding(horizontal = 24.dp)
                .then(box),
        ) {
            val line = text.trim()
            if (style.outline && !style.background) {
                // The stroke straddles the glyph edge; the fill drawn on top hides its inner half.
                val outline = Stroke(width = 2 * MPV_BORDER_SIZE * unit, join = StrokeJoin.Round)
                Text(line, style = textStyle.copy(color = Color.Black, drawStyle = outline, shadow = null))
            }
            Text(line, style = textStyle.copy(color = Color(style.color.argb)))
        }
    }
}

// The values MpvEngineFactory and mpvOptions give mpv, so both paths look alike.
private const val MPV_REFERENCE_HEIGHT = 720f
private const val MPV_FONT_SIZE = 46f
private const val MPV_BORDER_SIZE = 2.5f
private const val MPV_SHADOW_OFFSET = 1.5f
private const val BACKGROUND_ARGB = 0x99000000
