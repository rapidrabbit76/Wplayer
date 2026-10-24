package dev.wplayer.app.ui.library

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wplayer.app.data.PlaybackHistoryRepository
import dev.wplayer.app.data.PlaylistRepository
import dev.wplayer.app.data.SettingsRepository
import dev.wplayer.app.data.SourceRepository
import dev.wplayer.app.data.ThumbnailRepository
import dev.wplayer.app.data.model.BrowserViewMode
import dev.wplayer.app.data.model.MediaRef
import dev.wplayer.app.data.model.PlaylistItem
import dev.wplayer.app.data.model.WatchProgress
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PlaylistUiState(
    /** Null while loading, and after the playlist was deleted. */
    val name: String? = null,
    val items: List<PlaylistItem> = emptyList(),
    /** Watch progress by item id. */
    val progress: Map<Long, WatchProgress> = emptyMap(),
    /** Source display names by source key, to tell same-named files apart. */
    val sourceNames: Map<String, String> = emptyMap(),
    val showThumbnails: Boolean = true,
    val viewMode: BrowserViewMode = BrowserViewMode.GRID,
)

@HiltViewModel(assistedFactory = PlaylistViewModel.Factory::class)
class PlaylistViewModel @AssistedInject constructor(
    @Assisted val playlistId: Long,
    private val playlists: PlaylistRepository,
    history: PlaybackHistoryRepository,
    sources: SourceRepository,
    private val settings: SettingsRepository,
    private val thumbnails: ThumbnailRepository,
) : ViewModel() {
    val uiState: StateFlow<PlaylistUiState> = combine(
        playlists.name(playlistId),
        playlists.items(playlistId),
        history.progress,
        sources.sources,
        settings.settings,
    ) { name, items, progress, sourceList, appSettings ->
        PlaylistUiState(
            name = name,
            items = items,
            progress = items.mapNotNull { item ->
                progress[item.ref.mediaKey]?.let { item.id to it }
            }.toMap(),
            sourceNames = sourceList.associate { it.key to it.name },
            showThumbnails = appSettings.browser.showThumbnails,
            viewMode = appSettings.browser.viewMode,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaylistUiState(viewMode = settings.current.value.browser.viewMode))

    suspend fun thumbnail(ref: MediaRef): Bitmap? = thumbnails.thumbnail(ref)

    fun setViewMode(mode: BrowserViewMode) {
        viewModelScope.launch { settings.updateBrowser { it.copy(viewMode = mode) } }
    }

    fun rename(name: String) {
        viewModelScope.launch { playlists.rename(playlistId, name) }
    }

    fun delete() {
        viewModelScope.launch { playlists.delete(playlistId) }
    }

    fun remove(item: PlaylistItem) {
        viewModelScope.launch { playlists.remove(item.id) }
    }

    /** Moves [item] up ([offset] < 0) or down within the playlist. */
    fun move(item: PlaylistItem, offset: Int) {
        val ids = uiState.value.items.map { it.id }.toMutableList()
        val from = ids.indexOf(item.id)
        val to = (from + offset).coerceIn(0, ids.lastIndex)
        if (from < 0 || from == to) return
        ids.add(to, ids.removeAt(from))
        viewModelScope.launch { playlists.reorder(ids) }
    }

    @AssistedFactory
    interface Factory {
        fun create(playlistId: Long): PlaylistViewModel
    }
}
