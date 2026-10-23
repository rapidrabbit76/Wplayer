package dev.wplayer.app.ui.settings

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wplayer.app.data.PlaybackHistoryRepository
import dev.wplayer.app.data.SearchIndex
import dev.wplayer.app.data.SettingsRepository
import dev.wplayer.app.data.SubtitleFontStore
import dev.wplayer.app.data.ThumbnailRepository
import dev.wplayer.app.data.model.AppSettings
import dev.wplayer.app.data.model.SubtitleFont
import dev.wplayer.app.data.model.WhisperModel
import dev.wplayer.app.data.transcription.ModelStatus
import dev.wplayer.app.data.transcription.WhisperModelStore
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val history: PlaybackHistoryRepository,
    private val thumbnails: ThumbnailRepository,
    private val searchIndex: SearchIndex,
    private val whisperModels: WhisperModelStore,
    private val subtitleFontStore: SubtitleFontStore,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppSettings(),
    )

    /** Download state of the whisper model chosen for AI subtitles. */
    val whisperModelStatus: StateFlow<ModelStatus> = settingsRepository.settings
        .map { it.transcription.model }
        .distinctUntilChanged()
        .flatMapLatest(whisperModels::status)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ModelStatus.NotInstalled)

    private val subtitleFontFailed = MutableStateFlow(false)

    val subtitleFonts: StateFlow<SubtitleFontsState> = combine(subtitleFontStore.fonts, subtitleFontFailed, ::SubtitleFontsState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SubtitleFontsState())

    /** Adds a font file picked by the user and makes it the subtitle font. */
    fun addSubtitleFont(uri: Uri) {
        viewModelScope.launch {
            subtitleFontFailed.value = try {
                subtitleFontStore.add(uri)
                false
            } catch (e: IOException) {
                Log.w(TAG, "cannot add subtitle font $uri", e)
                true
            }
        }
    }

    fun deleteSubtitleFont(font: SubtitleFont) {
        viewModelScope.launch { subtitleFontStore.delete(font) }
    }

    fun dismissSubtitleFontFailure() {
        subtitleFontFailed.value = false
    }

    fun downloadWhisperModel(model: WhisperModel) = whisperModels.download(model)

    fun cancelWhisperModel(model: WhisperModel) = whisperModels.cancelDownload(model)

    fun deleteWhisperModel(model: WhisperModel) {
        viewModelScope.launch { whisperModels.delete(model) }
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsRepository.update(transform) }
    }

    /** Forgets every resume position and the recently played list. */
    fun clearHistory() {
        viewModelScope.launch { history.clearAll() }
    }

    /** Deletes stored thumbnails; they are rendered again when next shown. */
    fun clearThumbnails() {
        viewModelScope.launch { thumbnails.clear() }
    }

    /** Forgets every indexed file name; searches list folders again to rebuild it. */
    fun clearSearchIndex() {
        viewModelScope.launch { searchIndex.clear() }
    }
}

private const val TAG = "SettingsViewModel"
