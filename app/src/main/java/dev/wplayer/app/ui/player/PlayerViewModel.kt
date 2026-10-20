package dev.wplayer.app.ui.player

import android.graphics.Bitmap
import android.util.Log
import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wplayer.app.data.BrowserRepository
import dev.wplayer.app.data.CacheStorage
import dev.wplayer.app.data.ExternalUriResolver
import dev.wplayer.app.data.NetworkMonitor
import dev.wplayer.app.data.PlaybackHistoryRepository
import dev.wplayer.app.data.PlaylistRepository
import dev.wplayer.app.data.SettingsRepository
import dev.wplayer.app.data.SourceRepository
import dev.wplayer.app.data.model.AppSettings
import androidx.media3.common.C
import dev.wplayer.app.data.model.MediaButtonAction
import dev.wplayer.app.data.model.MediaRef
import dev.wplayer.app.data.model.RepeatMode
import dev.wplayer.app.data.model.ResumeMode
import dev.wplayer.app.data.model.SUBTITLE_POSITION_RANGE
import dev.wplayer.app.data.model.SUBTITLE_SIZE_RANGE
import dev.wplayer.app.data.model.SeekPrecision
import dev.wplayer.app.data.transcription.TranscriptionRepository
import dev.wplayer.app.data.transcription.WhisperModelStore
import dev.wplayer.app.di.ApplicationScope
import dev.wplayer.app.ui.common.UserError
import dev.wplayer.app.ui.common.catchingFailures
import dev.wplayer.core.ffi.CoreException
import dev.wplayer.core.ffi.TrackKind
import dev.wplayer.core.player.MpvEngine
import dev.wplayer.core.player.PlaybackState
import javax.inject.Inject
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Something the player wants the user to know, shown briefly over the video. */
enum class PlayerNotice { SOFTWARE_FALLBACK }

data class PlayerUiState(
    val title: String = "",
    val playback: PlaybackState = PlaybackState(),
    /** Set when libmpv could not start at all (e.g. native libraries missing). */
    val engineError: String? = null,
    /** Set when the requested media could not be opened. */
    val openFailure: UserError? = null,
    val zoom: Float = 1f,
    /** Changes whenever the engine is replaced; the video surface is recreated with it. */
    val engineGeneration: Int = 0,
    val notice: PlayerNotice? = null,
    /** Files that next/previous move through; empty for media from other apps. */
    val queue: List<MediaRef> = emptyList(),
    val currentPath: String? = null,
    /** Saved position the user is being asked to resume from. */
    val resumePrompt: Double? = null,
    val aspect: AspectRatio = AspectRatio.DEFAULT,
    val scale: ScaleMode = ScaleMode.FIT,
    val audioDelay: Double = 0.0,
    val hardwareDecoding: Boolean = true,
    val subtitlesVisible: Boolean = true,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    /** The last file ended with nothing to play next: the player should close. */
    val finished: Boolean = false,
)

/** A preview frame for the seek bar at [seconds]. */
data class SeekPreviewFrame(val seconds: Double, val image: Bitmap)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val engineFactory: MpvEngineFactory,
    private val browserRepository: BrowserRepository,
    private val playlistRepository: PlaylistRepository,
    private val externalUris: ExternalUriResolver,
    private val history: PlaybackHistoryRepository,
    private val settingsRepository: SettingsRepository,
    private val network: NetworkMonitor,
    private val cacheStorage: CacheStorage,
    private val mpvEnvironment: MpvEnvironment,
    private val transcriptions: TranscriptionRepository,
    private val whisperModels: WhisperModelStore,
    private val sessions: MediaSessionHolder,
    private val audioFocus: AudioFocusController,
    @ApplicationScope private val applicationScope: CoroutineScope,
) : ViewModel() {
    val settings: StateFlow<AppSettings> =
        settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /** The engine in use. Replaced (never mutated) when hardware decoding has to be abandoned. */
    private class EngineSlot(val engine: MpvEngine, val generation: Int, val hardwareDecoding: Boolean)

    /** Everything about the current viewing that is not reported by mpv. */
    private data class Session(
        val request: PlaybackRequest? = null,
        val openFailure: UserError? = null,
        val zoom: Float = 1f,
        val notice: PlayerNotice? = null,
        val queue: List<MediaRef> = emptyList(),
        val resumePrompt: Double? = null,
        val aspect: AspectRatio = AspectRatio.DEFAULT,
        val scale: ScaleMode = ScaleMode.FIT,
        val audioDelay: Double = 0.0,
        /** Decoder chosen in the player for this session; null follows the engine. */
        val hardwareDecoding: Boolean? = null,
        val subtitlesVisible: Boolean? = null,
        val finished: Boolean = false,
    )

    private val engineError = MutableStateFlow<String?>(null)
    private val slot = MutableStateFlow(createSlot(settings.value.playback.hardwareDecoding, generation = 0))
    private val engine: MpvEngine? get() = slot.value?.engine
    private val session = MutableStateFlow(Session())

    private val playback = slot.flatMapLatest { it?.engine?.state ?: flowOf(PlaybackState()) }

    val uiState: StateFlow<PlayerUiState> = combine(session, playback, slot, engineError, settings) { session, playback, slot, engineError, settings ->
        val currentPath = (session.request as? PlaybackRequest.FromSource)?.path
        val repeat = settings.playback.repeat
        PlayerUiState(
            title = session.request?.title.orEmpty(),
            playback = playback,
            engineError = engineError,
            openFailure = session.openFailure,
            zoom = session.zoom,
            engineGeneration = slot?.generation ?: 0,
            notice = session.notice,
            queue = session.queue,
            currentPath = currentPath,
            resumePrompt = session.resumePrompt,
            aspect = session.aspect,
            scale = session.scale,
            audioDelay = session.audioDelay,
            hardwareDecoding = session.hardwareDecoding ?: (slot?.hardwareDecoding ?: false),
            subtitlesVisible = session.subtitlesVisible ?: settings.subtitle.showByDefault,
            finished = session.finished,
            hasPrevious = currentPath != null && previousInQueue(session.queue, currentPath, repeat) != null,
            hasNext = currentPath != null && if (settings.playback.shuffle) {
                session.queue.size > 1
            } else {
                nextInQueue(session.queue, currentPath, repeat, shuffle = false) != null
            },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PlayerUiState(engineError = engineError.value))

    private var loadJob: Job? = null
    private var queueJob: Job? = null
    private var queueOrigin: Any? = null
    private var speedBeforeBoost: Double? = null
    private var resumeAnswer: CompletableDeferred<Boolean>? = null

    /** Fraction of the screen the subtitles are raised to clear the controls. */
    private var subtitleLift = 0f

    /** The surface currently shown, re-attached when the engine is replaced. */
    private var surface: Surface? = null

    /** The engine [surface] is attached to; null while there is no surface. */
    private val surfaceOwner = MutableStateFlow<MpvEngine?>(null)

    /** Playing sound only, with the player screen not visible. */
    private val inBackground = MutableStateFlow(false)

    private val sessionPlayer = MpvSessionPlayer(SessionActions())

    /** Where seek previews come from for the current file; null when they are not offered. */
    @Volatile
    private var previewUri: String? = null

    /** Preview decoding blocks; one at a time, always for the latest requested position. */
    private val previewDispatcher = Dispatchers.IO.limitedParallelism(1)
    private var previewSession: SeekPreviewSession? = null // Touched only on previewDispatcher.
    private val previewTarget = MutableStateFlow<Double?>(null)
    private val _seekPreview = MutableStateFlow<SeekPreviewFrame?>(null)

    /** Shown above the seek bar (or the seek gesture readout) while seeking. */
    val seekPreview: StateFlow<SeekPreviewFrame?> = _seekPreview.asStateFlow()

    /**
     * AI subtitles for the file being played. While they are being made, the
     * player reloads them as lines arrive.
     */
    val aiSubtitles: StateFlow<AiSubtitleStatus> = session.map { it.request }.distinctUntilChanged().flatMapLatest { request ->
        if (request !is PlaybackRequest.FromSource) return@flatMapLatest flowOf(AiSubtitleStatus.Unavailable)
        val model = settings.map { it.transcription.model }.distinctUntilChanged()
        combine(
            transcriptions.state(request.mediaKey),
            model.flatMapLatest { m -> whisperModels.status(m).map { m to it } },
            playback.map { it.duration }.distinctUntilChanged(),
        ) { transcription, (m, modelStatus), duration ->
            aiSubtitleStatus(transcription, m, modelStatus, transcriptions.subtitleFile(request.mediaKey) != null, duration)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AiSubtitleStatus.Unavailable)

    /** Set when the user starts AI subtitles here: the first lines then replace the current subtitles. */
    private var selectAiSubtitlesWhenShown = false

    init {
        sessions.attach(sessionPlayer)
        audioFocus.setListener(FocusActions())
        viewModelScope.launch {
            uiState.collect { sessionPlayer.update(it.toSessionSnapshot(settings.value)) }
        }
        // Hold audio focus while playing, so calls and other apps pause us (and we them).
        viewModelScope.launch {
            playback.map { it.fileLoaded && !it.paused }.distinctUntilChanged().collect { playing ->
                if (!playing) {
                    audioFocus.release()
                } else if (!audioFocus.acquire()) {
                    engine?.setPaused(true)
                }
            }
        }
        viewModelScope.launch {
            settings.map { it.playback.hardwareDecoding }.distinctUntilChanged().drop(1).collect { enabled ->
                session.update { it.copy(hardwareDecoding = null) }
                engine?.setProperty("hwdec", MpvEngineFactory.hwdecValue(enabled))
            }
        }
        // Settings changed while playing apply at once; a new engine starts with them.
        viewModelScope.launch {
            var applied = mpvEnvironment.options(settings.value)
            settings.map(mpvEnvironment::options).distinctUntilChanged().collect { options ->
                options.filter { (name, value) -> applied[name] != value }.forEach { (name, value) -> engine?.setProperty(name, value) }
                applied = options
            }
        }
        viewModelScope.launch {
            settings.map { it.subtitle.position }.distinctUntilChanged().collect { applySubtitlePosition() }
        }
        // Stats change many times a second; mpv reports them only while the overlay is shown.
        viewModelScope.launch {
            combine(settings.map { it.screen.showStats }.distinctUntilChanged(), slot) { show, slot -> show to slot }
                .collect { (show, slot) -> slot?.engine?.observeStats(show) }
        }
        // New lines of AI subtitles being made for this file: reload them.
        viewModelScope.launch {
            aiSubtitles.map { (it as? AiSubtitleStatus.Running)?.lines }.distinctUntilChanged().filterNotNull().filter { it > 0 }.collect {
                val request = session.value.request as? PlaybackRequest.FromSource ?: return@collect
                val file = transcriptions.subtitleFile(request.mediaKey) ?: return@collect
                engine?.showGrowingSubtitle(file.path, AI_SUBTITLE_TITLE, selectAiSubtitlesWhenShown)
                selectAiSubtitlesWhenShown = false
            }
        }
        viewModelScope.launch {
            playback.map { it.fileLoaded && it.endReached }.distinctUntilChanged().filter { it }.collect { onEnded() }
        }
        // StateFlow is conflated: while one frame decodes, newer positions replace older ones.
        viewModelScope.launch(previewDispatcher) {
            previewTarget.filterNotNull().collect { seconds -> renderPreview(seconds) }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(POSITION_SAVE_INTERVAL_MS)
                if (!uiState.value.playback.paused) savePosition()
            }
        }
    }

    private fun createSlot(hardwareDecoding: Boolean, generation: Int): EngineSlot? =
        runCatching { EngineSlot(engineFactory.create(hardwareDecoding, settings.value), generation, hardwareDecoding) }
            .onFailure {
                Log.e(TAG, "libmpv failed to start", it)
                engineError.value = it.message ?: it.javaClass.simpleName
            }
            .getOrNull()

    /** Opens [newRequest] unless it is already playing (e.g. after re-delivery of the intent). */
    fun open(newRequest: PlaybackRequest) {
        if (newRequest.mediaKey == session.value.request?.mediaKey) return
        val current = slot.value ?: return
        savePosition()
        // Stop now so the old file's position can never be saved under the new key.
        current.engine.stop()
        // mpv's pause is player-wide, and reaching the end of a file sets it: start the new one playing.
        current.engine.setPaused(false)
        resumeAnswer?.cancel()
        session.update {
            it.copy(request = newRequest, openFailure = null, notice = null, resumePrompt = null, audioDelay = 0.0, finished = false)
        }
        resetZoom()
        engine?.setProperty("audio-delay", "0")
        val playbackSettings = settings.value.playback
        setSpeed(if (playbackSettings.keepSpeed) playbackSettings.lastSpeed else 1.0, remember = false)
        if (newRequest is PlaybackRequest.FromSource) {
            loadQueue(newRequest)
            applicationScope.launch {
                history.recordPlayed(MediaRef(newRequest.sourceKey, newRequest.path, newRequest.title, isDirectory = false))
            }
        } else {
            session.update { it.copy(queue = emptyList()) }
        }
        load(current, newRequest)
    }

    private fun load(target: EngineSlot, request: PlaybackRequest, start: Double? = null) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            catchingFailures {
                if (isBlockedOnCellular(request)) {
                    session.update { it.copy(openFailure = UserError(UserError.Reason.CELLULAR_BLOCKED)) }
                    return@catchingFailures
                }
                val from = start ?: startPosition(request)
                val uri = resolveUri(request)
                previewUri = uri.takeIf { request.allowsSeekPreview() }
                // Local files are already on flash: copying them into the disk cache would only wear it.
                val cache = if (request.isLocal) "no" else mpvEnvironment.options(settings.value).getValue("cache")
                target.engine.setProperty("cache", cache)
                // Loading before the video output exists makes mpv start a decoder, then
                // tear it down and rebuild it once the surface arrives. In the background
                // (sound only) there is no surface to wait for.
                combine(surfaceOwner, inBackground) { owner, background -> owner === target.engine || background }.first { it }
                target.engine.load(uri, from)
                if (!awaitStartOrFallBack(target, request, from)) return@catchingFailures
                if (request is PlaybackRequest.FromSource) {
                    retryAfterReconnectIfNeeded(target.engine, request, from)
                    addSiblingSubtitles(target.engine, request)
                    showAiSubtitles(target.engine, request, select = false)
                }
            }.onFailure { fail(request, it) }
        }
    }

    private fun isBlockedOnCellular(request: PlaybackRequest): Boolean {
        if (settings.value.network.allowCellular) return false
        return !request.isLocal && network.isOnCellular()
    }

    /**
     * Previews open the file a second time: not possible for a content URI's
     * single file descriptor, and not wanted over mobile data unless allowed.
     */
    private fun PlaybackRequest.allowsSeekPreview(): Boolean {
        if (this is PlaybackRequest.FromUri && uri.scheme != "http" && uri.scheme != "https") return false
        return isLocal || settings.value.network.thumbnailsOnCellular || !network.isOnCellular()
    }

    /** Media on this device: device storage, or a file or content URI from another app. */
    private val PlaybackRequest.isLocal: Boolean
        get() = when (this) {
            is PlaybackRequest.FromSource -> sourceKey == SourceRepository.LOCAL_KEY
            is PlaybackRequest.FromUri -> uri.scheme != "http" && uri.scheme != "https"
        }

    /** Where to start [request], asking the user first when the settings say so. */
    private suspend fun startPosition(request: PlaybackRequest): Double? {
        val saved = history.resumePosition(request.mediaKey) ?: return null
        return when (settings.value.playback.resume) {
            ResumeMode.ALWAYS -> saved
            ResumeMode.NEVER -> null
            ResumeMode.ASK -> {
                val answer = CompletableDeferred<Boolean>()
                resumeAnswer = answer
                session.update { it.copy(resumePrompt = saved) }
                try {
                    if (answer.await()) saved else null
                } finally {
                    session.update { it.copy(resumePrompt = null) }
                    resumeAnswer = null
                }
            }
        }
    }

    /** The user's answer to [PlayerUiState.resumePrompt]. */
    fun answerResume(resume: Boolean) {
        resumeAnswer?.complete(resume)
    }

    private suspend fun resolveUri(request: PlaybackRequest): String = when (request) {
        is PlaybackRequest.FromSource -> browserRepository.playbackUri(request.sourceKey, request.path)
        is PlaybackRequest.FromUri -> externalUris.toPlayerUri(request.uri)
    }

    /**
     * Some MediaCodec decoders never produce a frame and hold mpv's core
     * forever, so the engine cannot be recovered in place. If playback has not
     * started in time, replace it with a software-decoding engine and reload.
     * Returns false when the fallback took over this request.
     */
    private suspend fun awaitStartOrFallBack(target: EngineSlot, request: PlaybackRequest, start: Double?): Boolean {
        if (!target.hardwareDecoding) return true
        val alive = withTimeoutOrNull(DECODER_STALL_TIMEOUT_MS) {
            target.engine.state.first { it.playbackStarted || it.buffering || it.error != null }
        }
        if (alive != null) return true

        Log.w(TAG, "hardware decoding stalled for ${request.mediaKey}; switching to software")
        val replacement = createSlot(hardwareDecoding = false, generation = target.generation + 1) ?: return false
        slot.value = replacement
        session.update { it.copy(notice = PlayerNotice.SOFTWARE_FALLBACK, hardwareDecoding = null) }
        target.engine.close() // Abandons the stuck core on its own thread.
        applySessionTo(replacement.engine)
        load(replacement, request, start)
        return false
    }

    /** Carries choices made in the player over to a replacement engine. */
    private fun applySessionTo(engine: MpvEngine) {
        val current = session.value
        engine.setProperty("video-aspect-override", current.aspect.mpvValue)
        applyScale(engine, current.scale)
        current.subtitlesVisible?.let { engine.setProperty("sub-visibility", if (it) "yes" else "no") }
        engine.setSpeed(uiState.value.playback.speed)
        applySubtitlePosition()
    }

    /**
     * A server session that died while the device slept fails inside libmpv's
     * stream open. Reconnect the source once and load again.
     */
    private suspend fun retryAfterReconnectIfNeeded(engine: MpvEngine, request: PlaybackRequest.FromSource, start: Double?) {
        val outcome = engine.state.first { it.fileLoaded || it.error != null }
        if (outcome.fileLoaded) return
        Log.i(TAG, "open failed (${outcome.error}); reconnecting ${request.sourceKey}")
        browserRepository.reconnect(request.sourceKey)
        engine.load(resolveUri(request), start)
    }

    private fun fail(request: PlaybackRequest, error: Throwable) {
        Log.w(TAG, "cannot open ${request.mediaKey}", error)
        session.update { it.copy(openFailure = UserError.from(error)) }
    }

    /** The best-matching subtitle becomes active; the rest are only listed. */
    private suspend fun addSiblingSubtitles(engine: MpvEngine, request: PlaybackRequest.FromSource) {
        val subtitles = browserRepository.siblingSubtitles(request.sourceKey, request.path)
        if (subtitles.isEmpty()) return
        engine.state.first { it.fileLoaded }
        subtitles.forEachIndexed { index, subtitle ->
            engine.addSubtitle(browserRepository.subtitleUri(request.sourceKey, subtitle.path), select = index == 0)
        }
    }

    /** Adds the AI subtitles made earlier for [request], if any; the user picks them in the panel. */
    private suspend fun showAiSubtitles(engine: MpvEngine, request: PlaybackRequest.FromSource, select: Boolean) {
        val file = transcriptions.subtitleFile(request.mediaKey) ?: return
        engine.state.first { it.fileLoaded }
        engine.showGrowingSubtitle(file.path, AI_SUBTITLE_TITLE, select)
    }

    /** Makes AI subtitles from the current position on (then the part before it), showing them as they come. */
    fun startAiSubtitles() {
        val request = session.value.request as? PlaybackRequest.FromSource ?: return
        val playback = uiState.value.playback
        val audioTrack = playback.tracks.firstOrNull { it.kind == TrackKind.AUDIO && it.selected }?.id
        transcriptions.start(request.sourceKey, request.path, request.title, playback.position, playback.duration, audioTrack)
        selectAiSubtitlesWhenShown = true
    }

    fun cancelAiSubtitles() {
        val request = session.value.request as? PlaybackRequest.FromSource ?: return
        transcriptions.cancel(request.mediaKey)
    }

    fun downloadWhisperModel() {
        whisperModels.download(settings.value.transcription.model)
    }

    /** Loads the files around [request] unless they are already known. */
    private fun loadQueue(request: PlaybackRequest.FromSource) {
        val folder = request.path.substringBeforeLast('/', "").ifEmpty { "/" }
        val origin = when (val queue = request.queue) {
            PlayQueue.Folder -> request.sourceKey to folder
            is PlayQueue.Playlist -> queue
        }
        if (origin == queueOrigin) return
        queueOrigin = origin
        session.update { it.copy(queue = emptyList()) }
        queueJob?.cancel()
        queueJob = viewModelScope.launch {
            catchingFailures {
                when (val queue = request.queue) {
                    PlayQueue.Folder -> browserRepository.playableFiles(request.sourceKey, folder, settings.value.browser)
                    is PlayQueue.Playlist -> playlistRepository.playableItems(queue.playlistId)
                }
            }
                .onSuccess { files -> session.update { it.copy(queue = files) } }
                .onFailure {
                    Log.w(TAG, "cannot list the files around ${request.path}", it)
                    queueOrigin = null
                }
        }
    }

    /** Plays the next file, or closes the player when there is none. */
    private fun onEnded() {
        savePosition()
        val next = if (settings.value.playback.autoPlayNext) nextFile() else null
        if (next != null) play(next) else session.update { it.copy(finished = true) }
    }

    fun nextFile(): MediaRef? {
        val current = uiState.value.currentPath ?: return null
        val playback = settings.value.playback
        return nextInQueue(session.value.queue, current, playback.repeat, playback.shuffle)
    }

    fun previousFile(): MediaRef? {
        val current = uiState.value.currentPath ?: return null
        return previousInQueue(session.value.queue, current, settings.value.playback.repeat)
    }

    /**
     * Back to the start of the file, or to the previous file within the first
     * [RESTART_THRESHOLD_SECONDS], like the previous button of music players.
     */
    fun restartOrPlayPrevious() {
        val previous = previousFile().takeIf { uiState.value.playback.position < RESTART_THRESHOLD_SECONDS }
        if (previous != null) play(previous) else seekTo(0.0)
    }

    /** Plays [ref] from the current queue. */
    fun play(ref: MediaRef) {
        val request = session.value.request as? PlaybackRequest.FromSource ?: return
        open(PlaybackRequest.FromSource(ref.sourceKey, ref.path, ref.name, request.queue))
    }

    fun onSurfaceCreated(surface: Surface) {
        this.surface = surface
        val current = engine
        current?.attachSurface(surface)
        surfaceOwner.value = current
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        engine?.setSurfaceSize(width, height)
    }

    /** Ignores a stale callback from a surface that was already replaced. */
    fun onSurfaceDestroyed(surface: Surface) {
        if (surface != this.surface) return
        this.surface = null
        surfaceOwner.value = null
        engine?.detachSurface()
    }

    fun dismissNotice() {
        session.update { it.copy(notice = null) }
    }

    fun togglePause() {
        engine?.togglePause()
    }

    /** Pauses and records the position, e.g. when the player leaves the screen. */
    fun pause() {
        engine?.setPaused(true)
        savePosition()
    }

    /** Requests the preview frame at [seconds]; the latest request wins. */
    fun showSeekPreview(seconds: Double) {
        if (previewUri != null) previewTarget.value = seconds
    }

    fun hideSeekPreview() {
        previewTarget.value = null
        _seekPreview.value = null
    }

    private fun renderPreview(seconds: Double) {
        val uri = previewUri ?: return
        val session = previewSession?.takeIf { it.uri == uri } ?: run {
            previewSession?.close()
            SeekPreviewSession(uri, cacheStorage.seekPreviewDir).also { previewSession = it }
        }
        try {
            val frame = session.frameAt(seconds) ?: return
            // Dropped if the seek ended while it was decoding.
            if (previewTarget.value != null) _seekPreview.value = SeekPreviewFrame(seconds, frame)
        } catch (e: CoreException) {
            Log.i(TAG, "no seek preview: ${e.message}")
        }
    }

    /** The player screen is hidden but sound continues: stop decoding video until it returns. */
    fun enterBackground() {
        inBackground.value = true
        engine?.setProperty("vid", "no")
        savePosition()
    }

    fun enterForeground() {
        if (!inBackground.value) return
        inBackground.value = false
        engine?.setProperty("vid", "auto")
    }

    private val precise: Boolean get() = settings.value.playback.seekPrecision == SeekPrecision.ACCURATE

    fun seekBy(seconds: Double) {
        engine?.seekBy(seconds, precise)
    }

    fun seekTo(seconds: Double) {
        engine?.seekTo(seconds, precise)
    }

    /** Sets the playback speed; with "keep speed" on it also becomes the default for the next file. */
    fun setSpeed(speed: Double, remember: Boolean = true) {
        engine?.setSpeed(speed)
        if (remember && settings.value.playback.keepSpeed) {
            updateSettings { it.copy(playback = it.playback.copy(lastSpeed = speed)) }
        }
    }

    /** Temporarily plays at [speed] while a long press is held. */
    fun beginSpeedBoost(speed: Double) {
        if (speedBeforeBoost != null) return
        speedBeforeBoost = uiState.value.playback.speed
        engine?.setSpeed(speed)
    }

    fun endSpeedBoost() {
        speedBeforeBoost?.let { engine?.setSpeed(it) }
        speedBeforeBoost = null
    }

    fun setSubtitleDelay(seconds: Double) {
        engine?.setSubtitleDelay(seconds)
    }

    fun stepSubtitle(lines: Int) {
        engine?.stepSubtitle(lines)
    }

    fun setSubtitlesVisible(visible: Boolean) {
        session.update { it.copy(subtitlesVisible = visible) }
        engine?.setProperty("sub-visibility", if (visible) "yes" else "no")
    }

    /**
     * Shows subtitles at [percent] of the default size. With [save] the size
     * is kept for the next video; without it (during a pinch) it is only shown.
     */
    fun setSubtitleSize(percent: Int, save: Boolean = true): Int {
        val clamped = percent.coerceIn(SUBTITLE_SIZE_RANGE)
        engine?.setProperty("sub-scale", (clamped / 100.0).toString())
        if (save) updateSettings { it.copy(subtitle = it.subtitle.copy(sizePercent = clamped)) }
        return clamped
    }

    /**
     * Moves the subtitle's bottom edge to [position] percent of the screen
     * height (100 = bottom). [save] works as in [setSubtitleSize].
     */
    fun setSubtitlePosition(position: Int, save: Boolean = true): Int {
        val clamped = position.coerceIn(SUBTITLE_POSITION_RANGE)
        engine?.setProperty("sub-pos", clamped.toString())
        if (save) updateSettings { it.copy(subtitle = it.subtitle.copy(position = clamped)) }
        return clamped
    }

    /**
     * Raises subtitles by [fraction] of the screen height, e.g. above the seek
     * bar while controls are shown. 0 restores the normal position.
     */
    fun setSubtitleLift(fraction: Float) {
        subtitleLift = fraction.coerceIn(0f, 0.5f)
        applySubtitlePosition()
    }

    /** Where subtitles are drawn: the user's position, kept above the controls when they show. */
    fun effectiveSubtitlePosition(): Int =
        minOf(settings.value.subtitle.position, (100 - subtitleLift * 100).roundToInt())

    private fun applySubtitlePosition() {
        engine?.setProperty("sub-pos", effectiveSubtitlePosition().toString())
    }

    fun setAudioDelay(seconds: Double) {
        session.update { it.copy(audioDelay = seconds) }
        engine?.setProperty("audio-delay", seconds.toString())
    }

    /** Volume boost in percent (100 = normal); kept for the next video. */
    fun setAudioBoost(percent: Int) {
        val clamped = percent.coerceIn(100, MAX_VOLUME_PERCENT)
        updateSettings { it.copy(audio = it.audio.copy(boostPercent = clamped)) }
    }

    fun setRepeat(mode: RepeatMode) = updateSettings { it.copy(playback = it.playback.copy(repeat = mode)) }

    fun setShuffle(enabled: Boolean) = updateSettings { it.copy(playback = it.playback.copy(shuffle = enabled)) }

    /** The brightness to start with, when one was remembered. */
    suspend fun savedBrightness(): Float? =
        settingsRepository.settings.first().screen.takeIf { it.saveBrightness }?.savedBrightness

    /** Remembers the brightness set by gesture when the settings ask for it. */
    fun rememberBrightness(level: Float) {
        if (!settings.value.screen.saveBrightness) return
        updateSettings { it.copy(screen = it.screen.copy(savedBrightness = level)) }
    }

    /** Switches between the hardware and software decoder for this session. */
    fun setHardwareDecoding(enabled: Boolean) {
        session.update { it.copy(hardwareDecoding = enabled) }
        engine?.setProperty("hwdec", MpvEngineFactory.hwdecValue(enabled))
    }

    fun setAspect(aspect: AspectRatio) {
        session.update { it.copy(aspect = aspect) }
        engine?.setProperty("video-aspect-override", aspect.mpvValue)
    }

    fun setScale(scale: ScaleMode) {
        session.update { it.copy(scale = scale) }
        resetZoom()
        engine?.let { applyScale(it, scale) }
    }

    private fun applyScale(engine: MpvEngine, scale: ScaleMode) {
        engine.setProperty("keepaspect", if (scale == ScaleMode.STRETCH) "no" else "yes")
        engine.setProperty("panscan", if (scale == ScaleMode.CROP) "1.0" else "0.0")
    }

    fun selectTrack(kind: TrackKind, id: Long?) {
        engine?.selectTrack(kind, id)
    }

    /** Cycles A-B repeat: set A, then B, then clear. */
    fun cycleAbRepeat() {
        val playback = uiState.value.playback
        when {
            playback.abLoopStart == null -> engine?.setAbLoop(playback.position, null)
            playback.abLoopEnd == null -> engine?.setAbLoop(playback.abLoopStart, playback.position)
            else -> engine?.setAbLoop(null, null)
        }
    }

    /** Multiplies the video zoom (1.0 fits the video to the screen) and returns the new scale. */
    fun zoomBy(factor: Float): Float {
        val scale = (session.value.zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        session.update { it.copy(zoom = scale) }
        engine?.setProperty("video-zoom", log2(scale).toString())
        return scale
    }

    fun resetZoom() {
        zoomBy(1f / session.value.zoom)
    }

    override fun onCleared() {
        // The preview core may still be decoding; close it after that, off the main thread.
        applicationScope.launch(previewDispatcher) { previewSession?.close() }
        savePosition()
        sessions.release()
        audioFocus.release()
        engine?.close()
    }

    /** Commands from the notification, lock screen and headset buttons. */
    private inner class SessionActions : SessionControls {
        override fun play() = engine?.setPaused(false) ?: Unit
        override fun pause() = this@PlayerViewModel.pause()
        override fun seekTo(positionMs: Long) = this@PlayerViewModel.seekTo(positionMs / 1000.0)
        override fun next() = step(forward = true)
        override fun previous() = step(forward = false)
        override fun stop() = this@PlayerViewModel.pause()

        private fun step(forward: Boolean) {
            val playback = settings.value.playback
            when (playback.mediaButtons) {
                MediaButtonAction.FILES -> (if (forward) nextFile() else previousFile())?.let(::play)
                MediaButtonAction.SEEK -> seekBy(if (forward) playback.seekForwardSeconds.toDouble() else -playback.seekBackSeconds.toDouble())
            }
        }
    }

    private inner class FocusActions : AudioFocusController.Listener {
        override fun onPause() = engine?.setPaused(true) ?: Unit
        override fun onResume() = engine?.setPaused(false) ?: Unit

        /** Another app speaks briefly (navigation, notification): play quieter meanwhile. */
        override fun onDuck(ducked: Boolean) {
            val boost = settings.value.audio.boostPercent
            engine?.setProperty("volume", (if (ducked) boost * DUCK_VOLUME else boost.toDouble()).toString())
        }
    }

    private fun PlayerUiState.toSessionSnapshot(settings: AppSettings): SessionSnapshot {
        val seekButtons = settings.playback.mediaButtons == MediaButtonAction.SEEK
        return SessionSnapshot(
            title = title,
            loaded = playback.fileLoaded,
            playing = !playback.paused,
            buffering = playback.buffering,
            ended = playback.endReached,
            positionMs = (playback.position * 1000).toLong(),
            durationMs = if (playback.duration > 0) (playback.duration * 1000).toLong() else C.TIME_UNSET,
            speed = playback.speed.toFloat(),
            hasPrevious = seekButtons || hasPrevious,
            hasNext = seekButtons || hasNext,
            seekBackMs = settings.playback.seekBackSeconds * 1000L,
            seekForwardMs = settings.playback.seekForwardSeconds * 1000L,
        )
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        applicationScope.launch { settingsRepository.update(transform) }
    }

    private fun savePosition() {
        val key = session.value.request?.mediaKey ?: return
        val playback = uiState.value.playback
        if (!playback.fileLoaded) return
        applicationScope.launch { history.save(key, playback.position, playback.duration) }
    }

    private companion object {
        const val TAG = "PlayerViewModel"
        const val POSITION_SAVE_INTERVAL_MS = 15_000L
        const val DECODER_STALL_TIMEOUT_MS = 10_000L
        const val DUCK_VOLUME = 0.3
        const val RESTART_THRESHOLD_SECONDS = 30.0
        const val MIN_ZOOM = 0.5f
        const val MAX_ZOOM = 4f
    }
}
