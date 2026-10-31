package dev.wplayer.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.wplayer.app.R
import kotlin.math.abs
import kotlin.math.roundToInt

/** The thumbnail size setting: how much bigger than the default thumbnails and grid cells are drawn. */
val LocalMediaScale = compositionLocalOf { 1f }

const val MIN_MEDIA_SCALE = 0.75f
const val MAX_MEDIA_SCALE = 2f
/** Fine enough to feel continuous; also bounds how often a drag saves the setting. */
private const val MEDIA_SCALE_STEP = 0.05f

/** Narrowest grid cell; the grid fits as many as the width allows. */
val mediaGridCellMinWidth: Dp
    @Composable get() = BASE_GRID_CELL_MIN_WIDTH * LocalMediaScale.current

/** Width of a list row's 16:9 thumbnail. */
val listThumbnailWidth: Dp
    @Composable get() = BASE_LIST_THUMBNAIL_WIDTH * LocalMediaScale.current

/** A top bar button whose menu holds a slider for the thumbnail size. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThumbnailSizeButton(scale: Float, onScaleChange: (Float) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = stringResource(R.string.thumbnail_size)
    IconButton(onClick = { open = true }) {
        Icon(Icons.Outlined.ZoomIn, contentDescription = label)
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        Column(Modifier.width(SLIDER_MENU_WIDTH).padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(
                stringResource(R.string.thumbnail_size_value, label, scale),
                style = MaterialTheme.typography.labelLarge,
            )
            val colors = SliderDefaults.colors()
            val interaction = remember { MutableInteractionSource() }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                Slider(
                    value = scale,
                    // Moves continuously but saves only when the value crosses a 0.05 step.
                    onValueChange = { value ->
                        val snapped = (value / MEDIA_SCALE_STEP).roundToInt() * MEDIA_SCALE_STEP
                        if (abs(snapped - scale) >= MEDIA_SCALE_STEP / 2) onScaleChange(snapped)
                    },
                    valueRange = MIN_MEDIA_SCALE..MAX_MEDIA_SCALE,
                    interactionSource = interaction,
                    // A small round thumb on a thin track; the slider keeps its 48dp touch height.
                    thumb = {
                        Box(Modifier.size(SLIDER_THUMB_SIZE).background(MaterialTheme.colorScheme.primary, CircleShape))
                    },
                    track = { state ->
                        SliderDefaults.Track(
                            sliderState = state,
                            modifier = Modifier.height(SLIDER_TRACK_HEIGHT),
                            colors = colors,
                            thumbTrackGapSize = 0.dp,
                            drawStopIndicator = null,
                        )
                    },
                    // The small thumb would shrink the slider to its own height; keep a full touch target.
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).padding(horizontal = 8.dp),
                )
                Icon(Icons.Outlined.Image, contentDescription = null, modifier = Modifier.size(24.dp))
            }
        }
    }
}

private val SLIDER_MENU_WIDTH = 360.dp
private val SLIDER_THUMB_SIZE = 18.dp
private val SLIDER_TRACK_HEIGHT = 4.dp
private val BASE_GRID_CELL_MIN_WIDTH = 160.dp
private val BASE_LIST_THUMBNAIL_WIDTH = 112.dp
