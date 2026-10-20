package dev.wplayer.app.ui.browser

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wplayer.app.data.BrowserRepository
import dev.wplayer.app.data.DownloadRepository
import dev.wplayer.app.data.LockedFolders
import dev.wplayer.app.data.PlaybackHistoryRepository
import dev.wplayer.app.data.PlaylistRepository
import dev.wplayer.app.data.SearchProgress
import dev.wplayer.app.data.SettingsRepository
import dev.wplayer.app.data.SourceRepository
import dev.wplayer.app.data.ThumbnailRepository
import dev.wplayer.app.data.arrange
import dev.wplayer.app.data.model.BrowserSettings
import dev.wplayer.app.data.model.MediaEntry
import dev.wplayer.app.data.model.MediaKind
import dev.wplayer.app.data.model.MediaRef
import dev.wplayer.app.data.model.Playlist
import dev.wplayer.app.data.model.Source
import dev.wplayer.app.data.model.WatchProgress
import dev.wplayer.app.data.model.mediaKey
import dev.wplayer.app.data.nameMatches
import dev.wplayer.app.ui.common.UserError
import dev.wplayer.app.ui.common.UserMessage
import dev.wplayer.app.ui.common.catchingFailures
import dev.wplayer.app.ui.navigation.BrowserRoute
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BrowserUiState(
    val source: Source? = null,
    val path: String = "/",
    /** Filtered and sorted per [settings]. */
    val entries: List<MediaEntry> = emptyList(),
    val isLoading: Boolean = true,
    val error: UserError? = null,
    val settings: BrowserSettings = BrowserSettings(),
    /** Watch progress of files in this folder, by path. */
    val progress: Map<String, WatchProgress> = emptyMap(),
    val playlists: List<Playlist> = emptyList(),
    val message: UserMessage? = null,
    /** Searching by name; null while browsing. */
    val search: BrowserSearch? = null,
    /** Paths of this folder's subfolders that are locked. */
    val lockedFolders: Set<String> = emptySet(),
    /** Locked folders have been unlocked this session. */
    val locksOpen: Boolean = false,
    /** This folder is (inside) a locked folder that has not been unlocked. */
    val hiddenByLock: Boolean = false,
) {
    /** The folder name, or null at the source root (show the source name instead). */
    val folderName: String? get() = path.trimEnd('/').substringAfterLast('/').ifEmpty { null }

    /** The folder containing this one, or null at the source root. */
    val parentFolder: String? get() = parentPath(path)
}

data class BrowserSearch(
    val query: String,
    val includeSubfolders: Boolean,
    /** Matches in this folder, or below it when [includeSubfolders]; everything here for a blank query. */
    val results: List<MediaEntry>,
    val inProgress: Boolean,
    /** The subfolder search hit its depth or result limit. */
    val truncated: Boolean,
)

@HiltViewModel(assistedFactory = BrowserViewModel.Factory::class)
class BrowserViewModel @AssistedInject constructor(
    @Assisted private val route: BrowserRoute,
    private val browserRepository: BrowserRepository,
    private val sourceRepository: SourceRepository,
    private val settingsRepository: SettingsRepository,
    private val playlistRepository: PlaylistRepository,
    private val history: PlaybackHistoryRepository,
    private val thumbnails: ThumbnailRepository,
    private val downloads: DownloadRepository,
    private val locks: LockedFolders,
) : ViewModel() {
    /** The folder as listed, before filtering and sorting. */
    // Starts from this session's cached listing, if any, so a reopened folder shows on the first frame.
    private val listing = MutableStateFlow(
        browserRepository.cachedListing(route.sourceKey, route.path).let { cached ->
            BrowserUiState(path = route.path, entries = cached.orEmpty(), isLoading = cached == null)
        },
    )
    private val message = MutableStateFlow<UserMessage?>(null)
    private val searchQuery = MutableStateFlow<String?>(null)
    private val searchSubfolders = MutableStateFlow(false)

    /** The running subfolder search; restarted (after typing pauses) when the query or filters change. */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private val subfolderSearch: Flow<SearchProgress?> = combine(
        searchQuery.map { it?.trim().orEmpty() },
        searchSubfolders,
        settingsRepository.settings.map { it.browser }.distinctUntilChanged(),
    ) { query, subfolders, settings -> Triple(query, subfolders, settings) }
        .distinctUntilChanged()
        .debounce(SEARCH_DEBOUNCE_MS)
        .flatMapLatest { (query, subfolders, settings) ->
            if (query.isEmpty() || !subfolders) {
                flowOf(null)
            } else {
                browserRepository.search(route.sourceKey, route.path, query, settings)
                    .onStart { emit(SearchProgress(emptyList(), finished = false, truncated = false)) }
            }
        }

    private val search: Flow<SearchInput?> = combine(searchQuery, searchSubfolders, subfolderSearch) { query, subfolders, found ->
        query?.let { SearchInput(it, subfolders, found) }
    }

    val uiState: StateFlow<BrowserUiState> = combine(
        listing,
        settingsRepository.settings,
        history.progress,
        playlistRepository.playlists,
        combine(message, search, locks.state, ::Triple),
    ) { listing, settings, progress, playlists, (message, search, lockState) ->
        val entries = arrange(listing.entries, settings.browser)
        val browserSearch = search?.toState(entries)
        val shown = browserSearch?.results ?: entries
        listing.copy(
            entries = entries,
            settings = settings.browser,
            progress = shown.mapNotNull { entry ->
                progress[mediaKey(route.sourceKey, entry.path)]?.let { entry.path to it }
            }.toMap(),
            playlists = playlists,
            message = message,
            search = browserSearch,
            lockedFolders = entries.filter { it.kind == MediaKind.DIRECTORY && lockState.isLocked(route.sourceKey, it.path) }
                .map { it.path }
                .toSet(),
            locksOpen = lockState.unlocked,
            hiddenByLock = lockState.isHidden(route.sourceKey, route.path),
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        // The first frame, before history and playlists load: the cached listing with the saved settings.
        settingsRepository.current.value.browser.let { browser ->
            listing.value.copy(entries = arrange(listing.value.entries, browser), settings = browser)
        },
    )

    val sourceKey: String get() = route.sourceKey

    init {
        load(showProgress = false)
    }

    /** Pull to refresh: lists the folder again with the progress indicator. */
    fun refresh() = load(showProgress = true)

    /**
     * Lists the folder. A listing cached this session shows at once and is
     * replaced when the fresh one arrives; if that fails, what is shown stays
     * and a message says so instead of an error page.
     */
    private fun load(showProgress: Boolean) {
        viewModelScope.launch {
            val cached = browserRepository.cachedListing(route.sourceKey, route.path)
            listing.update { it.copy(entries = cached ?: it.entries, isLoading = showProgress || cached == null, error = null) }
            catchingFailures {
                val source = sourceRepository.find(route.sourceKey)
                listing.update { it.copy(source = source) }
                browserRepository.list(route.sourceKey, route.path)
            }
                .onSuccess { entries -> listing.update { it.copy(entries = entries, isLoading = false) } }
                .onFailure { e ->
                    if (listing.value.entries.isNotEmpty()) {
                        listing.update { it.copy(isLoading = false) }
                        message.value = UserMessage.REFRESH_FAILED
                    } else {
                        listing.update { it.copy(isLoading = false, error = UserError.from(e)) }
                    }
                }
        }
    }

    fun startSearch() {
        if (searchQuery.value == null) searchQuery.value = ""
    }

    fun setSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun setSearchSubfolders(include: Boolean) {
        searchSubfolders.value = include
    }

    fun endSearch() {
        searchQuery.value = null
    }

    fun updateSettings(transform: (BrowserSettings) -> BrowserSettings) {
        viewModelScope.launch { settingsRepository.update { it.copy(browser = transform(it.browser)) } }
    }

    fun addToPlaylist(entry: MediaEntry, playlistId: Long) {
        viewModelScope.launch {
            val added = playlistRepository.add(playlistId, listOf(entry.toRef()))
            message.value = if (added > 0) UserMessage.ADDED_TO_PLAYLIST else UserMessage.ALREADY_IN_PLAYLIST
        }
    }

    fun addToNewPlaylist(entry: MediaEntry, name: String) {
        viewModelScope.launch {
            playlistRepository.add(playlistRepository.create(name), listOf(entry.toRef()))
            message.value = UserMessage.ADDED_TO_PLAYLIST
        }
    }

    /** Clears where [entry] was left off, so it plays from the start and loses its progress bar. */
    fun forgetHistory(entry: MediaEntry) {
        viewModelScope.launch { history.forget(entry.toRef()) }
    }

    suspend fun thumbnail(entry: MediaEntry): Bitmap? = thumbnails.thumbnail(entry.toRef())

    /** Downloads a file from this (network) source to device storage. */
    fun download(entry: MediaEntry) {
        viewModelScope.launch {
            downloads.enqueue(entry.toRef())
            message.value = UserMessage.DOWNLOAD_STARTED
        }
    }

    fun lockFolder(entry: MediaEntry) {
        viewModelScope.launch {
            locks.lock(route.sourceKey, entry.path)
            message.value = UserMessage.FOLDER_LOCKED
        }
    }

    /** Call after the device authenticated the user. */
    fun removeLock(entry: MediaEntry) {
        viewModelScope.launch { locks.removeLock(route.sourceKey, entry.path) }
    }

    /** Call after the device authenticated the user. */
    fun unlockSession() = locks.unlockSession()

    fun showMessage(text: UserMessage) {
        message.value = text
    }

    fun messageShown() {
        message.value = null
    }

    private fun MediaEntry.toRef() = MediaRef(route.sourceKey, path, name, isDirectory = kind == MediaKind.DIRECTORY)

    private data class SearchInput(val query: String, val includeSubfolders: Boolean, val found: SearchProgress?)

    private fun SearchInput.toState(folderEntries: List<MediaEntry>): BrowserSearch {
        val trimmed = query.trim()
        val deep = includeSubfolders && trimmed.isNotEmpty()
        return BrowserSearch(
            query = query,
            includeSubfolders = includeSubfolders,
            results = when {
                trimmed.isEmpty() -> folderEntries
                deep -> found?.matches.orEmpty()
                else -> folderEntries.filter { nameMatches(it.name, trimmed) }
            },
            // Waiting for typing to pause counts as searching too.
            inProgress = deep && found?.finished != true,
            truncated = deep && found?.truncated == true,
        )
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
    }

    @AssistedFactory
    interface Factory {
        fun create(route: BrowserRoute): BrowserViewModel
    }
}
