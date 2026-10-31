package dev.wplayer.app.ui.common

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.wplayer.app.data.model.BrowserViewMode
import dev.wplayer.app.data.model.MediaKind
import dev.wplayer.app.data.model.WatchProgress

/**
 * [items] as a thumbnail grid or a list, per [viewMode], filling the width.
 * [item] receives whether it is drawn in the grid.
 */
@Composable
fun <T> MediaCollection(
    viewMode: BrowserViewMode,
    items: List<T>,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    item: @Composable (T, grid: Boolean) -> Unit,
) {
    if (viewMode == BrowserViewMode.GRID) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(mediaGridCellMinWidth),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = modifier.fillMaxSize(),
        ) {
            gridItems(items, key = key) { item(it, true) }
        }
    } else {
        LazyColumn(modifier.fillMaxSize()) {
            items(items, key = key) { item(it, false) }
        }
    }
}

/** A [MediaCard] in the grid, a [MediaRow] in the list. [trailing] shows in the list only. */
@Composable
fun MediaItem(
    grid: Boolean,
    name: String,
    kind: MediaKind,
    supporting: String?,
    progress: WatchProgress?,
    actions: List<RowAction>,
    onClick: () -> Unit,
    loadThumbnail: (suspend () -> Bitmap?)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    if (grid) {
        MediaCard(name, kind, supporting, progress, selected = false, actions = actions, onClick = onClick, loadThumbnail = loadThumbnail)
    } else {
        MediaRow(name, kind, supporting, progress, selected = false, actions = actions, onClick = onClick, loadThumbnail = loadThumbnail, trailing = trailing)
    }
}
