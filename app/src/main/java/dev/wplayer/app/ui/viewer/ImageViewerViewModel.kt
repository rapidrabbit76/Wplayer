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
import dev.wplayer.app.data.PlaybackHistoryRepository
import dev.wplayer.app.data.SettingsRepository
import dev.wplayer.app.data.archivePages
import dev.wplayer.app.data.imagePages
import dev.wplayer.app.data.model.AppSettings
import dev.wplayer.app.data.model.ComicDirection
import dev.wplayer.app.data.model.MediaEntry
import dev.wplayer.app.data.model.MediaRef
import dev.wplayer.app.data.model.PictureSettings
import dev.wplayer.app.data.model.mediaKey
import dev.wplayer.app.data.nextArchivePage
import dev.wplayer.app.di.ApplicationScope
import dev.wplayer.app.ui.common.UserError
import dev.wplayer.app.ui.common.catchingFailures
import dev.wplayer.app.ui.navigation.ImageViewerRoute
import dev.wplayer.core.ffi.ArchiveEntry
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
    /** The pictures in page order; null until they are listed. */
    val pages: List<ImagePage>? = null,
    /** The page to open on. */
    val startPath: String = "",
    /** Listing the pictures failed. */
    val error: UserError? = null,
    /** The pictures are (inside) a locked folder that has not been unlocked. */
    val hiddenByLock: Boolean = false,
    /** By [ImagePage.path]; pages not asked for yet are missing. */
    val images: Map<String, PageImage> = emptyMap(),
    val slideshowSeconds: Int = PictureSettings().slideshowSeconds,
    /** The archive's name when the pages are the pictures inside one; null for a folder's pictures. */
    val book: String? = null,
    /** The first page is on the right and the next one to its left, as in Japanese manga. */
    val rightToLeft: Boolean = false,
) {
    /** Where the pager starts: [startPath], or the first page when it is gone. */
    val startPage: Int get() = pages.orEmpty().indexOfFirst { it.path == startPath }.coerceAtLeast(0)
}

/** The pictures as listed, before the settings and locks decide what shows. */
private sealed interface Listing {
    data class Folder(val entries: List<MediaEntry>) : Listing

    /** [entries] in page order; [startPath] is the page the archive was left on. */
    data class Archive(val entries: List<ArchiveEntry>, val startPath: String?) : Listing {
        val pages: List<ImagePage> = archivePages(entries)
    }
}

/**
 * The image viewer: the pictures of one folder, or of one archive, each made a
 * local file when its page comes near the screen. A folder's pictures are
 * copied side by side; a page that moves away stops its copy, which continues
 * from where it stopped when the page comes back. An archive's pages are taken
 * out one at a time, the shown page first and then ahead in archive order,
 * since an archive reads fastest from front to back.
 */
@HiltViewModel(assistedFactory = ImageViewerViewModel.Factory::class)
class ImageViewerViewModel @AssistedInject constructor(
    @Assisted private val route: ImageViewerRoute,
    private val browserRepository: BrowserRepository,
    settingsRepository: SettingsRepository,
    private val locks: LockedFolders,
    private val imageCache: ImageCache,
    private val history: PlaybackHistoryRepository,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {
    // The browser listed this folder moments ago, so the first frame usually has the pages already.
    private val listing = MutableStateFlow<Listing?>(
        (route as? ImageViewerRoute.Folder)?.let { browserRepository.cachedListing(it.sourceKey, it.folder) }?.let(Listing::Folder),
    )
    private val listingError = MutableStateFlow<UserError?>(null)
    private val images = MutableStateFlow<Map<String, PageImage>>(emptyMap())

    /** A folder's copies running, by page path; touched on the main thread only. */
    private val fetches = mutableMapOf<String, Job>()

    /** An archive's pages on or next to the screen, by path; touched on the main thread only. */
    private val onScreen = mutableSetOf<String>()
    private var settledPage = 0
    private var archiveReader: Job? = null

    /** The [ArchiveEntry.index] read last, where the archive continues from. */
    private var lastRead: UInt? = null

    val uiState: StateFlow<ImageViewerUiState> = combine(
        listing,
        listingError,
        settingsRepository.settings,
        locks.state,
        images,
    ) { listing, error, settings, lockState, images -> state(listing, error, settings, lockState, images) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            state(listing.value, null, settingsRepository.current.value, locks.state.value, emptyMap()),
        )

    init {
        when (route) {
            is ImageViewerRoute.Folder -> if (listing.value == null) load()
            // An archive in a locked folder stays unopened until the folders are unlocked.
            is ImageViewerRoute.Archive -> viewModelScope.launch {
                locks.state.first { !it.isHidden(route.sourceKey, route.path) }
                load()
            }
        }
    }

    private fun state(
        listing: Listing?,
        error: UserError?,
        settings: AppSettings,
        lockState: LockState,
        images: Map<String, PageImage>,
    ) = ImageViewerUiState(
        pages = when (listing) {
            is Listing.Folder -> imagePages(route.sourceKey, listing.entries, settings.browser, lockState)
            is Listing.Archive -> listing.pages
            null -> null
        },
        startPath = when (listing) {
            is Listing.Archive -> listing.startPath.orEmpty()
            else -> route.path
        },
        error = error,
        hiddenByLock = lockState.isHidden(route.sourceKey, route.path),
        images = images,
        slideshowSeconds = settings.pictures.slideshowSeconds,
        book = (route as? ImageViewerRoute.Archive)?.path?.substringAfterLast('/'),
        rightToLeft = route is ImageViewerRoute.Archive && settings.pictures.comicDirection == ComicDirection.RIGHT_TO_LEFT,
    )

    fun load() {
        viewModelScope.launch {
            listingError.value = null
            catchingFailures { list() }
                .onSuccess { listed ->
                    if (listed is Listing.Archive) settledPage = listed.pages.indexOfFirst { it.path == listed.startPath }.coerceAtLeast(0)
                    listing.value = listed
                }
                .onFailure { listingError.value = it.toUserError() }
        }
    }

    private suspend fun list(): Listing = when (route) {
        is ImageViewerRoute.Folder -> Listing.Folder(browserRepository.list(route.sourceKey, route.folder))
        is ImageViewerRoute.Archive -> {
            val entries = browserRepository.listArchive(route.sourceKey, route.path)
            if (entries.isNotEmpty()) {
                history.recordPlayed(MediaRef(route.sourceKey, route.path, route.path.substringAfterLast('/'), isDirectory = false))
            }
            Listing.Archive(entries, history.readingPage(mediaKey(route.sourceKey, route.path)))
        }
    }

    /** [page] is on or next to the screen: fetches its picture unless it is here or on its way. */
    fun show(page: ImagePage) {
        when (route) {
            is ImageViewerRoute.Folder -> copy(page)
            is ImageViewerRoute.Archive -> {
                onScreen += page.path
                readArchive()
            }
        }
    }

    /** [page] moved away: stops its copy, if one is running. */
    fun hide(page: ImagePage) {
        onScreen -= page.path
        fetches.remove(page.path)?.let { job ->
            job.cancel()
            images.update { it - page.path }
        }
    }

    /** The pager came to rest on [page]: an archive remembers it and reads on from there. */
    fun settle(page: ImagePage) {
        if (route !is ImageViewerRoute.Archive) return
        val archive = listing.value as? Listing.Archive ?: return
        settledPage = archive.pages.indexOf(page).coerceAtLeast(0)
        readArchive()
        appScope.launch { history.saveReadingPage(mediaKey(route.sourceKey, route.path), page.path) }
    }

    fun retry(page: ImagePage) {
        images.update { it - page.path }
        show(page)
    }

    /** The decoder could not read [page]'s file. */
    fun unreadable(page: ImagePage) {
        val image = images.value[page.path]
        // The cache may have let the file go to make room for newer ones; fetch it again.
        if (image is PageImage.Ready && !image.file.exists()) {
            retry(page)
        } else {
            images.update { it + (page.path to PageImage.Unreadable) }
        }
    }

    private fun copy(page: ImagePage) {
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

    /**
     * Takes the wanted pages out of the archive one by one until none is left:
     * those on or next to the screen and the [PREFETCH_PAGES] after the settled one.
     */
    private fun readArchive() {
        if (archiveReader?.isActive == true) return
        archiveReader = viewModelScope.launch {
            while (!locks.state.value.isHidden(route.sourceKey, route.path)) {
                val archive = listing.value as? Listing.Archive ?: break
                val pages = archive.pages
                val ahead = settledPage + 1..settledPage + PREFETCH_PAGES
                val wanted = pages.indices.filterTo(mutableSetOf()) { index ->
                    (pages[index].path in onScreen || index in ahead) && pages[index].path !in images.value
                }
                val index = nextArchivePage(archive.entries, wanted, settledPage, lastRead) ?: break
                val page = pages[index]
                images.update { it + (page.path to PageImage.Arriving(null)) }
                catchingFailures { imageCache.archivePage(route.sourceKey, route.path, page.path) }
                    .onSuccess { file -> images.update { it + (page.path to PageImage.Ready(file)) } }
                    .onFailure { e ->
                        Log.w(TAG, "cannot read ${page.path} of ${route.path}", e)
                        images.update { it + (page.path to PageImage.Failed(UserError.fromArchive(e))) }
                    }
                lastRead = archive.entries[index].index
            }
        }
    }

    private fun Throwable.toUserError() = when (route) {
        is ImageViewerRoute.Folder -> UserError.from(this)
        is ImageViewerRoute.Archive -> UserError.fromArchive(this)
    }

    /** Call after the device authenticated the user. */
    fun unlockSession() = locks.unlockSession()

    override fun onCleared() {
        // Copies from locked folders live only while their viewer is open.
        appScope.launch { imageCache.forgetLocked() }
    }

    private companion object {
        const val TAG = "ImageViewerViewModel"

        /** Pages read ahead of the one on screen, so turning to them shows them at once. */
        const val PREFETCH_PAGES = 2
    }

    @AssistedFactory
    interface Factory {
        fun create(route: ImageViewerRoute): ImageViewerViewModel
    }
}
