package dev.wplayer.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wplayer.app.data.PlaylistRepository
import dev.wplayer.app.data.SettingsRepository
import dev.wplayer.app.data.model.BrowserViewMode
import dev.wplayer.app.data.model.Playlist
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    val viewMode: StateFlow<BrowserViewMode> = settingsRepository.settings.map { it.browser.viewMode }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), settingsRepository.current.value.browser.viewMode)

    fun setViewMode(mode: BrowserViewMode) {
        viewModelScope.launch { settingsRepository.updateBrowser { it.copy(viewMode = mode) } }
    }

    val playlists: StateFlow<List<Playlist>> = playlistRepository.playlists.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    fun createPlaylist(name: String) {
        viewModelScope.launch { playlistRepository.create(name) }
    }
}
