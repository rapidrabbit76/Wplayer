package dev.wplayer.app.ui.sources

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wplayer.app.data.PlaybackHistoryRepository
import dev.wplayer.app.data.SettingsRepository
import dev.wplayer.app.data.SourceRepository
import dev.wplayer.app.data.ThumbnailRepository
import dev.wplayer.app.data.model.MediaRef
import dev.wplayer.app.data.model.Source
import dev.wplayer.app.data.model.WatchProgress
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A recently played file that was not finished. */
data class ContinueWatching(val ref: MediaRef, val progress: WatchProgress)

@HiltViewModel
class SourcesViewModel @Inject constructor(
    sourceRepository: SourceRepository,
    private val history: PlaybackHistoryRepository,
    private val thumbnails: ThumbnailRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    val sources: StateFlow<List<Source>> = sourceRepository.sources.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = listOf(sourceRepository.localStorage),
    )

    /** Newest first; finished files have no saved position and drop out. Empty when hidden in settings. */
    val continueWatching: StateFlow<List<ContinueWatching>> = combine(
        history.recent(),
        history.progress,
        settings.settings.map { it.browser.showContinueWatching }.distinctUntilChanged(),
    ) { recents, progress, shown ->
        if (!shown) return@combine emptyList()
        recents
            .mapNotNull { recent ->
                progress[recent.ref.mediaKey]
                    ?.takeIf { it.isResumable }
                    ?.let { ContinueWatching(recent.ref, it) }
            }
            .take(CONTINUE_LIMIT)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun thumbnail(ref: MediaRef): Bitmap? = thumbnails.thumbnail(ref)

    /** Drops [ref] from continue watching: its saved position and its recent entry. */
    fun forget(ref: MediaRef) {
        viewModelScope.launch { history.forget(ref) }
    }

    fun clearHistory() {
        viewModelScope.launch { history.clearAll() }
    }

    fun hideContinueWatching() {
        viewModelScope.launch { settings.update { it.copy(browser = it.browser.copy(showContinueWatching = false)) } }
    }

    private companion object {
        const val CONTINUE_LIMIT = 12
    }
}
