package dev.wplayer.app.ui.player

import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Forward5
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.wplayer.app.R
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Date
import kotlinx.coroutines.delay

/** Actions the on-screen controls can trigger. */
interface PlayerControlActions {
    fun onBack()
    fun onTogglePause()
    fun onSeekBy(seconds: Double)
    fun onSeekTo(seconds: Double)

    /** The seek bar thumb is being dragged ([dragging]) or was released. */
    fun onSeekBarDragging(dragging: Boolean)

    /** The seek bar is dragged to [seconds]: show the frame there. */
    fun onSeekPreview(seconds: Double)
    fun onSeekPreviewEnd()
    fun onPrevious()
    fun onNext()
    fun onOpenSheet(panel: PlayerSheet)
    fun onCycleAbRepeat()
    fun onLock()
    fun onPictureInPicture()
    fun onSetSpeed(speed: Double)
    fun onSetScale(scale: ScaleMode)
    fun onSetAspect(aspect: AspectRatio)
    fun onSetHardwareDecoding(enabled: Boolean)

    /** A quick menu beside the play buttons opened or closed; the controls stay up while one is open. */
    fun onQuickMenu(open: Boolean)
}

/** Settings and queue facts the controls show. */
data class ControlsConfig(
    val seekBackSeconds: Int = 10,
    val seekForwardSeconds: Int = 10,
    val showClock: Boolean = false,
    /** Codec and decoder under the title: developer information, off unless stats are on. */
    val showDecoder: Boolean = false,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    /** Many TVs have no picture-in-picture. */
    val canPictureInPicture: Boolean = true,
)

@Composable
fun PlayerControls(
    state: PlayerUiState,
    config: ControlsConfig,
    actions: PlayerControlActions,
    modifier: Modifier = Modifier,
    preview: SeekPreviewFrame? = null,
    /** Put focus on the play button, for moving through the controls with a remote. */
    focusPlay: Boolean = false,
) {
    val playback = state.playback
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(focusPlay) {
        if (focusPlay) playFocus.requestFocus()
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        // Wide windows put the quick menus beside the play buttons; narrow ones above the seek bar.
        val quickBesidePlay = maxWidth >= QUICK_BESIDE_PLAY_MIN_WIDTH
        TopBar(
            state = state,
            showClock = config.showClock,
            showDecoder = config.showDecoder,
            canPictureInPicture = config.canPictureInPicture,
            actions = actions,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(OverlayColors.scrim, Color.Transparent)))
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        )

        BoxWithConstraints(Modifier.align(Alignment.Center)) {
            // Narrow portrait phones lose some spacing so five buttons still fit.
            val spacing = if (maxWidth < 420.dp) 12.dp else 32.dp
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = actions::onPrevious, enabled = config.hasPrevious, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = stringResource(R.string.player_previous), modifier = Modifier.size(28.dp))
                }
                IconButton(onClick = { actions.onSeekBy(-config.seekBackSeconds.toDouble()) }, modifier = Modifier.size(48.dp)) {
                    Icon(
                        replayIcon(config.seekBackSeconds),
                        contentDescription = stringResource(R.string.player_rewind, config.seekBackSeconds),
                        modifier = Modifier.size(32.dp),
                    )
                }
                IconButton(onClick = actions::onTogglePause, modifier = Modifier.size(64.dp).focusRequester(playFocus)) {
                    Icon(
                        imageVector = if (playback.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = stringResource(if (playback.paused) R.string.player_play else R.string.player_pause),
                        modifier = Modifier.size(44.dp),
                    )
                }
                IconButton(onClick = { actions.onSeekBy(config.seekForwardSeconds.toDouble()) }, modifier = Modifier.size(48.dp)) {
                    Icon(
                        forwardIcon(config.seekForwardSeconds),
                        contentDescription = stringResource(R.string.player_forward, config.seekForwardSeconds),
                        modifier = Modifier.size(32.dp),
                    )
                }
                IconButton(onClick = actions::onNext, enabled = config.hasNext, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.player_next), modifier = Modifier.size(28.dp))
                }
            }
        }

        if (quickBesidePlay) {
            val sides = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            QuickStartButtons(state, actions, sides.align(Alignment.CenterStart).padding(start = 16.dp))
            QuickEndButtons(state, actions, sides.align(Alignment.CenterEnd).padding(end = 16.dp))
        }

        val draggedTo = dragFraction
        if (draggedTo != null && preview != null) {
            // Centered over the thumb, kept on screen at either end.
            val track = maxWidth - SEEK_BAR_PADDING * 2
            val center = SEEK_BAR_PADDING + track * draggedTo
            val left = (center - SEEK_PREVIEW_WIDTH / 2).coerceIn(8.dp, maxWidth - SEEK_PREVIEW_WIDTH - 8.dp)
            SeekPreviewImage(
                frame = preview,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                    .padding(start = left, bottom = SEEK_PREVIEW_BOTTOM),
            )
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, OverlayColors.scrim)))
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)),
        ) {
            if (!quickBesidePlay) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    QuickStartButtons(state, actions)
                    QuickEndButtons(state, actions)
                }
            }
            SeekBar(
                state = state,
                config = config,
                onSeekBy = actions::onSeekBy,
                onSeekTo = actions::onSeekTo,
                onDrag = { fraction ->
                    if ((fraction != null) != (dragFraction != null)) actions.onSeekBarDragging(fraction != null)
                    dragFraction = fraction
                    if (fraction != null) actions.onSeekPreview(fraction * playback.duration) else actions.onSeekPreviewEnd()
                },
                modifier = Modifier,
            )
        }
    }
}

private fun replayIcon(seconds: Int): ImageVector = when (seconds) {
    5 -> Icons.Filled.Replay5
    10 -> Icons.Filled.Replay10
    30 -> Icons.Filled.Replay30
    else -> Icons.Filled.Replay
}

private fun forwardIcon(seconds: Int): ImageVector = when (seconds) {
    5 -> Icons.Filled.Forward5
    10 -> Icons.Filled.Forward10
    30 -> Icons.Filled.Forward30
    else -> Icons.Filled.FastForward
}

/** One entry of the top bar: shown as an icon when there is room, else in the overflow menu. */
private data class TopBarAction(
    val icon: ImageVector,
    @StringRes val label: Int,
    val tint: Color? = null,
    val onClick: () -> Unit,
)

/**
 * Title plus actions. Narrow (phone portrait) layouts keep subtitles and lock
 * visible and move the rest into an overflow menu so the title stays readable.
 */
@Composable
private fun TopBar(
    state: PlayerUiState,
    showClock: Boolean,
    showDecoder: Boolean,
    canPictureInPicture: Boolean,
    actions: PlayerControlActions,
    modifier: Modifier,
) {
    val playback = state.playback
    val abTint = if (playback.abLoopStart != null) OverlayColors.accent else null
    val secondary = listOf(
        TopBarAction(Icons.Filled.Repeat, R.string.player_ab_repeat, abTint, actions::onCycleAbRepeat),
        TopBarAction(Icons.Filled.AspectRatio, R.string.player_video) { actions.onOpenSheet(PlayerSheet.VIDEO) },
        TopBarAction(Icons.Filled.Audiotrack, R.string.player_audio) { actions.onOpenSheet(PlayerSheet.AUDIO) },
        TopBarAction(Icons.AutoMirrored.Filled.PlaylistPlay, R.string.player_queue) { actions.onOpenSheet(PlayerSheet.QUEUE) },
        TopBarAction(Icons.Filled.PictureInPictureAlt, R.string.player_pip, onClick = actions::onPictureInPicture)
            .takeIf { canPictureInPicture },
    ).filterNotNull()
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val compact = maxWidth < COMPACT_TOP_BAR_WIDTH
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = actions::onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back), Modifier.size(TOP_BAR_ICON_SIZE))
            }
            Column(Modifier.weight(1f)) {
                Text(state.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Narrow bars keep the title wide by moving the clock under it.
                    if (showClock && compact) Clock(Modifier.padding(end = 8.dp), MaterialTheme.typography.labelMedium)
                    if (showDecoder) DecoderBadge(playback.videoCodec, playback.hardwareDecoder)
                }
            }
            if (showClock && !compact) Clock(Modifier.padding(horizontal = 8.dp), MaterialTheme.typography.titleSmall)
            if (!compact) {
                secondary.forEach { action ->
                    IconButton(onClick = action.onClick) {
                        Icon(
                            action.icon,
                            contentDescription = stringResource(action.label),
                            modifier = Modifier.size(TOP_BAR_ICON_SIZE),
                            tint = action.tint ?: LocalContentColor.current,
                        )
                    }
                }
            }
            IconButton(onClick = { actions.onOpenSheet(PlayerSheet.SUBTITLES) }) {
                Icon(Icons.Filled.Subtitles, contentDescription = stringResource(R.string.player_subtitles), Modifier.size(TOP_BAR_ICON_SIZE))
            }
            IconButton(onClick = { actions.onOpenSheet(PlayerSheet.SETTINGS) }) {
                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.player_settings), Modifier.size(TOP_BAR_ICON_SIZE))
            }
            if (compact) OverflowMenu(secondary)
        }
    }
}

/** Left of the play buttons: screen lock and playback speed. */
@Composable
private fun QuickStartButtons(state: PlayerUiState, actions: PlayerControlActions, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        IconButton(onClick = actions::onLock) {
            Icon(Icons.Filled.Lock, contentDescription = stringResource(R.string.player_lock), Modifier.size(QUICK_ICON_SIZE))
        }
        val speed = state.playback.speed
        QuickMenu(stringResource(R.string.player_speed), actions::onQuickMenu, label = formatSpeed(speed)) { close ->
            MenuHeader(stringResource(R.string.player_speed))
            SPEEDS.forEach { option ->
                MenuChoice(formatSpeed(option), selected = option == speed) {
                    actions.onSetSpeed(option)
                    close()
                }
            }
        }
    }
}

/** Right of the play buttons: how the picture fits the screen, and the decoder. */
@Composable
private fun QuickEndButtons(state: PlayerUiState, actions: PlayerControlActions, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        QuickMenu(stringResource(R.string.player_scale), actions::onQuickMenu, icon = Icons.Filled.AspectRatio) { close ->
            MenuHeader(stringResource(R.string.player_scale))
            ScaleMode.entries.forEach { option ->
                MenuChoice(stringResource(option.label), selected = option == state.scale) {
                    actions.onSetScale(option)
                    close()
                }
            }
            HorizontalDivider()
            MenuHeader(stringResource(R.string.player_aspect_ratio))
            AspectRatio.entries.forEach { option ->
                val label = if (option == AspectRatio.DEFAULT) stringResource(R.string.player_aspect_default) else option.label
                MenuChoice(label, selected = option == state.aspect) {
                    actions.onSetAspect(option)
                    close()
                }
            }
        }
        val decoder = stringResource(R.string.player_decoder)
        val decoderLabel = stringResource(if (state.hardwareDecoding) R.string.player_decoder_hw_short else R.string.player_decoder_sw_short)
        QuickMenu(decoder, actions::onQuickMenu, label = decoderLabel) { close ->
            MenuHeader(decoder)
            MenuChoice(stringResource(R.string.player_decoder_hw), selected = state.hardwareDecoding) {
                actions.onSetHardwareDecoding(true)
                close()
            }
            MenuChoice(stringResource(R.string.player_decoder_sw), selected = !state.hardwareDecoding) {
                actions.onSetHardwareDecoding(false)
                close()
            }
        }
    }
}

/** A button showing [label] (outlined, like "1.0x") or [icon] that opens a menu in place. */
@Composable
private fun QuickMenu(
    description: String,
    onOpenChange: (Boolean) -> Unit,
    label: String? = null,
    icon: ImageVector? = null,
    items: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val setOpen = { value: Boolean ->
        open = value
        onOpenChange(value)
    }
    Box {
        if (icon != null) {
            IconButton(onClick = { setOpen(true) }) {
                Icon(icon, contentDescription = description, Modifier.size(QUICK_ICON_SIZE))
            }
        } else {
            Surface(
                onClick = { setOpen(true) },
                shape = RoundedCornerShape(6.dp),
                color = Color.Transparent,
                contentColor = Color.White,
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.7f)),
                modifier = Modifier.semantics { contentDescription = description },
            ) {
                Text(label.orEmpty(), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { setOpen(false) }) { items { setOpen(false) } }
    }
}

@Composable
private fun MenuHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun MenuChoice(text: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = {
            if (selected) Icon(Icons.Filled.Check, contentDescription = null) else Spacer(Modifier.size(24.dp))
        },
        onClick = onClick,
    )
}

@Composable
private fun OverflowMenu(items: List<TopBarAction>) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.player_more), Modifier.size(TOP_BAR_ICON_SIZE))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(stringResource(item.label)) },
                    leadingIcon = { Icon(item.icon, contentDescription = null, tint = item.tint ?: LocalContentColor.current) },
                    onClick = {
                        expanded = false
                        item.onClick()
                    },
                )
            }
        }
    }
}

/** The current time in the system's 12/24-hour format, updated every minute. */
@Composable
private fun Clock(modifier: Modifier, style: TextStyle) {
    val context = LocalContext.current
    val wallClock = LocalWallClock.current
    var now by remember { mutableLongStateOf(wallClock()) }
    LaunchedEffect(wallClock) {
        while (true) {
            now = wallClock()
            delay(CLOCK_TICK_MS - now % CLOCK_TICK_MS)
        }
    }
    Text(DateFormat.getTimeFormat(context).format(Date(now)), style = style, maxLines = 1, modifier = modifier)
}

private const val CLOCK_TICK_MS = 60_000L

/** The time the top bar shows; screenshot tests pin it. */
internal val LocalWallClock = staticCompositionLocalOf<() -> Long> { System::currentTimeMillis }

@Composable
private fun DecoderBadge(codec: String?, hardwareDecoder: String?) {
    if (codec == null) return
    val decoder = hardwareDecoder?.let { stringResource(R.string.player_hwdec, it) } ?: stringResource(R.string.player_swdec)
    Text(
        text = "${codec.uppercase()} · $decoder",
        style = MaterialTheme.typography.labelSmall,
        color = Color.White.copy(alpha = 0.7f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Position slider with times. [onDrag] reports the dragged fraction, or null
 * when released; the seek itself happens on release. Focused from a remote,
 * left and right seek by the seek steps (repeating while held).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeekBar(
    state: PlayerUiState,
    config: ControlsConfig,
    onSeekBy: (Double) -> Unit,
    onSeekTo: (Double) -> Unit,
    onDrag: (Float?) -> Unit,
    modifier: Modifier,
) {
    val playback = state.playback
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val duration = playback.duration.coerceAtLeast(0.0)
    val fraction = dragFraction ?: if (duration > 0) (playback.position / duration).toFloat() else 0f
    val shownPosition = dragFraction?.let { it * duration } ?: playback.position

    val colors = SliderDefaults.colors(
        thumbColor = OverlayColors.track,
        activeTrackColor = OverlayColors.track,
        inactiveTrackColor = OverlayColors.inactiveTrack,
        activeTickColor = Color.Transparent,
        inactiveTickColor = Color.Transparent,
    )
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(modifier.fillMaxWidth().padding(horizontal = SEEK_BAR_PADDING, vertical = 8.dp)) {
        // A thin track and thumb like video sites; the slider keeps its 48dp touch height.
        Slider(
            value = fraction.coerceIn(0f, 1f),
            onValueChange = {
                dragFraction = it
                onDrag(it)
            },
            onValueChangeFinished = {
                dragFraction?.let { onSeekTo(it * duration) }
                dragFraction = null
                onDrag(null)
            },
            enabled = duration > 0,
            colors = colors,
            interactionSource = interaction,
            // The slider's own keys move it by 1% and seek only when the key is released.
            modifier = Modifier.onPreviewKeyEvent { event ->
                val forward = when (event.key) {
                    Key.DirectionRight -> true
                    Key.DirectionLeft -> false
                    else -> return@onPreviewKeyEvent false
                }
                if (event.type == KeyEventType.KeyDown) {
                    onSeekBy(if (forward) config.seekForwardSeconds.toDouble() else -config.seekBackSeconds.toDouble())
                }
                true
            },
            thumb = {
                val thumbColors = if (focused) SliderDefaults.colors(thumbColor = OverlayColors.accent) else colors
                SliderDefaults.Thumb(
                    interaction,
                    colors = thumbColors,
                    enabled = duration > 0,
                    thumbSize = if (focused) SEEK_THUMB_FOCUSED_SIZE else SEEK_THUMB_SIZE,
                )
            },
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    modifier = Modifier.height(SEEK_TRACK_HEIGHT),
                    enabled = duration > 0,
                    colors = colors,
                    drawStopIndicator = null,
                    thumbTrackGapSize = 2.dp,
                )
            },
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatTime(shownPosition), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            AbLoopLabel(playback.abLoopStart, playback.abLoopEnd)
            Spacer(Modifier.weight(1f))
            Text(formatTime(duration), style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** The frame at the dragged position with its time, like a video site's seek preview. */
@Composable
fun SeekPreviewImage(frame: SeekPreviewFrame, modifier: Modifier = Modifier) {
    // A fixed-width column keeps the image centered on the thumb whatever its shape.
    Column(modifier.width(SEEK_PREVIEW_WIDTH), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            bitmap = remember(frame.image) { frame.image.asImageBitmap() },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                // Fits within width × max height, so portrait videos stay small.
                .widthIn(max = SEEK_PREVIEW_WIDTH)
                .heightIn(max = SEEK_PREVIEW_MAX_HEIGHT)
                .aspectRatio(frame.image.width.toFloat() / frame.image.height.coerceAtLeast(1))
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(8.dp)),
        )
        Text(
            text = formatTime(frame.seconds),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .padding(top = 4.dp)
                .background(OverlayColors.textBackdrop, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun AbLoopLabel(start: Double?, end: Double?) {
    if (start == null) return
    Text(
        text = "A ${formatTime(start)}" + (end?.let { " → B ${formatTime(it)}" } ?: ""),
        style = MaterialTheme.typography.labelMedium,
        color = OverlayColors.accent,
    )
}

/** Shown while the screen is locked: a single unlock button. */
@Composable
fun LockedOverlay(onUnlock: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp)) {
        IconButton(
            onClick = onUnlock,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50))
                .size(56.dp),
        ) {
            Icon(Icons.Filled.LockOpen, contentDescription = stringResource(R.string.player_unlock))
        }
    }
}

private val COMPACT_TOP_BAR_WIDTH = 600.dp
private val SEEK_BAR_PADDING = 16.dp
private val SEEK_TRACK_HEIGHT = 4.dp
private val SEEK_THUMB_SIZE = DpSize(4.dp, 18.dp)
private val SEEK_THUMB_FOCUSED_SIZE = DpSize(8.dp, 28.dp)
private val TOP_BAR_ICON_SIZE = 20.dp
private val QUICK_ICON_SIZE = 22.dp
private val QUICK_BESIDE_PLAY_MIN_WIDTH = 600.dp
private val SEEK_PREVIEW_WIDTH = 240.dp
private val SEEK_PREVIEW_MAX_HEIGHT = 160.dp

/** Above the slider and its time labels. */
private val SEEK_PREVIEW_BOTTOM = 88.dp

/**
 * Fixed colors for controls drawn over video: theme (and dynamic) colors are
 * tuned for app surfaces and can vanish against dark footage.
 */
internal object OverlayColors {
    val accent = Color(0xFFFFC857)
    val track = Color.White
    val inactiveTrack = Color.White.copy(alpha = 0.3f)

    /** Behind the control bars at the top and bottom, fading to clear. */
    val scrim = Color.Black.copy(alpha = 0.7f)

    /** Behind a short text label, so it stays legible on bright frames. */
    val textBackdrop = Color.Black.copy(alpha = 0.6f)
}

/** "1.0x", "1.25x", "0.5x": one or two decimals, the same in every locale. */
fun formatSpeed(speed: Double): String {
    val exact = BigDecimal.valueOf(speed).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros()
    return "${exact.setScale(maxOf(exact.scale(), 1)).toPlainString()}x"
}

/** `H:MM:SS` or `M:SS`. */
fun formatTime(seconds: Double): String {
    val total = seconds.coerceAtLeast(0.0).toLong()
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
