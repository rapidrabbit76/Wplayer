package dev.wplayer.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import dev.wplayer.app.R
import dev.wplayer.app.ui.theme.FOCUS_HIGHLIGHT_ALPHA
import kotlin.math.roundToInt

/**
 * A slider's value while it is dragged, so a setting is saved once on release
 * rather than on every step.
 */
@Stable
class ReleaseSliderState internal constructor() {
    private var dragging by mutableFloatStateOf(Float.NaN)

    fun shown(saved: Int): Float = if (dragging.isNaN()) saved.toFloat() else dragging

    fun drag(to: Float) {
        dragging = to
    }

    /** The released value; null when a key press finished without a change (at either end of the range). */
    fun release(): Int? {
        val released = dragging.takeUnless { it.isNaN() }?.roundToInt()
        dragging = Float.NaN
        return released
    }
}

@Composable
fun rememberReleaseSliderState(): ReleaseSliderState = remember { ReleaseSliderState() }

/**
 * A [Slider] among the rows of a list, usable with a remote: left and right
 * change the value, up and down move on to the neighbouring rows as
 * everywhere else (the slider alone takes them as value changes too, so focus
 * could never leave it), and focus shows as a highlight, which the slider
 * alone does not draw.
 */
@Composable
fun ListSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val highlight = LocalContentColor.current.copy(alpha = FOCUS_HIGHLIGHT_ALPHA)
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        interactionSource = interaction,
        modifier = modifier
            .then(if (focused) Modifier.background(highlight, RoundedCornerShape(percent = 50)) else Modifier)
            .leaveOnVerticalKeys(focusManager),
    )
}

/** [onChange] gets each value while dragging (save false), then the released value (save true). */
@Composable
fun PercentSliderRow(title: String, value: Int, range: IntRange, onChange: (percent: Int, save: Boolean) -> Unit) {
    val slider = rememberReleaseSliderState()
    val shown = slider.shown(value)
    ListItem(
        headlineContent = {
            Row {
                Text(title, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.percent_value, shown.roundToInt()), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            }
        },
        supportingContent = {
            ListSlider(
                value = shown,
                onValueChange = {
                    slider.drag(it)
                    onChange(it.roundToInt(), false)
                },
                onValueChangeFinished = { slider.release()?.let { onChange(it, true) } },
                valueRange = range.first.toFloat()..range.last.toFloat(),
            )
        },
        colors = transparentRowColors(),
    )
}
