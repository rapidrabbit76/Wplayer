package dev.wplayer.app.ui.downloads

import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wplayer.app.data.DownloadRepository
import dev.wplayer.app.data.SettingsRepository
import dev.wplayer.app.data.SourceRepository
import dev.wplayer.app.data.model.BrowserViewMode
import dev.wplayer.app.data.model.Download
import dev.wplayer.app.ui.player.PlaybackRequest
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val repository: DownloadRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    val viewMode: StateFlow<BrowserViewMode> = settings.settings.map { it.browser.viewMode }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), settings.current.value.browser.viewMode)

    fun setViewMode(mode: BrowserViewMode) = launch { settings.update { it.copy(browser = it.browser.copy(viewMode = mode)) } }

    val downloads: StateFlow<List<Download>> = repository.downloads.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    /** Where downloads are saved, as shown under device storage. */
    val folderLabel: String = "${Environment.DIRECTORY_DOWNLOADS}/${DownloadRepository.FOLDER_NAME}"

    fun cancel(download: Download) = launch { repository.cancel(download.id) }

    fun retry(download: Download) = launch { repository.retry(download.id) }

    fun remove(download: Download, deleteFile: Boolean) = launch { repository.remove(download.id, deleteFile) }

    fun clearFinished() = launch { repository.clearFinished() }

    /** Plays a finished download from device storage. */
    fun playbackRequest(download: Download): PlaybackRequest? {
        val root = Environment.getExternalStorageDirectory().absolutePath
        if (!download.destPath.startsWith(root)) return null
        return PlaybackRequest.FromSource(SourceRepository.LOCAL_KEY, download.destPath.removePrefix(root), download.ref.name)
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
