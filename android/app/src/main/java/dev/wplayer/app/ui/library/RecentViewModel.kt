package dev.wplayer.app.ui.library

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wplayer.app.data.PlaybackHistoryRepository
import dev.wplayer.app.data.PlaylistRepository
import dev.wplayer.app.data.SettingsRepository
import dev.wplayer.app.data.SourceRepository
import dev.wplayer.app.data.ThumbnailRepository
import dev.wplayer.app.data.model.BrowserViewMode
import dev.wplayer.app.data.model.MediaRef
import dev.wplayer.app.data.model.Playlist
import dev.wplayer.app.data.model.RecentMedia
import dev.wplayer.app.data.model.WatchProgress
import dev.wplayer.app.ui.common.UserMessage
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RecentUiState(
    val items: List<RecentMedia> = emptyList(),
    /** Watch progress by media key. */
    val progress: Map<String, WatchProgress> = emptyMap(),
    val sourceNames: Map<String, String> = emptyMap(),
    val playlists: List<Playlist> = emptyList(),
    val message: UserMessage? = null,
    val showThumbnails: Boolean = true,
    val viewMode: BrowserViewMode = BrowserViewMode.GRID,
)

@HiltViewModel
class RecentViewModel @Inject constructor(
    private val history: PlaybackHistoryRepository,
    private val playlists: PlaylistRepository,
    sources: SourceRepository,
    private val settings: SettingsRepository,
    private val thumbnails: ThumbnailRepository,
) : ViewModel() {
    private val message = MutableStateFlow<UserMessage?>(null)

    val uiState: StateFlow<RecentUiState> = combine(
        combine(history.recent(), history.progress, sources.sources, ::Triple),
        playlists.playlists,
        message,
        settings.settings,
    ) { (items, progress, sourceList), playlistList, message, appSettings ->
        RecentUiState(
            items = items,
            progress = progress,
            sourceNames = sourceList.associate { it.key to it.name },
            playlists = playlistList,
            message = message,
            showThumbnails = appSettings.browser.showThumbnails,
            viewMode = appSettings.browser.viewMode,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecentUiState(viewMode = settings.current.value.browser.viewMode))

    suspend fun thumbnail(ref: MediaRef): Bitmap? = thumbnails.thumbnail(ref)

    fun setViewMode(mode: BrowserViewMode) {
        viewModelScope.launch { settings.updateBrowser { it.copy(viewMode = mode) } }
    }

    fun forget(ref: MediaRef) {
        viewModelScope.launch { history.forget(ref) }
    }

    fun clearAll() {
        viewModelScope.launch { history.clearAll() }
    }

    fun addToPlaylist(ref: MediaRef, playlistId: Long) {
        viewModelScope.launch {
            val added = playlists.add(playlistId, listOf(ref))
            message.value = if (added > 0) UserMessage.ADDED_TO_PLAYLIST else UserMessage.ALREADY_IN_PLAYLIST
        }
    }

    fun addToNewPlaylist(ref: MediaRef, name: String) {
        viewModelScope.launch {
            playlists.add(playlists.create(name), listOf(ref))
            message.value = UserMessage.ADDED_TO_PLAYLIST
        }
    }

    fun messageShown() {
        message.value = null
    }
}
