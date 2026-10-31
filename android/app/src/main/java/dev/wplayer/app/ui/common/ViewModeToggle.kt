package dev.wplayer.app.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.wplayer.app.R
import dev.wplayer.app.data.model.BrowserViewMode

/** Switches between the thumbnail grid and the list; one setting shared by every list screen. */
@Composable
fun ViewModeToggle(mode: BrowserViewMode, onChange: (BrowserViewMode) -> Unit) {
    val grid = mode == BrowserViewMode.GRID
    IconButton(onClick = { onChange(if (grid) BrowserViewMode.LIST else BrowserViewMode.GRID) }) {
        Icon(
            imageVector = if (grid) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.GridView,
            contentDescription = stringResource(if (grid) R.string.browser_view_list else R.string.browser_view_grid),
        )
    }
}
