package dev.wplayer.app.ui.player

import android.graphics.Rect
import android.os.SystemClock
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAndroidRect
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.roundToIntRect
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wplayer.app.R
import dev.wplayer.app.data.model.AppSettings
import dev.wplayer.app.data.model.CenterDoubleTapAction
import dev.wplayer.app.data.model.SUBTITLE_POSITION_RANGE
import dev.wplayer.app.data.model.SUBTITLE_SIZE_RANGE
import dev.wplayer.app.data.model.VerticalSwipeAction
import dev.wplayer.app.ui.common.message
import dev.wplayer.core.ffi.TrackKind
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** Transient readout shown in the middle of the screen while a gesture is active. */
private sealed interface GestureFeedback {
    data class Seek(val target: Double, val delta: Double) : GestureFeedback
    data class Level(val action: VerticalSwipeAction, val fraction: Float) : GestureFeedback
    data class SubtitleDelay(val seconds: Double) : GestureFeedback
    data class SubtitleSize(val percent: Int) : GestureFeedback
    data class SubtitlePosition(val position: Int) : GestureFeedback
    data class Zoom(val scale: Float) : GestureFeedback
    data class Speed(val speed: Double) : GestureFeedback
}

@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    inPictureInPicture: Boolean,
    canPictureInPicture: Boolean,
    onEnterPictureInPicture: () -> Unit,
    onVideoBoundsChanged: (Rect) -> Unit,
    keyCommands: Flow<PlayerKeyCommand>,
    /** Whether D-pad keys should move focus (controls or a panel) rather than drive playback. */
    onKeysNavigateChange: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val seekPreview by viewModel.seekPreview.collectAsStateWithLifecycle()
    val subtitlePlacement by viewModel.subtitlePlacement.collectAsStateWithLifecycle()
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var locked by rememberSaveable { mutableStateOf(false) }
    var sheet by rememberSaveable { mutableStateOf(PlayerSheet.NONE) }
    var feedback by remember { mutableStateOf<GestureFeedback?>(null) }
    var stepBurst by remember { mutableStateOf<StepSeekBurst?>(null) }
    var speedBoostActive by remember { mutableStateOf(false) }
    // Any control use restarts the auto-hide timer; a held seek bar suspends it.
    var lastInteractionMillis by remember { mutableLongStateOf(0L) }
    var seekBarDragging by remember { mutableStateOf(false) }
    var quickMenuOpen by remember { mutableStateOf(false) }
    // The controls were brought up from the keys and hold focus, so the D-pad moves through them.
    var controlsFocused by remember { mutableStateOf(false) }
    fun onControlUsed() {
        lastInteractionMillis = SystemClock.uptimeMillis()
    }

    val brightness = rememberBrightnessController()
    val volume = rememberVolumeController()
    val currentState by rememberUpdatedState(state)
    val currentSettings by rememberUpdatedState(settings)

    /**
     * Readouts for a seek by steps (remote, keyboard, double tap): the side
     * indicator adds up [step]s; the center time, when the settings ask for it,
     * shows where the seek lands, [total] seconds from here.
     */
    fun showStepSeek(step: Double, total: Double = step) {
        stepBurst = stepBurst.plus(step)
        if (currentSettings.playback.showStepSeekTime) {
            feedback = GestureFeedback.Seek((currentState.playback.position + total).coerceAtLeast(0.0), total)
        }
    }

    LaunchedEffect(brightness) {
        viewModel.savedBrightness()?.let(brightness::restore)
    }

    val gestureCallbacks = remember(viewModel, brightness, volume) {
        object : PlayerGestureCallbacks {
            private var seekStart: Double? = null
            private var seekTarget = 0.0
            private var subtitleDelayStart: Double? = null
            private var lastVerticalAction: VerticalSwipeAction? = null

            // A subtitle drag or pinch previews these, then saves them when it ends.
            private var subtitlePosition: Float? = null
            private var subtitleSize: Float? = null

            override fun onTap() {
                controlsVisible = !controlsVisible
            }

            override fun onDoubleTap(zone: TapZone) {
                val gestures = currentSettings.gestures
                val playback = currentSettings.playback
                when (zone) {
                    TapZone.LEFT -> if (gestures.doubleTapSidesSeek) {
                        val step = playback.seekBackSeconds.toDouble()
                        viewModel.seekBy(-step)
                        showStepSeek(-step)
                    }
                    TapZone.RIGHT -> if (gestures.doubleTapSidesSeek) {
                        val step = playback.seekForwardSeconds.toDouble()
                        viewModel.seekBy(step)
                        showStepSeek(step)
                    }
                    TapZone.CENTER -> when (gestures.doubleTapCenter) {
                        CenterDoubleTapAction.PICTURE_IN_PICTURE -> onEnterPictureInPicture()
                        CenterDoubleTapAction.PLAY_PAUSE -> viewModel.togglePause()
                        CenterDoubleTapAction.NONE -> Unit
                    }
                }
            }

            override fun onLongPressStart() {
                viewModel.beginSpeedBoost(currentSettings.playback.longPressSpeed)
                speedBoostActive = true
            }

            override fun onLongPressEnd() {
                viewModel.endSpeedBoost()
                speedBoostActive = false
            }

            override fun onSeekDrag(totalFraction: Float) {
                val playback = currentState.playback
                if (playback.duration <= 0) return // live or unknown length: nothing to scrub
                val start = seekStart ?: playback.position.also { seekStart = it }
                val range = playback.duration.coerceIn(MIN_SEEK_RANGE_SECONDS, MAX_SEEK_RANGE_SECONDS)
                seekTarget = (start + totalFraction * range).coerceIn(0.0, playback.duration.coerceAtLeast(0.0))
                feedback = GestureFeedback.Seek(seekTarget, seekTarget - start)
                viewModel.showSeekPreview(seekTarget)
            }

            override fun onSeekDragEnd() {
                if (seekStart != null) viewModel.seekTo(seekTarget)
                seekStart = null
                viewModel.hideSeekPreview()
            }

            override fun onVerticalDrag(action: VerticalSwipeAction, deltaFraction: Float) {
                val level = when (action) {
                    VerticalSwipeAction.BRIGHTNESS -> brightness.adjust(deltaFraction)
                    VerticalSwipeAction.VOLUME -> volume.adjust(deltaFraction)
                    VerticalSwipeAction.NONE -> return
                }
                lastVerticalAction = action
                feedback = GestureFeedback.Level(action, level)
            }

            override fun onVerticalDragEnd() {
                if (lastVerticalAction == VerticalSwipeAction.BRIGHTNESS) viewModel.rememberBrightness(brightness.level)
                lastVerticalAction = null
                volume.sync()
            }

            override fun onPinch(zoomFactor: Float) {
                feedback = GestureFeedback.Zoom(viewModel.zoomBy(zoomFactor))
            }

            override fun onTwoFingerSwipeDown() = onEnterPictureInPicture()

            override fun onTwoFingerHorizontalDrag(totalFraction: Float) {
                val start = subtitleDelayStart ?: currentState.playback.subtitleDelay.also { subtitleDelayStart = it }
                val delay = start + (totalFraction * SUBTITLE_DELAY_RANGE_SECONDS * 10).roundToInt() / 10.0
                viewModel.setSubtitleDelay(delay)
                feedback = GestureFeedback.SubtitleDelay(delay)
            }

            override fun onTwoFingerHorizontalDragEnd() {
                subtitleDelayStart = null
            }

            override fun isOnSubtitle(position: Offset, size: IntSize): Boolean {
                val playback = currentState.playback
                if (playback.subtitleText.isBlank() || !currentState.subtitlesVisible || size.height == 0) return false
                val bottom = viewModel.effectiveSubtitlePosition() / 100f * size.height
                val lines = playback.subtitleText.trim().lines().size
                val lineHeight = size.height * SUBTITLE_LINE_FRACTION * currentSettings.subtitle.sizePercent / 100f
                val slack = size.height * SUBTITLE_TOUCH_SLACK
                return position.y in (bottom - lines * lineHeight - slack)..(bottom + slack)
            }

            override fun onSubtitleDrag(deltaFraction: Float) {
                val start = subtitlePosition ?: currentSettings.subtitle.position.toFloat()
                val position = (start - deltaFraction * 100).coerceIn(SUBTITLE_POSITION_RANGE.toFloatRange())
                subtitlePosition = position
                viewModel.setSubtitlePosition(position.roundToInt(), save = false)
                feedback = GestureFeedback.SubtitlePosition(position.roundToInt())
            }

            override fun onSubtitlePinch(zoomFactor: Float) {
                val start = subtitleSize ?: currentSettings.subtitle.sizePercent.toFloat()
                val size = (start * zoomFactor).coerceIn(SUBTITLE_SIZE_RANGE.toFloatRange())
                subtitleSize = size
                viewModel.setSubtitleSize(size.roundToInt(), save = false)
                feedback = GestureFeedback.SubtitleSize(size.roundToInt())
            }

            override fun onSubtitleStep(lines: Int) = viewModel.stepSubtitle(lines)

            override fun onSubtitleAdjustEnd() {
                subtitlePosition?.let { viewModel.setSubtitlePosition(it.roundToInt()) }
                subtitleSize?.let { viewModel.setSubtitleSize(it.roundToInt()) }
                subtitlePosition = null
                subtitleSize = null
            }
        }
    }

    LaunchedEffect(keyCommands, brightness, volume) {
        // Seconds of combined seek taps not yet applied, and the seek that applies them.
        var pendingSeek = 0.0
        var pendingSeekJob: Job? = null
        fun dropPendingSeek() {
            pendingSeekJob?.cancel()
            pendingSeek = 0.0
        }
        keyCommands.collect { command ->
            val playback = currentSettings.playback
            when (command) {
                PlayerKeyCommand.SEEK_BACK, PlayerKeyCommand.SEEK_FORWARD -> {
                    val step = if (command == PlayerKeyCommand.SEEK_FORWARD) {
                        playback.seekForwardSeconds.toDouble()
                    } else {
                        -playback.seekBackSeconds.toDouble()
                    }
                    if (playback.combineRepeatedSeeks) {
                        // Each seek stalls the picture while it decodes up to the target, so a burst
                        // of taps only moves the readout and one seek follows the last tap.
                        pendingSeekJob?.cancel()
                        val total = pendingSeek + step
                        pendingSeek = total
                        showStepSeek(step, total)
                        pendingSeekJob = launch {
                            delay(SEEK_COMBINE_WINDOW_MS)
                            pendingSeek = 0.0
                            viewModel.seekBy(total)
                        }
                    } else {
                        viewModel.seekBy(step)
                        showStepSeek(step)
                    }
                }
                PlayerKeyCommand.TOGGLE_PAUSE -> viewModel.togglePause()
                PlayerKeyCommand.NEXT_FILE -> {
                    dropPendingSeek()
                    viewModel.nextFile()?.let(viewModel::play)
                }
                PlayerKeyCommand.RESTART_OR_PREVIOUS -> {
                    dropPendingSeek()
                    viewModel.restartOrPlayPrevious()
                }
                PlayerKeyCommand.BRIGHTNESS_UP, PlayerKeyCommand.BRIGHTNESS_DOWN -> {
                    val step = if (command == PlayerKeyCommand.BRIGHTNESS_UP) BRIGHTNESS_KEY_STEP else -BRIGHTNESS_KEY_STEP
                    feedback = GestureFeedback.Level(VerticalSwipeAction.BRIGHTNESS, brightness.adjust(step))
                    viewModel.rememberBrightness(brightness.level)
                }
                PlayerKeyCommand.FOCUS_CONTROLS -> if (!locked) {
                    controlsVisible = true
                    controlsFocused = true
                    onControlUsed()
                }
                PlayerKeyCommand.VOLUME_CHANGED -> volume.sync()
            }
        }
    }

    val controlActions = remember(viewModel) {
        object : PlayerControlActions {
            override fun onBack() = onClose()
            override fun onTogglePause() {
                onControlUsed()
                viewModel.togglePause()
            }
            override fun onSeekBy(seconds: Double) {
                onControlUsed()
                viewModel.seekBy(seconds)
            }
            override fun onSeekTo(seconds: Double) {
                onControlUsed()
                viewModel.seekTo(seconds)
            }
            override fun onSeekBarDragging(dragging: Boolean) {
                onControlUsed()
                seekBarDragging = dragging
            }
            override fun onSeekPreview(seconds: Double) = viewModel.showSeekPreview(seconds)
            override fun onSeekPreviewEnd() = viewModel.hideSeekPreview()
            override fun onPrevious() {
                onControlUsed()
                viewModel.previousFile()?.let(viewModel::play)
            }
            override fun onNext() {
                onControlUsed()
                viewModel.nextFile()?.let(viewModel::play)
            }
            override fun onOpenSheet(panel: PlayerSheet) {
                sheet = panel
            }
            override fun onCycleAbRepeat() {
                onControlUsed()
                viewModel.cycleAbRepeat()
            }
            override fun onLock() {
                locked = true
                controlsVisible = true
            }
            override fun onPictureInPicture() = onEnterPictureInPicture()
            override fun onSetSpeed(speed: Double) = viewModel.setSpeed(speed)
            override fun onSetScale(scale: ScaleMode) = viewModel.setScale(scale)
            override fun onSetAspect(aspect: AspectRatio) = viewModel.setAspect(aspect)
            override fun onSetHardwareDecoding(enabled: Boolean) = viewModel.setHardwareDecoding(enabled)
            override fun onQuickMenu(open: Boolean) {
                onControlUsed()
                quickMenuOpen = open
            }
        }
    }

    // Controls fade out after a few seconds of playback without interaction.
    LaunchedEffect(controlsVisible, state.playback.paused, sheet, lastInteractionMillis, seekBarDragging, quickMenuOpen, settings.screen.autoHideControls) {
        val idle = !state.playback.paused && sheet == PlayerSheet.NONE && !seekBarDragging && !quickMenuOpen
        if (controlsVisible && idle && (settings.screen.autoHideControls || locked)) {
            delay(CONTROLS_TIMEOUT_MS)
            controlsVisible = false
        }
    }
    LaunchedEffect(state.notice) {
        if (state.notice != null) {
            delay(NOTICE_TIMEOUT_MS)
            viewModel.dismissNotice()
        }
    }
    LaunchedEffect(feedback) {
        if (feedback != null) {
            delay(FEEDBACK_TIMEOUT_MS)
            feedback = null
        }
    }
    LaunchedEffect(stepBurst) {
        val burst = stepBurst ?: return@LaunchedEffect
        if (burst.active) {
            delay(STEP_SEEK_BURST_MS)
            stepBurst = burst.copy(active = false)
        }
    }
    BackHandler(enabled = locked) { controlsVisible = true }
    // Back first puts the focused controls away, then (from the plain picture) leaves.
    BackHandler(enabled = controlsVisible && controlsFocused) { controlsVisible = false }
    LaunchedEffect(controlsVisible) {
        if (!controlsVisible) controlsFocused = false
    }
    val keysNavigate = sheet != PlayerSheet.NONE || state.resumePrompt != null || (controlsVisible && controlsFocused)
    val currentOnKeysNavigateChange by rememberUpdatedState(onKeysNavigateChange)
    LaunchedEffect(keysNavigate) { currentOnKeysNavigateChange(keysNavigate) }
    LaunchedEffect(state.finished) {
        if (state.finished) onClose()
    }

    // Keep subtitles clear of the seek bar while the controls are shown.
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val seekBarHeight = with(LocalDensity.current) { SEEK_BAR_CLEARANCE.toPx() }
    LaunchedEffect(controlsVisible, locked, windowHeight) {
        val lift = if (controlsVisible && !locked && windowHeight > 0) seekBarHeight / windowHeight else 0f
        viewModel.setSubtitleLift(lift)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // A replacement engine cannot reuse a surface the previous one may still hold.
        key(state.engineGeneration) {
            val playback = state.playback
            val videoAspect = state.aspect.ratio ?: (playback.videoWidth.toFloat() / playback.videoHeight).takeIf { playback.videoHeight > 0 }
            VideoSurface(
                viewModel = viewModel,
                modifier = Modifier
                    .then(if (state.directOutput) Modifier.directVideoSize(videoAspect, state.scale, state.zoom) else Modifier.fillMaxSize())
                    .onGloballyPositioned { onVideoBoundsChanged(it.boundsInWindow().roundToIntRect().toAndroidRect()) },
            )
        }
        if (state.directOutput && state.subtitlesVisible) {
            DirectSubtitles(state.playback.subtitleText, settings.subtitle, subtitlePlacement, Modifier.fillMaxSize())
        }

        // Overlay content is drawn straight over video, outside any Surface: default to white.
        if (!inPictureInPicture) {
            CompositionLocalProvider(LocalContentColor provides Color.White) {
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .playerGestures(settings.gestures, locked, gestureCallbacks),
                    )

                    val message = state.engineError
                        ?: state.openFailure?.message()
                        ?: state.playback.error
                    // Until the first frame: opening and decoder start-up can each take seconds.
                    val loading = !state.playback.playbackStarted || state.playback.buffering
                    if (loading && message == null && state.resumePrompt == null) {
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    }

                    if (message != null) {
                        Text(
                            text = stringResource(R.string.player_error, message),
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        )
                    }

                    if (settings.screen.showStats) {
                        // In the corner, moving below the top bar while the controls show.
                        val top = if (controlsVisible && !locked) 80.dp else 8.dp
                        StatsOverlay(state.playback, Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = top))
                    }

                    StepSeekIndicator(stepBurst, Modifier.fillMaxSize())

                    val readout = if (speedBoostActive) GestureFeedback.Speed(state.playback.speed) else feedback
                    // Above the center controls so a gesture readout never hides the play button.
                    readout?.let {
                        Column(
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = 88.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // The swipe-seek readout shows the frame it would land on, like the seek bar.
                            if (it is GestureFeedback.Seek) seekPreview?.let { frame -> SeekPreviewImage(frame) }
                            FeedbackPill(it, state.playback.duration, Modifier)
                        }
                    }

                    state.notice?.let { notice ->
                        Text(
                            text = stringResource(
                                when (notice) {
                                    PlayerNotice.SOFTWARE_FALLBACK -> R.string.player_software_fallback
                                    PlayerNotice.DECODER_FALLBACK -> R.string.player_decoder_fallback
                                },
                            ),
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 120.dp, start = 24.dp, end = 24.dp)
                                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }

                    AnimatedVisibility(
                        visible = controlsVisible,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        // Moving through the controls counts as using them.
                        modifier = Modifier.onPreviewKeyEvent {
                            onControlUsed()
                            false
                        },
                    ) {
                        if (locked) {
                            LockedOverlay(onUnlock = { locked = false })
                        } else {
                            PlayerControls(
                                state = state,
                                config = ControlsConfig(
                                    seekBackSeconds = settings.playback.seekBackSeconds,
                                    seekForwardSeconds = settings.playback.seekForwardSeconds,
                                    showClock = settings.screen.showClock,
                                    showDecoder = settings.screen.showStats,
                                    hasPrevious = state.hasPrevious,
                                    hasNext = state.hasNext,
                                    canPictureInPicture = canPictureInPicture,
                                ),
                                actions = controlActions,
                                preview = seekPreview,
                                // Again when a panel closes, since focus went down with it.
                                focusPlay = controlsFocused && sheet == PlayerSheet.NONE,
                            )
                        }
                    }

                    PlayerSheets(sheet, state, settings, viewModel, onDismiss = { sheet = PlayerSheet.NONE })

                    state.resumePrompt?.let { position ->
                        ResumePrompt(position, onAnswer = viewModel::answerResume)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerSheets(
    sheet: PlayerSheet,
    state: PlayerUiState,
    settings: AppSettings,
    viewModel: PlayerViewModel,
    onDismiss: () -> Unit,
) {
    val aiSubtitles by viewModel.aiSubtitles.collectAsStateWithLifecycle()
    val subtitleActions = remember(viewModel) {
        object : SubtitlePanelActions {
            override fun onSelectTrack(id: Long?) = viewModel.selectTrack(TrackKind.SUBTITLE, id)
            override fun onVisibleChange(visible: Boolean) = viewModel.setSubtitlesVisible(visible)
            override fun onDelayChange(seconds: Double) = viewModel.setSubtitleDelay(seconds)
            override fun onSizeChange(percent: Int, save: Boolean) {
                viewModel.setSubtitleSize(percent, save)
            }
            override fun onPositionChange(position: Int, save: Boolean) {
                viewModel.setSubtitlePosition(position, save)
            }
            override fun onStartAiSubtitles() = viewModel.startAiSubtitles()
            override fun onCancelAiSubtitles() = viewModel.cancelAiSubtitles()
            override fun onDownloadAiModel() = viewModel.downloadWhisperModel()
        }
    }
    when (sheet) {
        PlayerSheet.SUBTITLES -> SubtitleSheet(
            tracks = state.playback.tracks,
            visible = state.subtitlesVisible,
            delay = state.playback.subtitleDelay,
            sizePercent = settings.subtitle.sizePercent,
            position = settings.subtitle.position,
            ai = aiSubtitles,
            actions = subtitleActions,
            onDismiss = onDismiss,
        )
        PlayerSheet.AUDIO -> AudioSheet(
            tracks = state.playback.tracks,
            boostPercent = settings.audio.boostPercent,
            delay = state.audioDelay,
            onSelectTrack = { viewModel.selectTrack(TrackKind.AUDIO, it) },
            onBoostChange = { percent, save -> if (save) viewModel.setAudioBoost(percent) },
            onDelayChange = viewModel::setAudioDelay,
            onDismiss = onDismiss,
        )
        PlayerSheet.VIDEO -> VideoSheet(
            aspect = state.aspect,
            scale = state.scale,
            hardwareDecoding = state.hardwareDecoding,
            onAspectChange = viewModel::setAspect,
            onScaleChange = viewModel::setScale,
            onHardwareDecodingChange = viewModel::setHardwareDecoding,
            onDismiss = onDismiss,
        )
        PlayerSheet.QUEUE -> QueueSheet(
            queue = state.queue,
            currentPath = state.currentPath,
            repeat = settings.playback.repeat,
            shuffle = settings.playback.shuffle,
            onRepeatChange = viewModel::setRepeat,
            onShuffleChange = viewModel::setShuffle,
            onPlay = {
                viewModel.play(it)
                onDismiss()
            },
            onDismiss = onDismiss,
        )
        PlayerSheet.SETTINGS -> PlayerSettingsSheet(settings, viewModel::updateSettings, onDismiss)
        PlayerSheet.NONE -> Unit
    }
}

/** Asks whether to continue from [position]; drawn in-window so the system bars stay hidden. */
@Composable
private fun ResumePrompt(position: Double, onAnswer: (Boolean) -> Unit) {
    BackHandler { onAnswer(false) }
    // Ready for the remote's OK, which continues.
    val continueFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { continueFocus.requestFocus() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .blockTouches(),
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.widthIn(max = 360.dp).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(stringResource(R.string.player_resume_question, formatTime(position)), style = MaterialTheme.typography.titleMedium)
                Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onAnswer(false) }) { Text(stringResource(R.string.player_resume_restart)) }
                    Button(onClick = { onAnswer(true) }, modifier = Modifier.focusRequester(continueFocus)) {
                        Text(stringResource(R.string.player_resume_continue))
                    }
                }
            }
        }
    }
}

/**
 * Sizes the surface for [VideoPath.DIRECT], where the decoder stretches the
 * picture over whatever surface it gets: [aspect] (width / height, null while
 * unknown) fitted or cropped to the space as mpv would, then [zoom]ed, centred.
 */
private fun Modifier.directVideoSize(aspect: Float?, scale: ScaleMode, zoom: Float): Modifier = layout { measurable, constraints ->
    val width = constraints.maxWidth
    val height = constraints.maxHeight
    val (videoWidth, videoHeight) = when {
        aspect == null || scale == ScaleMode.STRETCH -> width.toFloat() to height.toFloat()
        // Fit to the height when the space is wider than the video; cropping does the opposite.
        (width.toFloat() / height > aspect) == (scale == ScaleMode.FIT) -> height * aspect to height.toFloat()
        else -> width.toFloat() to width / aspect
    }
    val placeable = measurable.measure(Constraints.fixed((videoWidth * zoom).roundToInt(), (videoHeight * zoom).roundToInt()))
    layout(width, height) { placeable.place((width - placeable.width) / 2, (height - placeable.height) / 2) }
}

/** A [SurfaceView] whose lifecycle is forwarded to the player. */
@Composable
private fun VideoSurface(viewModel: PlayerViewModel, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(
                    object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) = viewModel.onSurfaceCreated(holder.surface)

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
                            viewModel.onSurfaceChanged(width, height)

                        override fun surfaceDestroyed(holder: SurfaceHolder) = viewModel.onSurfaceDestroyed(holder.surface)
                    },
                )
            }
        },
    )
}

@Composable
private fun FeedbackPill(feedback: GestureFeedback, duration: Double, modifier: Modifier) {
    val text = when (feedback) {
        is GestureFeedback.Seek -> {
            val sign = if (feedback.delta >= 0) "+" else "-"
            "${formatTime(feedback.target)} / ${formatTime(duration)}\n$sign${formatTime(abs(feedback.delta))}"
        }
        is GestureFeedback.Level -> {
            val label = stringResource(
                if (feedback.action == VerticalSwipeAction.BRIGHTNESS) R.string.player_brightness else R.string.player_volume,
            )
            "$label ${(feedback.fraction * 100).roundToInt()}%"
        }
        is GestureFeedback.SubtitleDelay -> stringResource(R.string.player_subtitle_delay, feedback.seconds)
        is GestureFeedback.SubtitleSize -> stringResource(R.string.player_subtitle_size_value, feedback.percent)
        is GestureFeedback.SubtitlePosition -> stringResource(R.string.player_subtitle_position_value, feedback.position)
        is GestureFeedback.Zoom -> stringResource(R.string.player_zoom, (feedback.scale * 100).roundToInt())
        is GestureFeedback.Speed -> stringResource(R.string.player_speed_value, feedback.speed)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = Color.White,
        textAlign = TextAlign.Center,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

private const val CONTROLS_TIMEOUT_MS = 3_500L
private const val FEEDBACK_TIMEOUT_MS = 800L
private const val NOTICE_TIMEOUT_MS = 5_000L

/** How long after the last of several seek taps the combined seek runs. */
private const val SEEK_COMBINE_WINDOW_MS = 400L

/** One brightness key press, as a fraction of the full range. */
private const val BRIGHTNESS_KEY_STEP = 1f / 16

private val SEEK_BAR_CLEARANCE = 112.dp
private const val MIN_SEEK_RANGE_SECONDS = 60.0
private const val MAX_SEEK_RANGE_SECONDS = 300.0
private const val SUBTITLE_DELAY_RANGE_SECONDS = 10.0

private fun IntRange.toFloatRange() = first.toFloat()..last.toFloat()

/** Approximate height of one subtitle line at 100% size, as a fraction of the screen height. */
private const val SUBTITLE_LINE_FRACTION = 0.08f

/** Extra touch area around the subtitle, as a fraction of the screen height. */
private const val SUBTITLE_TOUCH_SLACK = 0.04f
