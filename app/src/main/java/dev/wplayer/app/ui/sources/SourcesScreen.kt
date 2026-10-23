package dev.wplayer.app.ui.sources

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wplayer.app.R
import dev.wplayer.app.data.model.MediaEntry
import dev.wplayer.app.data.model.MediaRef
import dev.wplayer.app.data.model.Source
import dev.wplayer.app.data.model.SourceProtocol
import dev.wplayer.app.ui.common.RowAction
import dev.wplayer.app.ui.common.RowActionMenu
import dev.wplayer.app.ui.common.Thumbnail
import dev.wplayer.app.ui.player.PlaybackRequest
import dev.wplayer.app.ui.player.formatTime

/** The first tab: unfinished videos to continue, then the sources as cards. */
@Composable
fun SourcesScreen(
    onOpenSource: (String) -> Unit,
    onAddSource: () -> Unit,
    onEditSource: (String) -> Unit,
    onPlay: (PlaybackRequest) -> Unit,
    onShowInFolder: (MediaRef) -> Unit,
    viewModel: SourcesViewModel = hiltViewModel(),
) {
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val continueWatching by viewModel.continueWatching.collectAsStateWithLifecycle()
    SourcesContent(
        sources = sources,
        continueWatching = continueWatching,
        onOpenSource = onOpenSource,
        onAddSource = onAddSource,
        onEditSource = onEditSource,
        onResume = { ref -> onPlay(PlaybackRequest.FromSource(ref.sourceKey, ref.path, ref.name)) },
        loadThumbnail = viewModel::thumbnail,
        continueActions = object : ContinueWatchingActions {
            override fun onShowInFolder(ref: MediaRef) = onShowInFolder(ref)
            override fun onForget(ref: MediaRef) = viewModel.forget(ref)
            override fun onClearAll() = viewModel.clearHistory()
            override fun onHide() = viewModel.hideContinueWatching()
        },
    )
}

/** What a long press on a continue-watching card offers. */
interface ContinueWatchingActions {
    fun onShowInFolder(ref: MediaRef)
    fun onForget(ref: MediaRef)
    fun onClearAll()
    fun onHide()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SourcesContent(
    sources: List<Source>,
    continueWatching: List<ContinueWatching>,
    onOpenSource: (String) -> Unit,
    onAddSource: () -> Unit,
    onEditSource: (String) -> Unit,
    onResume: (MediaRef) -> Unit,
    loadThumbnail: (suspend (MediaRef) -> Bitmap?)? = null,
    continueActions: ContinueWatchingActions? = null,
) {
    var confirmingClear by remember { mutableStateOf(false) }
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.sources_title)) }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddSource,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.source_add)) },
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(SOURCE_CARD_MIN_WIDTH),
            // Extra bottom space keeps the last cards clear of the FAB.
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 96.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (continueWatching.isNotEmpty()) {
                sectionTitle(R.string.continue_watching)
                item(span = { GridItemSpan(maxLineSpan) }) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(continueWatching, key = { it.ref.sourceKey + it.ref.path }) { item ->
                            ContinueCard(
                                item = item,
                                onClick = { onResume(item.ref) },
                                loadThumbnail = loadThumbnail,
                                menu = continueActions?.let { actions ->
                                    listOf(
                                        RowAction(stringResource(R.string.show_in_folder)) { actions.onShowInFolder(item.ref) },
                                        RowAction(stringResource(R.string.continue_forget)) { actions.onForget(item.ref) },
                                        RowAction(stringResource(R.string.continue_clear_all)) { confirmingClear = true },
                                        RowAction(stringResource(R.string.continue_hide)) { actions.onHide() },
                                    )
                                }.orEmpty(),
                            )
                        }
                    }
                }
            }
            sectionTitle(R.string.sources_title)
            items(sources, key = { it.key }) { source ->
                SourceCard(source, onOpen = { onOpenSource(source.key) }, onEdit = { onEditSource(source.key) })
            }
        }
    }
    if (confirmingClear && continueActions != null) {
        AlertDialog(
            onDismissRequest = { confirmingClear = false },
            text = { Text(stringResource(R.string.recent_clear_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingClear = false
                        continueActions.onClearAll()
                    },
                ) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { confirmingClear = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

private fun LazyGridScope.sectionTitle(title: Int) {
    item(span = { GridItemSpan(maxLineSpan) }) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/**
 * The card opens the source; its edit button sits beside the part that opens,
 * not inside it, so a remote can move to the button: focus never moves to a
 * button inside the focused card.
 */
@Composable
private fun SourceCard(source: Source, onOpen: () -> Unit, onEdit: () -> Unit) {
    OutlinedCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onOpen)
                    .padding(start = 16.dp, top = 16.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(44.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(source.protocol.icon, contentDescription = null) }
                }
                Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    Text(source.displayName(), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = source.summary(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (source.protocol != SourceProtocol.LOCAL) {
                IconButton(onClick = onEdit, modifier = Modifier.padding(end = 4.dp)) {
                    Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.action_edit))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContinueCard(
    item: ContinueWatching,
    onClick: () -> Unit,
    loadThumbnail: (suspend (MediaRef) -> Bitmap?)?,
    /** Shown on a long press. */
    menu: List<RowAction>,
) {
    val kind = MediaEntry.kindOf(item.ref.name, isDirectory = false)
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        OutlinedCard(
            modifier = Modifier
                .width(CONTINUE_CARD_WIDTH)
                .clip(CardDefaults.outlinedShape)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = if (menu.isEmpty()) null else ({ menuOpen = true }),
                    onLongClickLabel = stringResource(R.string.action_more),
                ),
        ) {
            Box {
                Thumbnail(kind, loadThumbnail?.let { load -> { load(item.ref) } }, Modifier.fillMaxWidth().aspectRatio(16f / 9f), iconSize = 40.dp)
                LinearProgressIndicator(
                    progress = { item.progress.fraction },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                    drawStopIndicator = {},
                )
            }
            Column(Modifier.padding(12.dp)) {
                Text(item.ref.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    text = stringResource(R.string.continue_remaining, formatTime(item.progress.durationSeconds - item.progress.positionSeconds)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        RowActionMenu(expanded = menuOpen, onDismiss = { menuOpen = false }, actions = menu)
    }
}

@Composable
fun Source.displayName(): String =
    if (protocol == SourceProtocol.LOCAL) stringResource(R.string.source_local_storage) else name

/** Protocol and host, e.g. "SMB · 192.168.0.10". */
@Composable
private fun Source.summary(): String =
    if (protocol == SourceProtocol.LOCAL) stringResource(R.string.source_local_summary) else "${protocol.label} · $host"

private val SourceProtocol.icon: ImageVector
    get() = when (this) {
        SourceProtocol.LOCAL -> Icons.Outlined.PhoneAndroid
        SourceProtocol.WEBDAV, SourceProtocol.WEBDAVS -> Icons.Outlined.Cloud
        else -> Icons.Outlined.Dns
    }

private val SOURCE_CARD_MIN_WIDTH = 300.dp
private val CONTINUE_CARD_WIDTH = 232.dp
