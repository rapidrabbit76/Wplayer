package dev.wplayer.app.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.wplayer.app.R
import dev.wplayer.app.data.transcription.ModelStatus
import dev.wplayer.app.data.model.AppSettings
import dev.wplayer.app.ui.common.PercentSliderRow
import dev.wplayer.app.ui.common.SectionHeader
import dev.wplayer.app.ui.common.SwitchRow
import dev.wplayer.app.ui.common.transparentRowColors
import dev.wplayer.app.data.model.MediaRef
import dev.wplayer.app.data.model.RepeatMode
import dev.wplayer.app.data.model.SUBTITLE_POSITION_RANGE
import dev.wplayer.app.data.model.SUBTITLE_SIZE_RANGE
import dev.wplayer.app.ui.settings.PLAYER_SETTINGS_CATEGORIES
import dev.wplayer.app.ui.settings.categoryRows
import dev.wplayer.core.ffi.Track
import dev.wplayer.core.ffi.TrackKind
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.DriveFolderUpload
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.vector.ImageVector
import dev.wplayer.app.data.model.MediaEntry
import dev.wplayer.app.data.model.MediaKind
import dev.wplayer.app.data.parentPath
import dev.wplayer.app.ui.common.UserError
import dev.wplayer.app.ui.common.catchingFailures
import dev.wplayer.app.ui.common.message

enum class PlayerSheet { NONE, SUBTITLES, SUBTITLE_FILE, AUDIO, VIDEO, QUEUE, SETTINGS }

internal val SPEEDS = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 3.0)

/**
 * A panel drawn inside the player window. A ModalBottomSheet would open its
 * own window, which brings the hidden system bars back and shifts the video.
 * Wide windows get a side panel; narrow ones a bottom panel.
 * With a remote, focus moves into the panel and stays there until it closes.
 */
@Composable
private fun PlayerPanel(
    onDismiss: () -> Unit,
    width: Dp = 360.dp,
    /** False for content that scrolls itself, such as a lazy list. */
    scrollable: Boolean = true,
    /** What the back gesture does; closing the panel unless the panel has its own levels. */
    onBack: () -> Unit = onDismiss,
    content: @Composable () -> Unit,
) {
    BackHandler(onBack = onBack)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > maxHeight || maxWidth >= 600.dp
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f))
                .blockTouches(onTap = onDismiss),
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = if (wide) RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp) else RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            modifier = if (wide) {
                Modifier.align(Alignment.CenterEnd).width(width).fillMaxHeight()
            } else {
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = maxHeight * 0.6f)
            },
        ) {
            Column(
                Modifier
                    .focusRequester(focus)
                    .focusProperties { onExit = { cancelFocusChange() } }
                    .focusGroup()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    .padding(vertical = 8.dp),
            ) { content() }
        }
    }
}

/** Takes touches (a tap runs [onTap]) without being focusable, so a remote's focus never lands on a backdrop. */
internal fun Modifier.blockTouches(onTap: () -> Unit = {}): Modifier =
    pointerInput(onTap) { detectTapGestures { onTap() } }

/** Changes from the subtitle panel. Sizes and positions are percentages. */
interface SubtitlePanelActions {
    fun onSelectTrack(id: Long?)
    fun onVisibleChange(visible: Boolean)
    fun onDelayChange(seconds: Double)
    fun onSizeChange(percent: Int, save: Boolean)
    fun onPositionChange(position: Int, save: Boolean)
    fun onStartAiSubtitles()
    fun onCancelAiSubtitles()
    fun onDownloadAiModel()
}

@Composable
fun SubtitleSheet(
    tracks: List<Track>,
    visible: Boolean,
    delay: Double,
    sizePercent: Int,
    position: Int,
    ai: AiSubtitleStatus,
    actions: SubtitlePanelActions,
    /** Opens the subtitle file picker; null when the video is not from a source. */
    onLoadFile: (() -> Unit)?,
    onDismiss: () -> Unit,
) = PlayerPanel(onDismiss) {
    val external = stringResource(R.string.player_track_external)
    val aiTrack = stringResource(R.string.ai_subtitle_track)
    SwitchRow(stringResource(R.string.player_subtitles_show), visible, actions::onVisibleChange)
    SectionTitle(stringResource(R.string.player_subtitles))
    val subtitles = tracks.filter { it.kind == TrackKind.SUBTITLE }
    TrackRow(stringResource(R.string.player_subtitles_off), subtitles.none { it.selected }) { actions.onSelectTrack(null) }
    subtitles.forEach { track ->
        val label = if (track.title == AI_SUBTITLE_TITLE) aiTrack else track.label(external)
        TrackRow(label, track.selected) { actions.onSelectTrack(track.id) }
    }
    if (onLoadFile != null) PickerRow(stringResource(R.string.player_subtitle_load), Icons.Outlined.FileOpen, onLoadFile)
    if (ai != AiSubtitleStatus.Unavailable) {
        SectionTitle(stringResource(R.string.ai_subtitles))
        AiSubtitleControls(ai, actions)
    }
    SectionTitle(stringResource(R.string.player_subtitle_sync))
    StepperRow(
        value = stringResource(R.string.player_seconds_signed, delay),
        onDecrease = { actions.onDelayChange(delay - SYNC_STEP_SECONDS) },
        onIncrease = { actions.onDelayChange(delay + SYNC_STEP_SECONDS) },
        onReset = { actions.onDelayChange(0.0) },
    )
    PercentSliderRow(
        title = stringResource(R.string.player_subtitle_size),
        value = sizePercent,
        range = SUBTITLE_SIZE_RANGE,
        onChange = actions::onSizeChange,
    )
    PercentSliderRow(
        title = stringResource(R.string.player_subtitle_position),
        value = position,
        range = SUBTITLE_POSITION_RANGE,
        onChange = actions::onPositionChange,
    )
    Text(
        text = stringResource(R.string.player_subtitle_gesture_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** A folder of the subtitle file picker, as it is being listed. */
private sealed interface SubtitleFolder {
    data object Loading : SubtitleFolder
    data class Listed(val entries: List<MediaEntry>) : SubtitleFolder
    data class Failed(val error: UserError) : SubtitleFolder
}

/**
 * Picks a subtitle file from the playing video's source, starting in its folder.
 * The back gesture goes up a folder first; the back button returns to the subtitle panel.
 */
@Composable
fun SubtitleFileSheet(
    startPath: String,
    list: suspend (String) -> List<MediaEntry>,
    onPick: (MediaEntry) -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    var path by rememberSaveable { mutableStateOf(startPath) }
    var folder by remember { mutableStateOf<SubtitleFolder>(SubtitleFolder.Loading) }
    LaunchedEffect(path) {
        folder = SubtitleFolder.Loading
        folder = catchingFailures { list(path) }.fold({ SubtitleFolder.Listed(it) }, { SubtitleFolder.Failed(UserError.from(it)) })
    }
    val parent = parentPath(path)
    PlayerPanel(onDismiss, scrollable = false, onBack = { parent?.let { path = it } ?: onBack() }) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, end = 16.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.subtitle_picker_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.StartEllipsis,
                )
            }
        }
        LazyColumn(Modifier.fillMaxWidth()) {
            if (parent != null) {
                item(key = "..") {
                    PickerRow(stringResource(R.string.browser_parent_folder), Icons.Outlined.DriveFolderUpload) { path = parent }
                }
            }
            when (val current = folder) {
                SubtitleFolder.Loading -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                is SubtitleFolder.Failed -> item(key = "error") {
                    PickerMessage(stringResource(R.string.browser_error, current.error.message()))
                }
                is SubtitleFolder.Listed -> {
                    if (current.entries.isEmpty()) item(key = "empty") { PickerMessage(stringResource(R.string.subtitle_picker_empty)) }
                    items(current.entries, key = { it.path }) { entry ->
                        if (entry.kind == MediaKind.DIRECTORY) {
                            PickerRow(entry.name, Icons.Outlined.Folder) { path = entry.path }
                        } else {
                            PickerRow(entry.name, Icons.Outlined.Subtitles) { onPick(entry) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerRow(text: String, icon: ImageVector, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Icon(icon, contentDescription = null) },
        colors = transparentRowColors(),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun PickerMessage(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
fun AudioSheet(
    tracks: List<Track>,
    boostPercent: Int,
    delay: Double,
    onSelectTrack: (Long?) -> Unit,
    onBoostChange: (Int, Boolean) -> Unit,
    onDelayChange: (Double) -> Unit,
    onDismiss: () -> Unit,
) = PlayerPanel(onDismiss) {
    val external = stringResource(R.string.player_track_external)
    SectionTitle(stringResource(R.string.player_audio))
    tracks.filter { it.kind == TrackKind.AUDIO }.forEach { track ->
        TrackRow(track.label(external), track.selected) { onSelectTrack(track.id) }
    }
    PercentSliderRow(
        title = stringResource(R.string.player_audio_boost),
        value = boostPercent,
        range = 100..MAX_VOLUME_PERCENT,
        onChange = onBoostChange,
    )
    SectionTitle(stringResource(R.string.player_audio_sync))
    StepperRow(
        value = stringResource(R.string.player_seconds_signed, delay),
        onDecrease = { onDelayChange(delay - SYNC_STEP_SECONDS) },
        onIncrease = { onDelayChange(delay + SYNC_STEP_SECONDS) },
        onReset = { onDelayChange(0.0) },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VideoSheet(
    aspect: AspectRatio,
    scale: ScaleMode,
    hardwareDecoding: Boolean,
    onAspectChange: (AspectRatio) -> Unit,
    onScaleChange: (ScaleMode) -> Unit,
    onHardwareDecodingChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) = PlayerPanel(onDismiss) {
    SectionTitle(stringResource(R.string.player_aspect_ratio))
    ChipRow {
        AspectRatio.entries.forEach { option ->
            FilterChip(
                selected = option == aspect,
                onClick = { onAspectChange(option) },
                label = { Text(if (option == AspectRatio.DEFAULT) stringResource(R.string.player_aspect_default) else option.label) },
            )
        }
    }
    SectionTitle(stringResource(R.string.player_scale))
    ChipRow {
        ScaleMode.entries.forEach { option ->
            FilterChip(
                selected = option == scale,
                onClick = { onScaleChange(option) },
                label = { Text(stringResource(option.label)) },
            )
        }
    }
    SectionTitle(stringResource(R.string.player_decoder))
    ChipRow {
        FilterChip(selected = hardwareDecoding, onClick = { onHardwareDecodingChange(true) }, label = { Text(stringResource(R.string.player_decoder_hw)) })
        FilterChip(selected = !hardwareDecoding, onClick = { onHardwareDecodingChange(false) }, label = { Text(stringResource(R.string.player_decoder_sw)) })
    }
}

@Composable
fun QueueSheet(
    queue: List<MediaRef>,
    currentPath: String?,
    repeat: RepeatMode,
    shuffle: Boolean,
    onRepeatChange: (RepeatMode) -> Unit,
    onShuffleChange: (Boolean) -> Unit,
    onPlay: (MediaRef) -> Unit,
    onDismiss: () -> Unit,
) = PlayerPanel(onDismiss) {
    SectionTitle(stringResource(R.string.player_repeat))
    ChipRow {
        RepeatMode.entries.forEach { mode ->
            FilterChip(selected = mode == repeat, onClick = { onRepeatChange(mode) }, label = { Text(stringResource(mode.label)) })
        }
    }
    SwitchRow(stringResource(R.string.player_shuffle), shuffle, onShuffleChange)
    SectionTitle(stringResource(R.string.player_queue))
    if (queue.isEmpty()) PickerMessage(stringResource(R.string.player_queue_empty))
    queue.forEach { ref ->
        val current = ref.path == currentPath
        ListItem(
            headlineContent = { Text(ref.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            leadingContent = if (current) {
                { Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
            } else {
                null
            },
            colors = transparentRowColors(),
            modifier = Modifier.clickable(enabled = !current) { onPlay(ref) },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun SectionTitle(text: String) =
    SectionHeader(text, Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp))

/** − value + with a reset, for sync offsets. */
@Composable
private fun StepperRow(value: String, onDecrease: () -> Unit, onIncrease: () -> Unit, onReset: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = onDecrease) { Text("−") }
        Text(value, style = MaterialTheme.typography.titleMedium, modifier = Modifier.widthIn(min = 72.dp))
        OutlinedButton(onClick = onIncrease) { Text("+") }
        TextButton(onClick = onReset) { Text(stringResource(R.string.player_reset)) }
    }
}

@Composable
private fun TrackRow(label: String, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = if (selected) {
            { Icon(Icons.Filled.Check, contentDescription = null) }
        } else {
            null
        },
        colors = transparentRowColors(),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** Making AI subtitles: start, progress, or what is missing first. */
@Composable
private fun AiSubtitleControls(ai: AiSubtitleStatus, actions: SubtitlePanelActions) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val hint: String? = when (ai) {
            is AiSubtitleStatus.NeedsModel -> when (val download = ai.download) {
                is ModelStatus.Downloading -> stringResource(R.string.ai_model_downloading_percent, ((download.fraction ?: 0f) * 100).toInt())
                is ModelStatus.Failed -> stringResource(R.string.ai_model_failed, download.reason)
                else -> stringResource(R.string.ai_model_needed, ai.model.sizeBytes / 1_000_000)
            }
            is AiSubtitleStatus.Ready -> stringResource(if (ai.made) R.string.ai_subtitles_made else R.string.ai_subtitles_hint)
            AiSubtitleStatus.Queued -> stringResource(R.string.ai_subtitles_queued)
            is AiSubtitleStatus.Running -> if (ai.totalSeconds > 0) {
                pluralStringResource(R.plurals.ai_subtitles_progress, ai.lines, formatTime(ai.doneSeconds), formatTime(ai.totalSeconds), ai.lines)
            } else {
                pluralStringResource(R.plurals.ai_subtitles_lines, ai.lines, ai.lines)
            }
            is AiSubtitleStatus.Failed -> stringResource(R.string.ai_subtitles_failed, ai.reason)
            AiSubtitleStatus.Unavailable -> null
        }
        hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (ai is AiSubtitleStatus.Running && ai.totalSeconds > 0) {
            LinearProgressIndicator(progress = { (ai.doneSeconds / ai.totalSeconds).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        }
        when (ai) {
            is AiSubtitleStatus.NeedsModel -> if (ai.download !is ModelStatus.Downloading) {
                OutlinedButton(onClick = actions::onDownloadAiModel) { Text(stringResource(R.string.ai_model_download)) }
            }
            is AiSubtitleStatus.Ready, is AiSubtitleStatus.Failed -> OutlinedButton(onClick = actions::onStartAiSubtitles) {
                Text(stringResource(if (ai is AiSubtitleStatus.Ready && ai.made) R.string.ai_subtitles_remake else R.string.ai_subtitles_start))
            }
            AiSubtitleStatus.Queued, is AiSubtitleStatus.Running -> OutlinedButton(onClick = actions::onCancelAiSubtitles) {
                Text(stringResource(R.string.ai_subtitles_stop))
            }
            AiSubtitleStatus.Unavailable -> Unit
        }
    }
}

private fun Track.label(externalLabel: String): String = listOfNotNull(
    title,
    language?.uppercase(),
    codec,
    externalLabel.takeIf { external },
).ifEmpty { listOf("#$id") }.joinToString(" · ")

/**
 * The settings screen's playback categories, changed without leaving the video.
 * Changes apply at once, as on the settings screen.
 */
@Composable
fun PlayerSettingsSheet(
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onDismiss: () -> Unit,
    /** The subtitle font row, which needs the font files; none in previews. */
    subtitleFont: (@Composable () -> Unit)? = null,
) =
    PlayerPanel(onDismiss, width = 480.dp, scrollable = false) {
        var category by rememberSaveable { mutableStateOf(PLAYER_SETTINGS_CATEGORIES.first()) }
        PrimaryScrollableTabRow(
            selectedTabIndex = PLAYER_SETTINGS_CATEGORIES.indexOf(category),
            containerColor = Color.Transparent,
            edgePadding = 8.dp,
        ) {
            PLAYER_SETTINGS_CATEGORIES.forEach { item ->
                Tab(selected = item == category, onClick = { category = item }, text = { Text(stringResource(item.title)) })
            }
        }
        LazyColumn(Modifier.fillMaxWidth()) { categoryRows(category, settings, onUpdate, subtitleFont) }
    }

internal val ScaleMode.label: Int
    get() = when (this) {
        ScaleMode.FIT -> R.string.player_scale_fit
        ScaleMode.STRETCH -> R.string.player_scale_stretch
        ScaleMode.CROP -> R.string.player_scale_crop
    }

val RepeatMode.label: Int
    get() = when (this) {
        RepeatMode.OFF -> R.string.repeat_off
        RepeatMode.ALL -> R.string.repeat_all
        RepeatMode.ONE -> R.string.repeat_one
    }

private const val SYNC_STEP_SECONDS = 0.1
