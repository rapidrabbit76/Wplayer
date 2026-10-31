package dev.wplayer.app.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wplayer.app.R
import dev.wplayer.app.data.model.BrowserViewMode
import dev.wplayer.app.data.model.Playlist
import dev.wplayer.app.ui.common.CollectionCard
import dev.wplayer.app.ui.common.FAB_CLEARANCE
import dev.wplayer.app.ui.common.PlaylistNameDialog
import dev.wplayer.app.ui.common.ViewModeToggle
import dev.wplayer.app.ui.common.mediaGridCellMinWidth

/** The playlists tab: recently played, then the user's playlists. */
@Composable
fun LibraryScreen(
    onOpenRecent: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val viewMode by viewModel.viewMode.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    LibraryContent(playlists, viewMode, viewModel::setViewMode, onOpenRecent, onOpenPlaylist, onCreatePlaylist = { creating = true })
    if (creating) {
        PlaylistNameDialog(
            title = stringResource(R.string.playlist_new),
            initial = "",
            onConfirm = {
                viewModel.createPlaylist(it)
                creating = false
            },
            onDismiss = { creating = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryContent(
    playlists: List<Playlist>,
    viewMode: BrowserViewMode,
    onViewModeChange: (BrowserViewMode) -> Unit,
    onOpenRecent: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onCreatePlaylist: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library_title)) },
                actions = { ViewModeToggle(viewMode, onViewModeChange) },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreatePlaylist,
                icon = { Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, contentDescription = null) },
                text = { Text(stringResource(R.string.playlist_new)) },
            )
        },
    ) { padding ->
        val bottom = padding.calculateBottomPadding() + FAB_CLEARANCE
        val recentTitle = stringResource(R.string.recent_title)
        if (viewMode == BrowserViewMode.GRID) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(mediaGridCellMinWidth),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = padding.calculateTopPadding() + 12.dp, bottom = bottom),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "recent") { CollectionCard(Icons.Outlined.History, recentTitle, supporting = null, onClick = onOpenRecent) }
                gridItems(playlists, key = { it.id }) { playlist ->
                    CollectionCard(
                        icon = Icons.AutoMirrored.Outlined.PlaylistPlay,
                        title = playlist.name,
                        supporting = pluralStringResource(R.plurals.playlist_item_count, playlist.itemCount, playlist.itemCount),
                        onClick = { onOpenPlaylist(playlist.id) },
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = bottom),
            ) {
                item(key = "recent") {
                    ListItem(
                        headlineContent = { Text(recentTitle) },
                        leadingContent = { Icon(Icons.Outlined.History, contentDescription = null) },
                        modifier = Modifier.clickable(onClick = onOpenRecent),
                    )
                }
                items(playlists, key = { it.id }) { playlist ->
                    ListItem(
                        headlineContent = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(pluralStringResource(R.plurals.playlist_item_count, playlist.itemCount, playlist.itemCount)) },
                        leadingContent = { Icon(Icons.AutoMirrored.Outlined.PlaylistPlay, contentDescription = null) },
                        modifier = Modifier.clickable { onOpenPlaylist(playlist.id) },
                    )
                }
            }
        }
    }
}
