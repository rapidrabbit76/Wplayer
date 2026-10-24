package dev.wplayer.app.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wplayer.app.R
import dev.wplayer.app.data.SourceRepository
import dev.wplayer.app.data.model.MediaEntry
import dev.wplayer.app.data.model.MediaKind
import dev.wplayer.app.data.model.MediaRef
import dev.wplayer.app.data.model.mediaKey
import dev.wplayer.app.ui.common.AddToPlaylistDialog
import dev.wplayer.app.ui.common.BackTopAppBar
import dev.wplayer.app.ui.common.MediaCollection
import dev.wplayer.app.ui.common.MediaItem
import dev.wplayer.app.ui.common.PlaylistNameDialog
import dev.wplayer.app.ui.common.RowAction
import dev.wplayer.app.ui.common.ViewModeToggle
import dev.wplayer.app.ui.player.PlayQueue
import dev.wplayer.app.ui.player.PlaybackRequest

/**
 * A playlist. Files play through the playlist in order; folders work as
 * bookmarks and open in the browser.
 */
@Composable
fun PlaylistScreen(
    viewModel: PlaylistViewModel,
    onPlay: (PlaybackRequest) -> Unit,
    onOpenFolder: (sourceKey: String, path: String) -> Unit,
    onShowInFolder: (MediaRef) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    val queue = PlayQueue.Playlist(viewModel.playlistId)
    fun play(ref: MediaRef) = onPlay(PlaybackRequest.FromSource(ref.sourceKey, ref.path, ref.name, queue))

    LibraryLayout(
        title = state.name.orEmpty(),
        onBack = onBack,
        actions = {
            ViewModeToggle(state.viewMode, viewModel::setViewMode)
            val firstFile = state.items.firstOrNull { !it.ref.isDirectory }
            IconButton(onClick = { firstFile?.let { play(it.ref) } }, enabled = firstFile != null) {
                Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.playlist_play_all))
            }
            IconButton(onClick = { renaming = true }) {
                Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.playlist_rename))
            }
            IconButton(onClick = { confirmingDelete = true }) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.playlist_delete))
            }
        },
    ) {
        if (state.items.isEmpty()) {
            EmptyMessage(stringResource(R.string.playlist_empty))
        } else {
            val showInFolder = stringResource(R.string.show_in_folder)
            val moveUp = stringResource(R.string.playlist_move_up)
            val moveDown = stringResource(R.string.playlist_move_down)
            val remove = stringResource(R.string.playlist_remove_item)
            MediaCollection(state.viewMode, state.items, key = { it.id }) { item, grid ->
                MediaItem(
                    grid = grid,
                    name = item.ref.name,
                    kind = if (item.ref.isDirectory) MediaKind.DIRECTORY else MediaEntry.kindOf(item.ref.name, isDirectory = false),
                    supporting = sourceLabel(item.ref.sourceKey, state.sourceNames) + " · " + item.ref.parentPath(),
                    progress = state.progress[item.id],
                    actions = listOf(
                        RowAction(showInFolder) { onShowInFolder(item.ref) },
                        RowAction(moveUp) { viewModel.move(item, -1) },
                        RowAction(moveDown) { viewModel.move(item, 1) },
                        RowAction(remove) { viewModel.remove(item) },
                    ),
                    onClick = {
                        if (item.ref.isDirectory) onOpenFolder(item.ref.sourceKey, item.ref.path) else play(item.ref)
                    },
                    loadThumbnail = if (state.showThumbnails && item.ref.isVideo) ({ viewModel.thumbnail(item.ref) }) else null,
                )
            }
        }
    }

    if (renaming) {
        PlaylistNameDialog(
            title = stringResource(R.string.playlist_rename),
            initial = state.name.orEmpty(),
            onConfirm = {
                viewModel.rename(it)
                renaming = false
            },
            onDismiss = { renaming = false },
        )
    }
    if (confirmingDelete) {
        ConfirmDialog(
            text = stringResource(R.string.playlist_delete_confirm, state.name.orEmpty()),
            onConfirm = {
                confirmingDelete = false
                viewModel.delete()
                onBack()
            },
            onDismiss = { confirmingDelete = false },
        )
    }
}

@Composable
fun RecentScreen(
    viewModel: RecentViewModel,
    onPlay: (PlaybackRequest) -> Unit,
    onShowInFolder: (MediaRef) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var addingToPlaylist by remember { mutableStateOf<MediaRef?>(null) }
    var confirmingClear by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    state.message?.let { message ->
        val text = stringResource(message.text)
        LaunchedEffect(message) {
            snackbar.showSnackbar(text)
            viewModel.messageShown()
        }
    }

    LibraryLayout(
        title = stringResource(R.string.recent_title),
        onBack = onBack,
        snackbar = snackbar,
        actions = {
            ViewModeToggle(state.viewMode, viewModel::setViewMode)
            IconButton(onClick = { confirmingClear = true }, enabled = state.items.isNotEmpty()) {
                Icon(Icons.Outlined.DeleteSweep, contentDescription = stringResource(R.string.recent_clear))
            }
        },
    ) {
        if (state.items.isEmpty()) {
            EmptyMessage(stringResource(R.string.recent_empty))
        } else {
            val showInFolder = stringResource(R.string.show_in_folder)
            val addLabel = stringResource(R.string.playlist_add_title)
            val forgetLabel = stringResource(R.string.browser_forget_history)
            MediaCollection(state.viewMode, state.items, key = { it.ref.sourceKey + it.ref.path }) { recent, grid ->
                val ref = recent.ref
                MediaItem(
                    grid = grid,
                    name = ref.name,
                    kind = MediaEntry.kindOf(ref.name, isDirectory = false),
                    supporting = sourceLabel(ref.sourceKey, state.sourceNames) + " · " + ref.parentPath(),
                    progress = state.progress[mediaKey(ref.sourceKey, ref.path)],
                    actions = listOf(
                        RowAction(showInFolder) { onShowInFolder(ref) },
                        RowAction(addLabel) { addingToPlaylist = ref },
                        RowAction(forgetLabel) { viewModel.forget(ref) },
                    ),
                    onClick = { onPlay(PlaybackRequest.FromSource(ref.sourceKey, ref.path, ref.name)) },
                    loadThumbnail = if (state.showThumbnails && ref.isVideo) ({ viewModel.thumbnail(ref) }) else null,
                )
            }
        }
    }

    addingToPlaylist?.let { ref ->
        AddToPlaylistDialog(
            itemName = ref.name,
            playlists = state.playlists,
            onPick = {
                viewModel.addToPlaylist(ref, it)
                addingToPlaylist = null
            },
            onCreate = {
                viewModel.addToNewPlaylist(ref, it)
                addingToPlaylist = null
            },
            onDismiss = { addingToPlaylist = null },
        )
    }
    if (confirmingClear) {
        ConfirmDialog(
            text = stringResource(R.string.recent_clear_confirm),
            onConfirm = {
                confirmingClear = false
                viewModel.clearAll()
            },
            onDismiss = { confirmingClear = false },
        )
    }
}

@Composable
private fun LibraryLayout(
    title: String,
    onBack: () -> Unit,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    actions: @Composable RowScope.() -> Unit,
    content: @Composable () -> Unit,
) {
    Scaffold(
        topBar = { BackTopAppBar(title, onBack, actions) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) { content() }
    }
}

@Composable
private fun EmptyMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ConfirmDialog(text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** The source's name; device storage has no stored name. */
@Composable
private fun sourceLabel(sourceKey: String, names: Map<String, String>): String =
    if (sourceKey == SourceRepository.LOCAL_KEY) {
        stringResource(R.string.source_local_storage)
    } else {
        names[sourceKey] ?: stringResource(R.string.source_missing)
    }

private val MediaRef.isVideo: Boolean get() = MediaEntry.kindOf(name, isDirectory) == MediaKind.VIDEO

private fun MediaRef.parentPath(): String = path.substringBeforeLast('/', "").ifEmpty { "/" }
