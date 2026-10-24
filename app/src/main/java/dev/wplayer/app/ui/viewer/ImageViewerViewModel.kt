package dev.wplayer.app.ui.viewer

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wplayer.app.data.BrowserRepository
import dev.wplayer.app.data.ImageCache
import dev.wplayer.app.data.ImagePage
import dev.wplayer.app.data.LockState
import dev.wplayer.app.data.LockedFolders
import dev.wplayer.app.data.SettingsRepository
import dev.wplayer.app.data.imagePages
import dev.wplayer.app.data.model.AppSettings
import dev.wplayer.app.data.model.MediaEntry
import dev.wplayer.app.data.model.PictureSettings
import dev.wplayer.app.di.ApplicationScope
import dev.wplayer.app.ui.common.UserError
import dev.wplayer.app.ui.common.catchingFailures
import dev.wplayer.app.ui.navigation.ImageViewerRoute
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How far a page's picture has come. */
sealed interface PageImage {
    /** [fraction] of the file is here; null while its size is unknown. */
    data class Arriving(val fraction: Float?) : PageImage

    data class Ready(val file: File) : PageImage

    /** The file could not be read from its source. */
    data class Failed(val error: UserError) : PageImage

    /** The file arrived but is not a picture Android can show. */
    data object Unreadable : PageImage
}

data class ImageViewerUiState(
    /** The folder's pictures in the browser's order; null until the folder is listed. */
    val pages: List<ImagePage>? = null,
    /** The picture that was opened. */
    val startPath: String = "",
    /** Listing the folder failed. */
    val error: UserError? = null,
    /** The folder is (inside) a locked folder that has not been unlocked. */
    val hiddenByLock: Boolean = false,
    /** By [ImagePage.path]; pages not asked for yet are missing. */
    val images: Map<String, PageImage> = emptyMap(),
    val slideshowSeconds: Int = PictureSettings().slideshowSeconds,
    /** The first page is on the right and the next one to its left, as in Japanese manga. */
    val rightToLeft: Boolean = false,
) {
    /** Where the pager starts: the opened picture, or the first when it is gone. */
    val startPage: Int get() = pages.orEmpty().indexOfFirst { it.path == startPath }.coerceAtLeast(0)
}

/**
 * The image viewer: the pictures of one folder, each fetched as a local file
 * when its page comes near the screen. Pages that move away stop their copy;
 * it continues from where it stopped when they come back.
 */
@HiltViewModel(assistedFactory = ImageViewerViewModel.Factory::class)
class ImageViewerViewModel @AssistedInject constructor(
    @Assisted private val route: ImageViewerRoute,
    private val browserRepository: BrowserRepository,
    settingsRepository: SettingsRepository,
    private val locks: LockedFolders,
    private val imageCache: ImageCache,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {
    // The browser listed this folder moments ago, so the first frame usually has the pages already.
    private val entries = MutableStateFlow(browserRepository.cachedListing(route.sourceKey, route.folder))
    private val listingError = MutableStateFlow<UserError?>(null)
    private val images = MutableStateFlow<Map<String, PageImage>>(emptyMap())

    /** Copies running, by page path; touched on the main thread only. */
    private val fetches = mutableMapOf<String, Job>()

    val uiState: StateFlow<ImageViewerUiState> = combine(
        entries,
        listingError,
        settingsRepository.settings,
        locks.state,
        images,
    ) { entries, error, settings, lockState, images -> state(entries, error, settings, lockState, images) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            state(entries.value, null, settingsRepository.current.value, locks.state.value, emptyMap()),
        )

    init {
        if (entries.value == null) load()
    }

    private fun state(
        entries: List<MediaEntry>?,
        error: UserError?,
        settings: AppSettings,
        lockState: LockState,
        images: Map<String, PageImage>,
    ) = ImageViewerUiState(
        pages = entries?.let { imagePages(route.sourceKey, it, settings.browser, lockState) },
        startPath = route.path,
        error = error,
        hiddenByLock = lockState.isHidden(route.sourceKey, route.folder),
        images = images,
        slideshowSeconds = settings.pictures.slideshowSeconds,
    )

    fun load() {
        viewModelScope.launch {
            listingError.value = null
            catchingFailures { browserRepository.list(route.sourceKey, route.folder) }
                .onSuccess { entries.value = it }
                .onFailure { listingError.value = UserError.from(it) }
        }
    }

    /** [page] is on or next to the screen: fetches its picture unless it is here or on its way. */
    fun show(page: ImagePage) {
        if (page.path in fetches || images.value[page.path] is PageImage.Ready) return
        images.update { it + (page.path to PageImage.Arriving(null)) }
        fetches[page.path] = viewModelScope.launch {
            catchingFailures {
                imageCache.file(route.sourceKey, page.path) { fraction ->
                    images.update { it + (page.path to PageImage.Arriving(fraction)) }
                }
            }
                .onSuccess { file -> images.update { it + (page.path to PageImage.Ready(file)) } }
                .onFailure { e ->
                    Log.w(TAG, "cannot fetch ${page.path}", e)
                    images.update { it + (page.path to PageImage.Failed(UserError.from(e))) }
                }
            fetches -= page.path
        }
    }

    /** [page] moved away: stops its copy, if one is running. */
    fun hide(page: ImagePage) {
        fetches.remove(page.path)?.let { job ->
            job.cancel()
            images.update { it - page.path }
        }
    }

    fun retry(page: ImagePage) {
        images.update { it - page.path }
        show(page)
    }

    /** The decoder could not read [page]'s file. */
    fun unreadable(page: ImagePage) {
        images.update { it + (page.path to PageImage.Unreadable) }
    }

    /** Call after the device authenticated the user. */
    fun unlockSession() = locks.unlockSession()

    override fun onCleared() {
        // Copies from locked folders live only while their viewer is open.
        appScope.launch { imageCache.forgetLocked() }
    }

    private companion object {
        const val TAG = "ImageViewerViewModel"
    }

    @AssistedFactory
    interface Factory {
        fun create(route: ImageViewerRoute): ImageViewerViewModel
    }
}
