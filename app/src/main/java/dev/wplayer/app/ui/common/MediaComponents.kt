package dev.wplayer.app.ui.common

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.wplayer.app.R
import dev.wplayer.app.data.model.MediaKind
import dev.wplayer.app.data.model.Playlist
import dev.wplayer.app.data.model.WatchProgress

/** An entry of a long-press menu. */
data class RowAction(val label: String, val onClick: () -> Unit)

/**
 * The long-press menu of a row, tile or card. A remote's select key, still held
 * when the menu opens, keeps repeating into the menu, and its release would pick
 * the first item; select presses only count once one starts inside the menu.
 */
@Composable
fun RowActionMenu(expanded: Boolean, onDismiss: () -> Unit, actions: List<RowAction>) {
    var selectArmed by remember(expanded) { mutableStateOf(false) }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.onPreviewKeyEvent { event ->
            if (!event.isSelect) return@onPreviewKeyEvent false
            if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) selectArmed = true
            !selectArmed
        },
    ) {
        actions.forEach { action ->
            DropdownMenuItem(
                text = { Text(action.label) },
                onClick = {
                    onDismiss()
                    action.onClick()
                },
            )
        }
    }
}

/** The keys Compose's clickable treats as a press: a remote's center, Enter and the keypad's Enter. */
private val KeyEvent.isSelect: Boolean
    get() = key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter

/**
 * A file or folder row: icon, name, a supporting line, watch progress, and a
 * long-press menu. [selected] highlights the folder open in the next column.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaRow(
    name: String,
    kind: MediaKind,
    supporting: String?,
    progress: WatchProgress?,
    selected: Boolean,
    actions: List<RowAction>,
    onClick: () -> Unit,
    /** For the clickable item itself, e.g. to give it focus. */
    modifier: Modifier = Modifier,
    /** Loads the video thumbnail shown instead of the icon; null shows the icon. */
    loadThumbnail: (suspend () -> Bitmap?)? = null,
    trailing: (@Composable () -> Unit)? = null,
    /** A locked folder, shown with a lock. */
    locked: Boolean = false,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        ListItem(
            headlineContent = { Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = if (supporting == null && progress == null) {
                null
            } else {
                {
                    Column {
                        supporting?.let { Text(it) }
                        progress?.let {
                            LinearProgressIndicator(
                                progress = { it.fraction },
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                drawStopIndicator = {},
                            )
                        }
                    }
                }
            },
            leadingContent = {
                if (loadThumbnail == null) {
                    Icon(if (locked) Icons.Outlined.Lock else kind.icon, contentDescription = null)
                } else {
                    Thumbnail(kind, loadThumbnail, Modifier.width(listThumbnailWidth).aspectRatio(16f / 9f))
                }
            },
            trailingContent = trailing,
            colors = if (selected) {
                ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            } else {
                ListItemDefaults.colors()
            },
            modifier = modifier.combinedClickable(
                onClick = onClick,
                onLongClick = if (actions.isEmpty()) null else ({ menuOpen = true }),
                onLongClickLabel = stringResource(R.string.action_more),
            ),
        )
        RowActionMenu(expanded = menuOpen, onDismiss = { menuOpen = false }, actions = actions)
    }
}

/**
 * A grid tile: a 16:9 thumbnail (or the kind's icon) with the watch progress
 * along its bottom edge, then the name. Same actions as [MediaRow].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaCard(
    name: String,
    kind: MediaKind,
    supporting: String?,
    progress: WatchProgress?,
    selected: Boolean,
    actions: List<RowAction>,
    onClick: () -> Unit,
    /** For the clickable item itself, e.g. to give it focus. */
    modifier: Modifier = Modifier,
    loadThumbnail: (suspend () -> Bitmap?)? = null,
    /** A locked folder, shown with a lock. */
    locked: Boolean = false,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Column(
            modifier = modifier
                .clip(MaterialTheme.shapes.medium)
                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = if (actions.isEmpty()) null else ({ menuOpen = true }),
                    onLongClickLabel = stringResource(R.string.action_more),
                )
                .padding(6.dp),
        ) {
            Box {
                Thumbnail(kind, loadThumbnail, Modifier.fillMaxWidth().aspectRatio(16f / 9f), iconSize = 40.dp, locked = locked)
                progress?.let {
                    LinearProgressIndicator(
                        progress = { it.fraction },
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
                        drawStopIndicator = {},
                    )
                }
            }
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
            )
            supporting?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
            }
        }
        RowActionMenu(expanded = menuOpen, onDismiss = { menuOpen = false }, actions = actions)
    }
}

/** A grid tile for a collection (a playlist, recently played): a large icon, name and a count. */
@Composable
fun CollectionCard(icon: ImageVector, title: String, supporting: String?, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
        )
        supporting?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 2.dp))
        }
    }
}

/** A thumbnail, showing the kind's icon until (or unless) the image loads. */
@Composable
fun Thumbnail(kind: MediaKind, load: (suspend () -> Bitmap?)?, modifier: Modifier, iconSize: Dp = 24.dp, locked: Boolean = false) {
    // Lists key items by file, so loading once per item is enough; the lambda itself changes on recomposition.
    val currentLoad by rememberUpdatedState(load)
    val image by produceState<ImageBitmap?>(initialValue = null) { value = currentLoad?.invoke()?.asImageBitmap() }
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        val loaded = image
        if (loaded != null) {
            Image(loaded, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(
                if (locked) Icons.Outlined.Lock else kind.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

val MediaKind.icon: ImageVector
    get() = when (this) {
        MediaKind.DIRECTORY -> Icons.Outlined.Folder
        MediaKind.AUDIO -> Icons.Outlined.MusicNote
        else -> Icons.Outlined.Movie
    }

/** Picks a playlist for [itemName], or names a new one. */
@Composable
fun AddToPlaylistDialog(
    itemName: String,
    playlists: List<Playlist>,
    onPick: (Long) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by rememberSaveable { mutableStateOf("") }
    val create = {
        if (newName.isNotBlank()) onCreate(newName.trim())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.playlist_add_title)) },
        text = {
            Column {
                Text(itemName, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                LazyColumn(Modifier.heightIn(max = 280.dp).padding(top = 8.dp)) {
                    items(playlists, key = { it.id }) { playlist ->
                        ListItem(
                            headlineContent = { Text(playlist.name) },
                            supportingContent = { Text(pluralStringResource(R.plurals.playlist_item_count, playlist.itemCount, playlist.itemCount)) },
                            leadingContent = { Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, contentDescription = null) },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                            modifier = Modifier.clickable { onPick(playlist.id) },
                        )
                    }
                }
                if (playlists.isNotEmpty()) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                RemoteOutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = stringResource(R.string.playlist_new_name),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { create() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = create, enabled = newName.isNotBlank()) { Text(stringResource(R.string.playlist_create_and_add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
fun ConfirmDialog(text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Asks for a playlist name; used for creating and renaming. */
@Composable
fun PlaylistNameDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            RemoteOutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = stringResource(R.string.playlist_name),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onConfirm(name.trim()) }),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
