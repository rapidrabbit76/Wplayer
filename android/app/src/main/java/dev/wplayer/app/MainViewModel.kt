package dev.wplayer.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wplayer.app.data.SettingsRepository
import dev.wplayer.app.data.model.AppearanceSettings
import dev.wplayer.app.data.update.ReleaseManifest
import dev.wplayer.app.data.update.UpdateRepository
import dev.wplayer.app.data.update.UpdateState
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The settings the app shell (theme and layout) depends on. */
data class ShellSettings(val appearance: AppearanceSettings, val columnView: Boolean, val thumbnailScale: Float)

@HiltViewModel
class MainViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
    private val updates: UpdateRepository,
) : ViewModel() {
    /** Set when the start-up check runs, so its result is offered once in a dialog. */
    private val offerUpdate = MutableStateFlow(false)

    /** What the start-up update dialog shows; null hides it. Manual checks in settings never open it. */
    val updateOffer: StateFlow<UpdateState?> = combine(offerUpdate, updates.state) { offered, state ->
        state.takeIf {
            offered && (it is UpdateState.Available || it is UpdateState.Downloading || it is UpdateState.NeedsInstallPermission)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch {
            if (settingsRepository.settings.first().update.autoCheck) {
                offerUpdate.value = true
                updates.check(onlyIfDue = true)
            }
        }
    }

    fun installUpdate(release: ReleaseManifest) = updates.install(release)

    fun installPermissionIntent() = updates.installPermissionIntent()

    fun dismissUpdate() {
        offerUpdate.value = false
    }

    /** Null until the settings are read, so the first frame never flashes the wrong theme. */
    val shell: StateFlow<ShellSettings?> = settingsRepository.settings
        .map { ShellSettings(it.appearance, it.browser.columnView, it.browser.thumbnailScale) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}
