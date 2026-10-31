package dev.wplayer.app.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.wplayer.app.R
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Step seeks (remote, keyboard, double tap) in one direction, added up while
 * they keep coming, like Netflix's and YouTube's side readout. [taps] changes
 * with every step so each one can pulse; an inactive burst is fading out.
 */
@Immutable
data class StepSeekBurst(val forward: Boolean, val seconds: Int, val taps: Int = 1, val active: Boolean = true)

/** This burst with a step of [delta] seconds added, or a new burst when it ended or turned around. */
fun StepSeekBurst?.plus(delta: Double): StepSeekBurst {
    val forward = delta > 0
    val seconds = abs(delta).roundToInt()
    return if (this != null && active && this.forward == forward) {
        copy(seconds = this.seconds + seconds, taps = taps + 1)
    } else {
        StepSeekBurst(forward, seconds)
    }
}

/** Arrows rippling toward the seek direction over the seconds so far, at that side of the screen. */
@Composable
fun StepSeekIndicator(burst: StepSeekBurst?, modifier: Modifier = Modifier) {
    Box(modifier) {
        if (burst == null) return@Box
        AnimatedVisibility(
            visible = burst.active,
            enter = fadeIn(tween(FADE_IN_MS)),
            exit = fadeOut(tween(FADE_OUT_MS)),
            modifier = Modifier
                .align(if (burst.forward) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(horizontal = 48.dp),
        ) {
            BurstBadge(burst)
        }
    }
}

@Composable
private fun BurstBadge(burst: StepSeekBurst) {
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(burst.taps, burst.forward) {
        pulse.snapTo(PULSE_SCALE)
        pulse.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
    }
    val phase by rememberInfiniteTransition(label = "chevrons").animateFloat(
        initialValue = 0f,
        targetValue = CHEVRONS.toFloat(),
        animationSpec = infiniteRepeatable(tween(RIPPLE_MS, easing = LinearEasing)),
        label = "phase",
    )
    Column(
        modifier = Modifier
            .size(BADGE_SIZE)
            .graphicsLayer {
                scaleX = pulse.value
                scaleY = pulse.value
            }
            .background(Color.Black.copy(alpha = 0.35f), CircleShape),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy((-10).dp)) {
            repeat(CHEVRONS) { slot ->
                // The light runs toward the seek direction: left to right forward, right to left back.
                val order = if (burst.forward) slot else CHEVRONS - 1 - slot
                val lit = (1f - abs(phase - order - 0.5f)).coerceIn(0f, 1f)
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.3f + 0.7f * lit),
                    modifier = Modifier
                        .size(30.dp)
                        .graphicsLayer { if (!burst.forward) rotationZ = 180f },
                )
            }
        }
        Text(
            text = stringResource(R.string.settings_seconds, burst.seconds),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
        )
    }
}

/** How long a burst stays after its last step; a step within it adds up. */
const val STEP_SEEK_BURST_MS = 900L

private const val CHEVRONS = 3
private const val RIPPLE_MS = 750
private const val FADE_IN_MS = 120
private const val FADE_OUT_MS = 300
private const val PULSE_SCALE = 1.15f
private val BADGE_SIZE = 128.dp
