package dev.wplayer.app.ui.settings

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wplayer.app.R
import dev.wplayer.app.data.model.AppSettings
import dev.wplayer.app.data.model.BrowserSettings
import dev.wplayer.app.data.model.CenterDoubleTapAction
import dev.wplayer.app.data.model.FolderNavigation
import dev.wplayer.app.data.model.GestureSettings
import dev.wplayer.app.data.model.MediaButtonAction
import dev.wplayer.app.data.model.OrientationMode
import dev.wplayer.app.data.model.PlaybackSettings
import dev.wplayer.app.data.model.ResumeMode
import dev.wplayer.app.data.model.SUBTITLE_POSITION_RANGE
import dev.wplayer.app.data.model.SUBTITLE_SIZE_RANGE
import dev.wplayer.app.data.model.ScreenSettings
import dev.wplayer.app.data.model.SeekPrecision
import dev.wplayer.app.data.model.SubtitleColor
import dev.wplayer.app.data.model.SubtitleFont
import dev.wplayer.app.data.model.SubtitleSettings
import dev.wplayer.app.data.model.ThemeMode
import dev.wplayer.app.data.model.TranscriptionSettings
import dev.wplayer.app.data.model.VerticalSwipeAction
import dev.wplayer.app.data.model.VideoEnhancement
import dev.wplayer.app.data.model.WhisperModel
import dev.wplayer.app.data.transcription.ModelStatus
import dev.wplayer.app.ui.common.ListSlider
import dev.wplayer.app.ui.common.readableWidth
import dev.wplayer.app.ui.player.MAX_VOLUME_PERCENT
import kotlin.math.roundToInt

private val TWO_PANE_MIN_WIDTH = 840.dp
private val CATEGORY_PANE_WIDTH = 360.dp

private val SEEK_STEPS = listOf(1, 2, 3, 5, 10, 15, 20, 30, 60, 90, 120, 180, 300)
private val BOOST_SPEEDS = listOf(1.5, 2.0, 3.0)
private val READ_AHEAD_SECONDS = listOf(10, 30, 60, 120, 180, 300, 0)
private val BUFFER_SECONDS = listOf(1, 2, 3, 4, 5, 10, 15, 20, 30)
private val CACHE_MEGABYTES = listOf(0, 64, 128, 256, 512, 1024, 2048, 4096, 8192, 10240)

/** Language choices for track selection: ISO 639 codes, empty meaning "any". */
private val LANGUAGES = listOf("", "ko", "en", "ja", "zh")

/**
 * The settings home: one entry per category. Wide
 * windows show the chosen category beside the list instead of opening it.
 */
@Composable
fun SettingsScreen(onOpenCategory: (SettingsCategory) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= TWO_PANE_MIN_WIDTH) {
            var selected by rememberSaveable { mutableStateOf(SettingsCategory.GENERAL) }
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(CATEGORY_PANE_WIDTH)) {
                    SettingsHomeContent(selected = selected, onOpenCategory = { selected = it })
                }
                VerticalDivider()
                Box(Modifier.weight(1f)) { SettingsCategoryScreen(selected, onBack = null) }
            }
        } else {
            SettingsHomeContent(selected = null, onOpenCategory = onOpenCategory)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsHomeContent(selected: SettingsCategory?, onOpenCategory: (SettingsCategory) -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) }) { padding ->
        LazyColumn(modifier = Modifier.readableWidth(), contentPadding = padding) {
            items(SettingsCategory.entries) { category ->
                ListItem(
                    headlineContent = { Text(stringResource(category.title)) },
                    supportingContent = { Text(stringResource(category.summary)) },
                    leadingContent = { Icon(category.icon, contentDescription = null) },
                    colors = if (category == selected) {
                        ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    } else {
                        ListItemDefaults.colors()
                    },
                    modifier = Modifier.clickable { onOpenCategory(category) },
                )
            }
        }
    }
}

/** One category's settings; [onBack] is null when shown beside the category list. */
@Composable
fun SettingsCategoryScreen(category: SettingsCategory, onBack: (() -> Unit)?, viewModel: SettingsViewModel = hiltViewModel()) {
    when (category) {
        SettingsCategory.UPDATES -> return UpdateSettingsScreen(onBack)
        SettingsCategory.ABOUT -> return AboutScreen(onBack)
        else -> Unit
    }
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val modelStatus by viewModel.whisperModelStatus.collectAsStateWithLifecycle()
    val subtitleFonts by viewModel.subtitleFonts.collectAsStateWithLifecycle()
    val maintenance = remember(viewModel) {
        object : SettingsMaintenance {
            override fun clearHistory() = viewModel.clearHistory()
            override fun clearThumbnails() = viewModel.clearThumbnails()
            override fun clearSearchIndex() = viewModel.clearSearchIndex()
            override fun downloadWhisperModel(model: WhisperModel) = viewModel.downloadWhisperModel(model)
            override fun cancelWhisperModel(model: WhisperModel) = viewModel.cancelWhisperModel(model)
            override fun deleteWhisperModel(model: WhisperModel) = viewModel.deleteWhisperModel(model)
            override fun add(uri: Uri) = viewModel.addSubtitleFont(uri)
            override fun delete(font: SubtitleFont) = viewModel.deleteSubtitleFont(font)
            override fun dismissFailure() = viewModel.dismissSubtitleFontFailure()
        }
    }
    SettingsCategoryContent(category, settings, onBack, viewModel::update, maintenance, modelStatus, subtitleFonts)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsCategoryContent(
    category: SettingsCategory,
    settings: AppSettings,
    onBack: (() -> Unit)?,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    maintenance: SettingsMaintenance,
    /** Of the whisper model chosen in [settings]. */
    whisperModelStatus: ModelStatus = ModelStatus.NotInstalled,
    subtitleFonts: SubtitleFontsState = SubtitleFontsState(),
) {
    var confirmingClear by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(category.title)) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.readableWidth(), contentPadding = padding) {
            when (category) {
                SettingsCategory.GENERAL -> {
                    item { SectionHeader(R.string.settings_appearance) }
                    appearanceSection(settings, onUpdate)
                    item { SectionHeader(R.string.settings_browser) }
                    browserSection(settings, onUpdate)
                    item { SectionHeader(R.string.settings_history) }
                    item {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.settings_clear_history)) },
                            supportingContent = { Text(stringResource(R.string.settings_clear_history_hint)) },
                            modifier = Modifier.clickable { confirmingClear = true },
                        )
                    }
                    item {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.settings_clear_thumbnails)) },
                            supportingContent = { Text(stringResource(R.string.settings_clear_thumbnails_hint)) },
                            modifier = Modifier.clickable(onClick = maintenance::clearThumbnails),
                        )
                    }
                    item {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.settings_clear_search_index)) },
                            supportingContent = { Text(stringResource(R.string.settings_clear_search_index_hint)) },
                            modifier = Modifier.clickable(onClick = maintenance::clearSearchIndex),
                        )
                    }
                    item { SectionHeader(R.string.settings_developer) }
                    item {
                        SwitchRow(R.string.player_show_stats, settings.screen.showStats, R.string.settings_show_stats_hint) { on ->
                            onUpdate { it.copy(screen = it.screen.copy(showStats = on)) }
                        }
                    }
                }
                SettingsCategory.SUBTITLES -> {
                    categoryRows(category, settings, onUpdate) {
                        SubtitleFontRow(settings.subtitle.font, subtitleFonts, maintenance) { font ->
                            onUpdate { it.copy(subtitle = it.subtitle.copy(font = font)) }
                        }
                    }
                    item { SectionHeader(R.string.ai_subtitles) }
                    aiSubtitleSection(settings, onUpdate, whisperModelStatus, maintenance)
                }
                else -> categoryRows(category, settings, onUpdate)
            }
        }
    }
    if (confirmingClear) {
        AlertDialog(
            onDismissRequest = { confirmingClear = false },
            text = { Text(stringResource(R.string.recent_clear_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingClear = false
                        maintenance.clearHistory()
                    },
                ) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { confirmingClear = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** The settings screen's clean-up actions, and adding subtitle fonts. */
interface SettingsMaintenance : SubtitleFontActions {
    /** Forgets every resume position and the recently played list. */
    fun clearHistory()
    fun clearThumbnails()
    fun clearSearchIndex()
    fun downloadWhisperModel(model: WhisperModel)
    fun cancelWhisperModel(model: WhisperModel)
    fun deleteWhisperModel(model: WhisperModel)
}

/** Categories that change playback, also offered from the player's settings panel. */
val PLAYER_SETTINGS_CATEGORIES = listOf(
    SettingsCategory.PLAYBACK,
    SettingsCategory.SCREEN,
    SettingsCategory.SUBTITLES,
    SettingsCategory.AUDIO,
    SettingsCategory.GESTURES,
)

/**
 * The rows of [category]; General's history and developer actions only live on the settings screen,
 * as does [subtitleFont], the subtitle font row, which adds font files.
 */
fun LazyListScope.categoryRows(
    category: SettingsCategory,
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    subtitleFont: (@Composable () -> Unit)? = null,
) {
    when (category) {
        SettingsCategory.GENERAL -> {
            appearanceSection(settings, onUpdate)
            browserSection(settings, onUpdate)
        }
        SettingsCategory.PLAYBACK -> playbackSection(settings, onUpdate)
        SettingsCategory.SCREEN -> screenSection(settings, onUpdate)
        SettingsCategory.SUBTITLES -> subtitleSection(settings, onUpdate, subtitleFont)
        SettingsCategory.AUDIO -> audioSection(settings, onUpdate)
        SettingsCategory.GESTURES -> gestureSection(settings, onUpdate)
        SettingsCategory.NETWORK -> networkSection(settings, onUpdate)
        // Have their own screens; see UpdateSettingsScreen and AboutScreen.
        SettingsCategory.UPDATES, SettingsCategory.ABOUT -> Unit
    }
}

/** The whisper model and the spoken language; only on the settings screen, since the model has to be downloaded. */
private fun LazyListScope.aiSubtitleSection(
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    modelStatus: ModelStatus,
    maintenance: SettingsMaintenance,
) {
    val transcription = settings.transcription
    fun update(transform: (TranscriptionSettings) -> TranscriptionSettings) =
        onUpdate { it.copy(transcription = transform(it.transcription)) }
    item {
        Text(
            text = stringResource(R.string.settings_ai_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
    item {
        ChoiceRow(R.string.settings_ai_model, WhisperModel.entries, transcription.model, { stringResource(it.label) }) { model ->
            update { it.copy(model = model) }
        }
    }
    item {
        val model = transcription.model
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_ai_model_file)) },
            supportingContent = {
                Text(
                    when (modelStatus) {
                        ModelStatus.Installed -> stringResource(R.string.ai_model_installed, model.sizeBytes / 1_000_000)
                        is ModelStatus.Downloading -> stringResource(R.string.ai_model_downloading_percent, ((modelStatus.fraction ?: 0f) * 100).toInt())
                        is ModelStatus.Failed -> stringResource(R.string.ai_model_failed, modelStatus.reason)
                        ModelStatus.NotInstalled -> stringResource(R.string.ai_model_not_installed, model.sizeBytes / 1_000_000)
                    },
                )
            },
            trailingContent = {
                when (modelStatus) {
                    ModelStatus.Installed -> TextButton(onClick = { maintenance.deleteWhisperModel(model) }) { Text(stringResource(R.string.action_delete)) }
                    is ModelStatus.Downloading -> TextButton(onClick = { maintenance.cancelWhisperModel(model) }) { Text(stringResource(R.string.action_cancel)) }
                    else -> TextButton(onClick = { maintenance.downloadWhisperModel(model) }) { Text(stringResource(R.string.ai_model_download)) }
                }
            },
        )
    }
    item {
        ChoiceRow(R.string.settings_ai_language, LANGUAGES, transcription.language, { aiLanguageLabel(it) }) { language ->
            update { it.copy(language = language) }
        }
    }
}

@Composable
private fun aiLanguageLabel(code: String): String = if (code.isEmpty()) stringResource(R.string.ai_language_auto) else languageLabel(code)

private val WhisperModel.label: Int
    get() = when (this) {
        WhisperModel.FAST -> R.string.whisper_fast
        WhisperModel.ACCURATE -> R.string.whisper_accurate
    }

private fun LazyListScope.appearanceSection(settings: AppSettings, onUpdate: ((AppSettings) -> AppSettings) -> Unit) {
    val appearance = settings.appearance
    item { LanguageRow() }
    item {
        ChoiceRow(R.string.settings_theme, ThemeMode.entries, appearance.themeMode, { stringResource(it.label) }) { mode ->
            onUpdate { it.copy(appearance = it.appearance.copy(themeMode = mode)) }
        }
    }
    item {
        SwitchRow(R.string.settings_dynamic_color, appearance.dynamicColor, R.string.settings_dynamic_color_hint) { on ->
            onUpdate { it.copy(appearance = it.appearance.copy(dynamicColor = on)) }
        }
    }
    item {
        SwitchRow(R.string.settings_pure_black, appearance.pureBlack, R.string.settings_pure_black_hint) { on ->
            onUpdate { it.copy(appearance = it.appearance.copy(pureBlack = on)) }
        }
    }
}

private fun LazyListScope.playbackSection(settings: AppSettings, onUpdate: ((AppSettings) -> AppSettings) -> Unit) {
    val playback = settings.playback
    fun update(transform: (PlaybackSettings) -> PlaybackSettings) =
        onUpdate { it.copy(playback = transform(it.playback)) }
    item {
        SwitchRow(R.string.settings_hwdec, playback.hardwareDecoding, R.string.settings_hwdec_hint) { on ->
            update { it.copy(hardwareDecoding = on) }
        }
    }
    if (playback.hardwareDecoding) {
        item {
            SwitchRow(R.string.settings_direct_output, playback.directVideoOutput, R.string.settings_direct_output_hint) { on ->
                update { it.copy(directVideoOutput = on) }
            }
        }
    }
    item {
        ChoiceRow(R.string.settings_resume, ResumeMode.entries, playback.resume, { stringResource(it.label) }) { mode ->
            update { it.copy(resume = mode) }
        }
    }
    item {
        SwitchRow(R.string.settings_auto_next, playback.autoPlayNext, R.string.settings_auto_next_hint) { on ->
            update { it.copy(autoPlayNext = on) }
        }
    }
    item {
        SwitchRow(R.string.settings_keep_speed, playback.keepSpeed) { on -> update { it.copy(keepSpeed = on) } }
    }
    item {
        ChoiceRow(R.string.settings_seek_precision, SeekPrecision.entries, playback.seekPrecision, { stringResource(it.label) }) { precision ->
            update { it.copy(seekPrecision = precision) }
        }
    }
    item {
        PickerRow(R.string.settings_seek_back, SEEK_STEPS, playback.seekBackSeconds, { stringResource(R.string.settings_seconds, it) }) { step ->
            update { it.copy(seekBackSeconds = step) }
        }
    }
    item {
        PickerRow(R.string.settings_seek_forward, SEEK_STEPS, playback.seekForwardSeconds, { stringResource(R.string.settings_seconds, it) }) { step ->
            update { it.copy(seekForwardSeconds = step) }
        }
    }
    item {
        SwitchRow(R.string.settings_combine_seeks, playback.combineRepeatedSeeks, R.string.settings_combine_seeks_hint) { on ->
            update { it.copy(combineRepeatedSeeks = on) }
        }
    }
    item {
        SwitchRow(R.string.settings_step_seek_time, playback.showStepSeekTime, R.string.settings_step_seek_time_hint) { on ->
            update { it.copy(showStepSeekTime = on) }
        }
    }
    item {
        ChoiceRow(R.string.settings_long_press_speed, BOOST_SPEEDS, playback.longPressSpeed, { "${it}x" }) { speed ->
            update { it.copy(longPressSpeed = speed) }
        }
    }
    item {
        SwitchRow(R.string.settings_auto_pip, playback.autoPictureInPicture) { on -> update { it.copy(autoPictureInPicture = on) } }
    }
    item {
        SwitchRow(R.string.settings_background_play, playback.backgroundPlay, R.string.settings_background_play_hint) { on ->
            update { it.copy(backgroundPlay = on) }
        }
    }
    item {
        ChoiceRow(R.string.settings_media_buttons, MediaButtonAction.entries, playback.mediaButtons, { stringResource(it.label) }) { action ->
            update { it.copy(mediaButtons = action) }
        }
    }
}

private fun LazyListScope.screenSection(settings: AppSettings, onUpdate: ((AppSettings) -> AppSettings) -> Unit) {
    val screen = settings.screen
    fun update(transform: (ScreenSettings) -> ScreenSettings) =
        onUpdate { it.copy(screen = transform(it.screen)) }
    item {
        ChoiceRow(R.string.settings_enhancement, VideoEnhancement.entries, screen.enhancement, { stringResource(it.label) }) { enhancement ->
            update { it.copy(enhancement = enhancement) }
        }
    }
    item {
        SwitchRow(R.string.settings_deband, screen.deband, R.string.settings_deband_hint) { on -> update { it.copy(deband = on) } }
    }
    item {
        SwitchRow(R.string.settings_smooth_motion, screen.smoothMotion, R.string.settings_smooth_motion_hint) { on ->
            update { it.copy(smoothMotion = on) }
        }
    }
    item {
        ChoiceRow(R.string.settings_orientation, OrientationMode.entries, screen.orientation, { stringResource(it.label) }) { mode ->
            update { it.copy(orientation = mode) }
        }
    }
    item {
        SwitchRow(R.string.settings_save_brightness, screen.saveBrightness) { on ->
            update { it.copy(saveBrightness = on, savedBrightness = if (on) it.savedBrightness else null) }
        }
    }
    item {
        SwitchRow(R.string.settings_auto_hide, screen.autoHideControls) { on -> update { it.copy(autoHideControls = on) } }
    }
    item {
        SwitchRow(R.string.settings_show_clock, screen.showClock) { on -> update { it.copy(showClock = on) } }
    }
}

private fun LazyListScope.subtitleSection(
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    fontRow: (@Composable () -> Unit)?,
) {
    val subtitle = settings.subtitle
    fun update(transform: (SubtitleSettings) -> SubtitleSettings) =
        onUpdate { it.copy(subtitle = transform(it.subtitle)) }
    item {
        SwitchRow(R.string.settings_subtitle_show, subtitle.showByDefault) { on -> update { it.copy(showByDefault = on) } }
    }
    item {
        PickerRow(R.string.settings_subtitle_language, LANGUAGES, subtitle.preferredLanguage, { languageLabel(it) }) { code ->
            update { it.copy(preferredLanguage = code) }
        }
    }
    item {
        SliderRow(R.string.settings_subtitle_size, subtitle.sizePercent, SUBTITLE_SIZE_RANGE) { percent ->
            update { it.copy(sizePercent = percent) }
        }
    }
    item {
        SliderRow(R.string.settings_subtitle_position, subtitle.position, SUBTITLE_POSITION_RANGE) { position ->
            update { it.copy(position = position) }
        }
    }
    item {
        ChoiceRow(R.string.settings_subtitle_color, SubtitleColor.entries, subtitle.color, { stringResource(it.label) }) { color ->
            update { it.copy(color = color) }
        }
    }
    fontRow?.let { item { it() } }
    item { SwitchRow(R.string.settings_subtitle_bold, subtitle.bold) { on -> update { it.copy(bold = on) } } }
    item { SwitchRow(R.string.settings_subtitle_outline, subtitle.outline) { on -> update { it.copy(outline = on) } } }
    item { SwitchRow(R.string.settings_subtitle_shadow, subtitle.shadow) { on -> update { it.copy(shadow = on) } } }
    item { SwitchRow(R.string.settings_subtitle_background, subtitle.background) { on -> update { it.copy(background = on) } } }
    item {
        SwitchRow(R.string.settings_subtitle_ass, subtitle.assFullStyling, R.string.settings_subtitle_ass_hint) { on ->
            update { it.copy(assFullStyling = on) }
        }
    }
    item {
        SwitchRow(R.string.settings_subtitle_embedded_fonts, subtitle.embeddedFonts) { on -> update { it.copy(embeddedFonts = on) } }
    }
}

private fun LazyListScope.audioSection(settings: AppSettings, onUpdate: ((AppSettings) -> AppSettings) -> Unit) {
    val audio = settings.audio
    item {
        SliderRow(R.string.settings_audio_boost, audio.boostPercent, 100..MAX_VOLUME_PERCENT) { percent ->
            onUpdate { it.copy(audio = it.audio.copy(boostPercent = percent)) }
        }
    }
    item {
        PickerRow(R.string.settings_audio_language, LANGUAGES, audio.preferredLanguage, { languageLabel(it) }) { code ->
            onUpdate { it.copy(audio = it.audio.copy(preferredLanguage = code)) }
        }
    }
}

private fun LazyListScope.networkSection(settings: AppSettings, onUpdate: ((AppSettings) -> AppSettings) -> Unit) {
    val network = settings.network
    item {
        PickerRow(
            R.string.settings_read_ahead,
            READ_AHEAD_SECONDS,
            network.readAheadSeconds,
            { if (it == 0) stringResource(R.string.settings_read_ahead_max) else stringResource(R.string.settings_seconds, it) },
            supporting = R.string.settings_read_ahead_hint,
        ) { seconds -> onUpdate { it.copy(network = it.network.copy(readAheadSeconds = seconds)) } }
    }
    item {
        PickerRow(
            R.string.settings_buffer,
            BUFFER_SECONDS,
            network.bufferSeconds,
            { stringResource(R.string.settings_seconds, it) },
            supporting = R.string.settings_buffer_hint,
        ) { seconds -> onUpdate { it.copy(network = it.network.copy(bufferSeconds = seconds)) } }
    }
    item {
        PickerRow(
            R.string.settings_cache,
            CACHE_MEGABYTES,
            network.cacheMegabytes,
            { megabytes -> cacheLabel(megabytes) },
            supporting = R.string.settings_cache_hint,
        ) { megabytes -> onUpdate { it.copy(network = it.network.copy(cacheMegabytes = megabytes)) } }
    }
}

private fun LazyListScope.browserSection(settings: AppSettings, onUpdate: ((AppSettings) -> AppSettings) -> Unit) {
    val browser = settings.browser
    fun update(transform: (BrowserSettings) -> BrowserSettings) =
        onUpdate { it.copy(browser = transform(it.browser)) }
    item {
        SwitchRow(R.string.settings_column_view, browser.columnView, R.string.settings_column_view_hint) { on ->
            update { it.copy(columnView = on) }
        }
    }
    item {
        ChoiceRow(R.string.settings_folder_navigation, FolderNavigation.entries, browser.folderNavigation, { stringResource(it.label) }) { navigation ->
            update { it.copy(folderNavigation = navigation) }
        }
    }
    item {
        SwitchRow(R.string.settings_background_indexing, browser.backgroundIndexing, R.string.settings_background_indexing_hint) { on ->
            update { it.copy(backgroundIndexing = on) }
        }
    }
    item {
        SwitchRow(R.string.settings_show_continue_watching, browser.showContinueWatching, R.string.settings_show_continue_watching_hint) { on ->
            update { it.copy(showContinueWatching = on) }
        }
    }
    item { SwitchRow(R.string.settings_show_thumbnails, browser.showThumbnails) { on -> update { it.copy(showThumbnails = on) } } }
    item { SwitchRow(R.string.settings_show_progress, browser.showProgress) { on -> update { it.copy(showProgress = on) } } }
    item {
        SwitchRow(R.string.settings_show_subtitle_files, browser.showSubtitleFiles) { on -> update { it.copy(showSubtitleFiles = on) } }
    }
    item { SwitchRow(R.string.settings_show_other_files, browser.showOtherFiles) { on -> update { it.copy(showOtherFiles = on) } } }
    item { SwitchRow(R.string.settings_show_hidden_files, browser.showHiddenFiles) { on -> update { it.copy(showHiddenFiles = on) } } }
}

private fun LazyListScope.gestureSection(settings: AppSettings, onUpdate: ((AppSettings) -> AppSettings) -> Unit) {
    val gestures = settings.gestures
    fun update(transform: (GestureSettings) -> GestureSettings) =
        onUpdate { it.copy(gestures = transform(it.gestures)) }
    item {
        ChoiceRow(R.string.settings_left_vertical, VerticalSwipeAction.entries, gestures.leftVertical, { stringResource(it.label) }) { action ->
            update { it.copy(leftVertical = action) }
        }
    }
    item {
        ChoiceRow(R.string.settings_right_vertical, VerticalSwipeAction.entries, gestures.rightVertical, { stringResource(it.label) }) { action ->
            update { it.copy(rightVertical = action) }
        }
    }
    item { SwitchRow(R.string.settings_horizontal_seek, gestures.horizontalSeek) { on -> update { it.copy(horizontalSeek = on) } } }
    item { SwitchRow(R.string.settings_double_tap_sides, gestures.doubleTapSidesSeek) { on -> update { it.copy(doubleTapSidesSeek = on) } } }
    item {
        ChoiceRow(R.string.settings_double_tap_center, CenterDoubleTapAction.entries, gestures.doubleTapCenter, { stringResource(it.label) }) { action ->
            update { it.copy(doubleTapCenter = action) }
        }
    }
    item { SwitchRow(R.string.settings_long_press, gestures.longPressSpeedUp) { on -> update { it.copy(longPressSpeedUp = on) } } }
    item { SwitchRow(R.string.settings_pinch_zoom, gestures.pinchZoom) { on -> update { it.copy(pinchZoom = on) } } }
    item {
        SwitchRow(R.string.settings_subtitle_gestures, gestures.subtitleGestures, R.string.settings_subtitle_gestures_hint) { on ->
            update { it.copy(subtitleGestures = on) }
        }
    }
    item { SwitchRow(R.string.settings_two_finger_pip, gestures.twoFingerDownPip) { on -> update { it.copy(twoFingerDownPip = on) } } }
    item {
        SwitchRow(R.string.settings_two_finger_sub_delay, gestures.twoFingerSubtitleDelay) { on ->
            update { it.copy(twoFingerSubtitleDelay = on) }
        }
    }
}

@Composable
internal fun SectionHeader(@StringRes title: Int) {
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun SwitchRow(
    @StringRes title: Int,
    checked: Boolean,
    @StringRes supporting: Int? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = supporting?.let { { Text(stringResource(it)) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
        modifier = Modifier.clickable { onCheckedChange(!checked) },
    )
}

/** The app's language; kept by the system rather than in [AppSettings] (see [AppLanguage]). */
@Composable
private fun LanguageRow() {
    var language by remember { mutableStateOf(AppLanguage.current()) }
    ChoiceRow(
        R.string.settings_language,
        AppLanguage.entries,
        language,
        { if (it == AppLanguage.SYSTEM) stringResource(R.string.language_system) else it.nativeName },
    ) { picked ->
        language = picked
        picked.apply()
    }
}

/** Segmented buttons, for up to four short options. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ChoiceRow(
    @StringRes title: Int,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = {
            Row(Modifier.padding(top = 8.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    options.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = option == selected,
                            onClick = { onSelect(option) },
                            shape = SegmentedButtonDefaults.itemShape(index, options.size),
                            label = { Text(label(option), maxLines = 1) },
                        )
                    }
                }
            }
        },
    )
}

/** The current value with a drop-down of choices, for longer option lists. */
@Composable
private fun <T> PickerRow(
    @StringRes title: Int,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    @StringRes supporting: Int? = null,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ListItem(
            headlineContent = { Text(stringResource(title)) },
            supportingContent = supporting?.let { { Text(stringResource(it)) } },
            trailingContent = { Text(label(selected), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) },
            modifier = Modifier.clickable { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(label(option)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

/** A percentage slider that saves when released, not on every step. */
@Composable
private fun SliderRow(@StringRes title: Int, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    var dragging by remember { mutableFloatStateOf(Float.NaN) }
    val shown = if (dragging.isNaN()) value.toFloat() else dragging
    ListItem(
        headlineContent = {
            Row {
                Text(stringResource(title), modifier = Modifier.weight(1f))
                Text("${shown.roundToInt()}%", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            }
        },
        supportingContent = {
            Column {
                ListSlider(
                    value = shown,
                    onValueChange = { dragging = it },
                    onValueChangeFinished = {
                        // A key press can finish without a change (at either end of the range).
                        if (!dragging.isNaN()) onChange(dragging.roundToInt())
                        dragging = Float.NaN
                    },
                    valueRange = range.first.toFloat()..range.last.toFloat(),
                )
            }
        },
    )
}

@Composable
private fun cacheLabel(megabytes: Int): String = when {
    megabytes == 0 -> stringResource(R.string.settings_cache_none)
    megabytes >= 1024 -> stringResource(R.string.settings_gigabytes, megabytes / 1024)
    else -> stringResource(R.string.settings_megabytes, megabytes)
}

@Composable
private fun languageLabel(code: String): String = when (code) {
    "" -> stringResource(R.string.language_any)
    "ko" -> stringResource(R.string.language_korean)
    "en" -> stringResource(R.string.language_english)
    "ja" -> stringResource(R.string.language_japanese)
    "zh" -> stringResource(R.string.language_chinese)
    else -> code
}

private val MediaButtonAction.label: Int
    get() = when (this) {
        MediaButtonAction.FILES -> R.string.media_buttons_files
        MediaButtonAction.SEEK -> R.string.media_buttons_seek
    }

private val ThemeMode.label: Int
    get() = when (this) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    }

private val CenterDoubleTapAction.label: Int
    get() = when (this) {
        CenterDoubleTapAction.PICTURE_IN_PICTURE -> R.string.double_tap_center_pip
        CenterDoubleTapAction.PLAY_PAUSE -> R.string.double_tap_center_pause
        CenterDoubleTapAction.NONE -> R.string.double_tap_center_none
    }

private val FolderNavigation.label: Int
    get() = when (this) {
        FolderNavigation.BOTH -> R.string.folder_navigation_both
        FolderNavigation.PATH_BAR -> R.string.folder_navigation_path_bar
        FolderNavigation.PARENT_ROW -> R.string.folder_navigation_parent_row
    }

private val ResumeMode.label: Int
    get() = when (this) {
        ResumeMode.ALWAYS -> R.string.resume_always
        ResumeMode.NEVER -> R.string.resume_never
        ResumeMode.ASK -> R.string.resume_ask
    }

private val SeekPrecision.label: Int
    get() = when (this) {
        SeekPrecision.FAST -> R.string.seek_fast
        SeekPrecision.ACCURATE -> R.string.seek_accurate
    }

private val VideoEnhancement.label: Int
    get() = when (this) {
        VideoEnhancement.OFF -> R.string.enhancement_off
        VideoEnhancement.SHARP -> R.string.enhancement_sharp
        VideoEnhancement.DETAIL -> R.string.enhancement_detail
        VideoEnhancement.ANIME -> R.string.enhancement_anime
    }

private val OrientationMode.label: Int
    get() = when (this) {
        OrientationMode.AUTO -> R.string.orientation_auto
        OrientationMode.SENSOR -> R.string.orientation_sensor
        OrientationMode.LANDSCAPE -> R.string.orientation_landscape
        OrientationMode.PORTRAIT -> R.string.orientation_portrait
    }

private val SubtitleColor.label: Int
    get() = when (this) {
        SubtitleColor.WHITE -> R.string.color_white
        SubtitleColor.YELLOW -> R.string.color_yellow
        SubtitleColor.CYAN -> R.string.color_cyan
        SubtitleColor.GREEN -> R.string.color_green
    }

private val VerticalSwipeAction.label: Int
    get() = when (this) {
        VerticalSwipeAction.BRIGHTNESS -> R.string.settings_action_brightness
        VerticalSwipeAction.VOLUME -> R.string.settings_action_volume
        VerticalSwipeAction.NONE -> R.string.settings_action_none
    }
