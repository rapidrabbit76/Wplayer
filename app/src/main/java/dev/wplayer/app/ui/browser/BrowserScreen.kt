package dev.wplayer.app.ui.browser

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.DriveFolderUpload
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wplayer.app.R
import dev.wplayer.app.data.MAX_SEARCH_DEPTH
import dev.wplayer.app.data.MAX_SEARCH_RESULTS
import dev.wplayer.app.data.PathSegment
import dev.wplayer.app.data.SubtitleFontStore
import dev.wplayer.app.data.model.BrowserSettings
import dev.wplayer.app.data.model.BrowserViewMode
import dev.wplayer.app.data.model.MediaEntry
import dev.wplayer.app.data.model.MediaKind
import dev.wplayer.app.data.model.SortField
import dev.wplayer.app.data.model.SourceProtocol
import dev.wplayer.app.data.parentPath
import dev.wplayer.app.data.pathSegments
import dev.wplayer.app.ui.common.AddToPlaylistDialog
import dev.wplayer.app.ui.common.CenteredMessage
import dev.wplayer.app.ui.common.CollectionCard
import dev.wplayer.app.ui.common.MediaCard
import dev.wplayer.app.ui.common.MediaRow
import dev.wplayer.app.ui.common.RowAction
import dev.wplayer.app.ui.common.ThumbnailSizeButton
import dev.wplayer.app.ui.common.UserMessage
import dev.wplayer.app.ui.common.UserMessageEffect
import dev.wplayer.app.ui.common.ViewModeToggle
import dev.wplayer.app.ui.common.rememberRemoteTextField
import dev.wplayer.app.ui.common.mediaGridCellMinWidth
import dev.wplayer.app.ui.common.message
import dev.wplayer.app.ui.common.rememberDeviceAuthentication
import dev.wplayer.app.ui.navigation.LocalColumnRole
import dev.wplayer.app.ui.player.PlaybackRequest
import dev.wplayer.app.ui.sources.displayName

@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    /** The child folder open in the next column, highlighted here. */
    selectedPath: String?,
    /** An entry to scroll to and highlight, when the folder was opened to show a file. */
    focusPath: String?,
    onOpenFolder: (String) -> Unit,
    /** Opens the folder holding a search result, showing that result. */
    onShowInFolder: (String) -> Unit,
    /** Goes to a folder above this one, from the path bar or the ".." entry. */
    onNavigateTo: (String) -> Unit,
    onPlay: (PlaybackRequest) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // A search result in this very folder is shown here rather than in a new column.
    var focus by rememberSaveable { mutableStateOf(focusPath) }
    var addingToPlaylist by remember { mutableStateOf<MediaEntry?>(null) }
    val snackbar = remember { SnackbarHostState() }
    UserMessageEffect(state.message, snackbar, viewModel::messageShown)

    val auth = rememberDeviceAuthentication()
    val openLockedTitle = stringResource(R.string.locked_folder_open)
    val unlockFolderTitle = stringResource(R.string.browser_unlock_folder)
    val onEntryClick: (MediaEntry) -> Unit = { entry ->
        if (entry.kind == MediaKind.DIRECTORY) {
            if (entry.path in state.lockedFolders && !state.locksOpen) {
                auth.authenticate(openLockedTitle) {
                    viewModel.unlockSession()
                    onOpenFolder(entry.path)
                }
            } else {
                onOpenFolder(entry.path)
            }
        } else if (SubtitleFontStore.isFontFile(entry.name)) {
            viewModel.useAsSubtitleFont(entry)
        } else {
            onPlay(PlaybackRequest.FromSource(viewModel.sourceKey, entry.path, entry.name))
        }
    }
    // The download notification needs this permission on Android 13+; downloading works either way.
    var pendingDownload by remember { mutableStateOf<MediaEntry?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingDownload?.let(viewModel::download)
        pendingDownload = null
    }
    val context = LocalContext.current
    val startDownload: (MediaEntry) -> Unit = { entry ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = entry
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.download(entry)
        }
    }

    val addLabel = stringResource(R.string.playlist_add_title)
    val forgetLabel = stringResource(R.string.browser_forget_history)
    val downloadLabel = stringResource(R.string.download_action)
    val lockFolderLabel = stringResource(R.string.browser_lock_folder)
    val showInFolderLabel = stringResource(R.string.show_in_folder)
    val useAsFontLabel = stringResource(R.string.browser_use_as_subtitle_font)
    val isRemote = state.source?.protocol != SourceProtocol.LOCAL
    val inSearch = state.search != null
    val entryActions: (MediaEntry) -> List<RowAction> = { entry ->
        buildList {
            if (inSearch) {
                add(
                    RowAction(showInFolderLabel) {
                        if (parentPath(entry.path) == state.path) {
                            viewModel.endSearch()
                            focus = entry.path
                        } else {
                            onShowInFolder(entry.path)
                        }
                    },
                )
            }
            when {
                SubtitleFontStore.isFontFile(entry.name) -> add(RowAction(useAsFontLabel) { viewModel.useAsSubtitleFont(entry) })
                // Playlists hold what the player plays; pictures open in the viewer instead.
                entry.kind != MediaKind.IMAGE -> add(RowAction(addLabel) { addingToPlaylist = entry })
            }
            if (isRemote && (entry.isPlayable || entry.kind == MediaKind.IMAGE)) add(RowAction(downloadLabel) { startDownload(entry) })
            if (entry.path in state.progress) add(RowAction(forgetLabel) { viewModel.forgetHistory(entry) })
            if (entry.kind == MediaKind.DIRECTORY) {
                if (entry.path in state.lockedFolders) {
                    add(RowAction(unlockFolderTitle) { auth.authenticate(unlockFolderTitle) { viewModel.removeLock(entry) } })
                } else {
                    add(
                        RowAction(lockFolderLabel) {
                            if (auth.isAvailable) viewModel.lockFolder(entry) else viewModel.showMessage(UserMessage.NO_SCREEN_LOCK)
                        },
                    )
                }
            }
        }
    }
    val navigation = state.settings.folderNavigation
    val parentEntry = state.parentFolder
        ?.takeIf { navigation.showsParentRow }
        ?.let { parent -> ParentEntry(label = folderLabel(state, parent), onOpen = { onNavigateTo(parent) }) }
    val search = state.search
    BackHandler(enabled = search != null, onBack = viewModel::endSearch)
    val content: @Composable () -> Unit = {
        if (search == null) {
            BrowserContent(
                state = state,
                selectedPath = selectedPath ?: focus,
                onRefresh = viewModel::refresh,
                onEntryClick = onEntryClick,
                entryActions = entryActions,
                loadThumbnail = viewModel::thumbnail,
                parentEntry = parentEntry,
                scrollTo = focus,
            )
        } else {
            SearchResults(search, state, viewModel::refresh, onEntryClick, entryActions, viewModel::thumbnail)
        }
    }
    BrowserLayout(
        state = state,
        onBack = onBack,
        onNavigateTo = onNavigateTo.takeIf { navigation.showsPathBar },
        snackbar = snackbar,
        searchBar = search?.let {
            {
                SearchTopBar(
                    search = it,
                    onQueryChange = viewModel::setSearchQuery,
                    onSubfoldersChange = viewModel::setSearchSubfolders,
                    onClose = viewModel::endSearch,
                )
            }
        },
        actions = {
            IconButton(onClick = viewModel::startSearch) {
                Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.browser_search))
            }
            ThumbnailSizeButton(state.settings.thumbnailScale) { scale -> viewModel.updateSettings { it.copy(thumbnailScale = scale) } }
            ViewModeToggle(state.settings.viewMode) { mode -> viewModel.updateSettings { it.copy(viewMode = mode) } }
            SortMenu(state.settings, viewModel::updateSettings)
        },
    ) {
        when {
            state.hiddenByLock -> LockedFolderMessage(onOpen = { auth.authenticate(openLockedTitle, viewModel::unlockSession) })
            state.source?.protocol == SourceProtocol.LOCAL -> StoragePermissionGate(onGranted = viewModel::refresh, content = content)
            else -> content()
        }
    }

    addingToPlaylist?.let { entry ->
        AddToPlaylistDialog(
            itemName = entry.name,
            playlists = state.playlists,
            onPick = { id ->
                viewModel.addToPlaylist(entry, id)
                addingToPlaylist = null
            },
            onCreate = { name ->
                viewModel.addToNewPlaylist(entry, name)
                addingToPlaylist = null
            },
            onDismiss = { addingToPlaylist = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BrowserLayout(
    state: BrowserUiState,
    onBack: () -> Unit,
    /** Shows the path bar; null hides it. */
    onNavigateTo: ((String) -> Unit)? = null,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    /** Replaces the title bar while searching. */
    searchBar: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    // A parent folder shown beside the current one only names itself; the current column has the controls.
    val parentColumn = LocalColumnRole.current?.isCurrent == false
    Scaffold(
        topBar = {
            Column {
                if (searchBar != null && !parentColumn) searchBar() else TopAppBar(
                    title = {
                        Text(
                            text = state.folderName ?: state.source?.displayName().orEmpty(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = if (parentColumn) MaterialTheme.typography.titleMedium else LocalTextStyle.current,
                            color = if (parentColumn) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
                        )
                    },
                    navigationIcon = {
                        if (!parentColumn) {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                            }
                        }
                    },
                    actions = { if (!parentColumn) actions() },
                )
                when {
                    onNavigateTo == null -> Unit
                    // Same height beside the current folder's path bar, so the columns' rows line up;
                    // also until the source (the path's first name) has loaded.
                    parentColumn || state.source == null -> Spacer(Modifier.height(PATH_BAR_HEIGHT))
                    else -> PathBar(rootName = state.source?.displayName().orEmpty(), path = state.path, onNavigateTo = onNavigateTo)
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) { content() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BrowserContent(
    state: BrowserUiState,
    selectedPath: String?,
    onRefresh: () -> Unit,
    onEntryClick: (MediaEntry) -> Unit,
    entryActions: (MediaEntry) -> List<RowAction> = { emptyList() },
    loadThumbnail: (suspend (MediaEntry) -> Bitmap?)? = null,
    /** The ".." entry leading one folder up; null leaves it out. */
    parentEntry: ParentEntry? = null,
    /** Shows each entry's folder relative to this one, for results from subfolders. */
    showLocation: Boolean = false,
    emptyMessage: String = stringResource(R.string.browser_empty),
    /** An entry to bring into view once it is listed. */
    scrollTo: String? = null,
) {
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    // Scrolls once per target, not again when a refresh relists the folder.
    var scrolledTo by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val isCurrentColumn = LocalColumnRole.current?.isCurrent != false
    // Parent columns are narrow: always a list. The current folder follows the view setting.
    val grid = state.settings.viewMode == BrowserViewMode.GRID && isCurrentColumn
    // Parent columns already show the way up.
    val parent = parentEntry?.takeIf { isCurrentColumn }
    val thumbnailFor: (MediaEntry) -> (suspend () -> Bitmap?)? = { entry ->
        loadThumbnail
            ?.takeIf { state.settings.showThumbnails && entry.kind == MediaKind.VIDEO }
            ?.let { load -> { load(entry) } }
    }
    val sizeOf: (MediaEntry) -> String? = { entry ->
        val size = if (entry.kind == MediaKind.DIRECTORY) null else Formatter.formatShortFileSize(context, entry.size)
        val location = if (showLocation) relativeFolder(state.path, entry.path) else null
        listOfNotNull(location, size).joinToString(" · ").ifEmpty { null }
    }
    LaunchedEffect(scrollTo, state.entries, grid) {
        if (scrollTo == null || scrollTo == scrolledTo) return@LaunchedEffect
        val index = state.entries.indexOfFirst { it.path == scrollTo }
        if (index < 0) return@LaunchedEffect
        val item = index + if (parent != null) 1 else 0
        if (grid) gridState.scrollToItem(item) else listState.scrollToItem(item)
        scrolledTo = scrollTo
    }

    // With a remote, focus starts on the entry to show, the one used last (back from a
    // subfolder), or the first, so OK opens something right away. Touch mode ignores this.
    var lastFocused by rememberSaveable { mutableStateOf<String?>(null) }
    val focusTarget = (scrollTo ?: lastFocused)?.takeIf { path -> state.entries.any { it.path == path } }
        ?: state.entries.firstOrNull()?.path
    val entryFocus = remember { FocusRequester() }
    var focusPlaced by remember { mutableStateOf(false) }
    LaunchedEffect(focusTarget, grid) {
        if (focusPlaced || !isCurrentColumn || focusTarget == null) return@LaunchedEffect
        val item = state.entries.indexOfFirst { it.path == focusTarget } + if (parent != null) 1 else 0
        val visible = if (grid) {
            gridState.layoutInfo.visibleItemsInfo.any { it.index == item }
        } else {
            listState.layoutInfo.visibleItemsInfo.any { it.index == item }
        }
        if (!visible) {
            if (grid) gridState.scrollToItem(item) else listState.scrollToItem(item)
        }
        withFrameNanos { } // Until the item is laid out with its requester.
        focusPlaced = true
        entryFocus.requestFocus()
    }
    val focusModifier: (MediaEntry) -> Modifier = { entry ->
        Modifier
            .then(if (entry.path == focusTarget) Modifier.focusRequester(entryFocus) else Modifier)
            .onFocusChanged { if (it.isFocused) lastFocused = entry.path }
    }
    // Pulling is a touch gesture. With a remote, focus scrolling at the top of the list
    // would pull the indicator part way and leave it there, with no release to settle it.
    val pullState = rememberPullToRefreshState()
    Box(
        Modifier
            .fillMaxSize()
            .pullToRefresh(
                isRefreshing = state.isLoading,
                state = pullState,
                enabled = LocalInputModeManager.current.inputMode == InputMode.Touch,
                onRefresh = onRefresh,
            ),
    ) {
        when {
            state.error != null -> CenteredMessage(stringResource(R.string.browser_error, state.error.message())) {
                Button(onClick = onRefresh) { Text(stringResource(R.string.action_retry)) }
            }
            !state.isLoading && state.entries.isEmpty() && parent == null -> CenteredMessage(emptyMessage)
            !state.isLoading && state.entries.isEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
                item(key = PARENT_NAME) { ParentRow(parent!!) }
                item { CenteredMessage(stringResource(R.string.browser_empty), Modifier.fillParentMaxHeight(0.6f)) }
            }
            grid -> LazyVerticalGrid(
                columns = GridCells.Adaptive(mediaGridCellMinWidth),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
            ) {
                parent?.let { item(key = PARENT_NAME) { CollectionCard(Icons.Outlined.DriveFolderUpload, PARENT_NAME, it.label, it.onOpen) } }
                gridItems(state.entries, key = { it.path }) { entry ->
                    MediaCard(
                        name = entry.name,
                        kind = entry.kind,
                        supporting = sizeOf(entry),
                        progress = state.progress[entry.path].takeIf { state.settings.showProgress },
                        selected = entry.path == selectedPath,
                        actions = entryActions(entry),
                        onClick = { onEntryClick(entry) },
                        loadThumbnail = thumbnailFor(entry),
                        locked = entry.path in state.lockedFolders,
                        modifier = focusModifier(entry),
                    )
                }
            }
            else -> LazyColumn(Modifier.fillMaxSize(), state = listState) {
                parent?.let { item(key = PARENT_NAME) { ParentRow(it) } }
                items(state.entries, key = { it.path }) { entry ->
                    MediaRow(
                        name = entry.name,
                        kind = entry.kind,
                        supporting = sizeOf(entry),
                        progress = state.progress[entry.path].takeIf { state.settings.showProgress },
                        selected = entry.path == selectedPath,
                        actions = entryActions(entry),
                        onClick = { onEntryClick(entry) },
                        loadThumbnail = thumbnailFor(entry),
                        locked = entry.path in state.lockedFolders,
                        modifier = focusModifier(entry),
                    )
                }
            }
        }
        PullToRefreshDefaults.Indicator(
            state = pullState,
            isRefreshing = state.isLoading,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

/** In place of a locked folder's contents until the device authenticates the user. */
@Composable
internal fun LockedFolderMessage(onOpen: () -> Unit) {
    CenteredMessage(stringResource(R.string.locked_folder_message)) {
        Button(onClick = onOpen) {
            Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(18.dp))
            Text(stringResource(R.string.locked_folder_open))
        }
    }
}

/** Sort field, direction and folders-first, in one pop-up. */
@Composable
private fun SortMenu(settings: BrowserSettings, onChange: ((BrowserSettings) -> BrowserSettings) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.browser_sort))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SortField.entries.forEach { field ->
                DropdownMenuItem(
                    text = { Text(stringResource(field.label)) },
                    leadingIcon = { RadioButton(selected = settings.sortField == field, onClick = null) },
                    onClick = { onChange { it.copy(sortField = field) } },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.browser_sort_ascending)) },
                leadingIcon = { Checkbox(checked = settings.ascending, onCheckedChange = null) },
                onClick = { onChange { it.copy(ascending = !it.ascending) } },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.browser_folders_first)) },
                leadingIcon = { Checkbox(checked = settings.foldersFirst, onCheckedChange = null) },
                onClick = { onChange { it.copy(foldersFirst = !it.foldersFirst) } },
            )
        }
    }
}

private val SortField.label: Int
    get() = when (this) {
        SortField.NAME -> R.string.sort_name
        SortField.EXTENSION -> R.string.sort_extension
        SortField.DATE -> R.string.sort_date
        SortField.SIZE -> R.string.sort_size
    }

/** Matches of a name search, in the browser's list or grid. */
@Composable
private fun SearchResults(
    search: BrowserSearch,
    state: BrowserUiState,
    onRefresh: () -> Unit,
    onEntryClick: (MediaEntry) -> Unit,
    entryActions: (MediaEntry) -> List<RowAction>,
    loadThumbnail: suspend (MediaEntry) -> Bitmap?,
) {
    Column {
        if (search.truncated) {
            Text(
                text = stringResource(R.string.browser_search_truncated, MAX_SEARCH_RESULTS, MAX_SEARCH_DEPTH),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        BrowserContent(
            // The refresh indicator doubles as progress while subfolders are searched.
            state = state.copy(entries = search.results, isLoading = state.isLoading || search.inProgress),
            selectedPath = null,
            onRefresh = onRefresh,
            onEntryClick = onEntryClick,
            entryActions = entryActions,
            loadThumbnail = loadThumbnail,
            showLocation = search.includeSubfolders,
            emptyMessage = stringResource(R.string.browser_search_empty),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchTopBar(
    search: BrowserSearch,
    onQueryChange: (String) -> Unit,
    onSubfoldersChange: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.browser_search_close))
            }
        },
        title = {
            val remote = rememberRemoteTextField()
            TextField(
                value = search.query,
                onValueChange = onQueryChange,
                readOnly = remote.readOnly,
                placeholder = { Text(stringResource(R.string.browser_search_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                trailingIcon = {
                    if (search.query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_clear))
                        }
                    }
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).then(remote.modifier),
            )
        },
        actions = {
            FilterChip(
                selected = search.includeSubfolders,
                onClick = { onSubfoldersChange(!search.includeSubfolders) },
                label = { Text(stringResource(R.string.browser_search_subfolders)) },
                modifier = Modifier.padding(end = 8.dp),
            )
        },
    )
}

/** Where [entryPath] sits below [folder], e.g. "드라마/시즌1"; null when directly inside it. */
private fun relativeFolder(folder: String, entryPath: String): String? {
    val parent = parentPath(entryPath) ?: return null
    val prefix = folder.trimEnd('/')
    return parent.removePrefix(prefix).trim('/').ifEmpty { null }
}

/** The ".." entry: one folder up, labelled with that folder's name. */
data class ParentEntry(val label: String, val onOpen: () -> Unit)

@Composable
private fun ParentRow(entry: ParentEntry) {
    ListItem(
        headlineContent = { Text(PARENT_NAME) },
        supportingContent = { Text(entry.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Icon(Icons.Outlined.DriveFolderUpload, contentDescription = stringResource(R.string.browser_parent_folder)) },
        modifier = Modifier.clickable(onClick = entry.onOpen),
    )
}

/**
 * The folders from the source root down to the current one; tapping one goes
 * straight there. Scrolled to the end so the current folder stays in view.
 */
@Composable
private fun PathBar(rootName: String, path: String, onNavigateTo: (String) -> Unit) {
    val segments = remember(rootName, path) { listOf(PathSegment(rootName, "/")) + pathSegments(path) }
    val scroll = rememberScrollState()
    LaunchedEffect(path, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = 8.dp)
            .height(PATH_BAR_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        segments.forEachIndexed { index, segment ->
            val isCurrent = index == segments.lastIndex
            if (index > 0) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                text = segment.name,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (isCurrent) FontWeight.SemiBold else null,
                color = if (isCurrent) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .clickable(enabled = !isCurrent, role = Role.Button) { onNavigateTo(segment.path) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

/** The name shown for the folder at [path]: its own name, or the source's at the root. */
@Composable
private fun folderLabel(state: BrowserUiState, path: String): String =
    pathSegments(path).lastOrNull()?.name ?: state.source?.displayName().orEmpty()

private const val PARENT_NAME = ".."
private val PATH_BAR_HEIGHT = 40.dp

/**
 * Opens the "All files access" toggle for this app. Some vendor ROMs lack the
 * per-app screen, so fall back to the global list, then to app details.
 */
private fun openAllFilesAccessSettings(context: Context) {
    val packageUri = "package:${context.packageName}".toUri()
    val candidates = buildList {
        // "All files access" exists from Android 11; Android 10 uses legacy storage.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            add(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri))
            add(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
        add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
    }
    for (intent in candidates) {
        try {
            context.startActivity(intent)
            return
        } catch (e: ActivityNotFoundException) {
            continue
        }
    }
}

/**
 * Shows [content] once the app may read arbitrary folders: "All files access"
 * on Android 11+, the legacy storage permission on Android 10.
 */
@Composable
private fun StoragePermissionGate(onGranted: () -> Unit, content: @Composable () -> Unit) {
    val context = LocalContext.current
    fun isGranted() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

    var granted by remember { mutableStateOf(isGranted()) }
    val legacyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (ok) onGranted()
    }
    LifecycleResumeEffect(Unit) {
        val now = isGranted()
        if (now && !granted) onGranted()
        granted = now
        onPauseOrDispose {}
    }

    if (granted) {
        content()
    } else {
        CenteredMessage(stringResource(R.string.storage_permission_rationale)) {
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        openAllFilesAccessSettings(context)
                    } else {
                        legacyLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                    }
                },
            ) { Text(stringResource(R.string.action_grant)) }
        }
    }
}

