package dev.wplayer.app.ui.downloads

import android.text.format.Formatter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wplayer.app.R
import dev.wplayer.app.data.model.BrowserViewMode
import dev.wplayer.app.data.model.Download
import dev.wplayer.app.data.model.DownloadStatus
import dev.wplayer.app.data.model.WatchProgress
import dev.wplayer.app.ui.common.CenteredMessage
import dev.wplayer.app.ui.common.MediaCollection
import dev.wplayer.app.ui.common.MediaItem
import dev.wplayer.app.ui.common.RowAction
import dev.wplayer.app.ui.common.ViewModeToggle
import dev.wplayer.app.ui.player.PlaybackRequest

/** Actions on one download, so the content stays a plain function of the list. */
interface DownloadActions {
    fun onPlay(download: Download)
    fun onCancel(download: Download)
    fun onRetry(download: Download)
    fun onRemove(download: Download, deleteFile: Boolean)
    fun onClearFinished()
    fun onViewModeChange(mode: BrowserViewMode)
}

@Composable
fun DownloadsScreen(onPlay: (PlaybackRequest) -> Unit, viewModel: DownloadsViewModel = hiltViewModel()) {
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val viewMode by viewModel.viewMode.collectAsStateWithLifecycle()
    val actions = remember(viewModel, onPlay) {
        object : DownloadActions {
            override fun onPlay(download: Download) {
                viewModel.playbackRequest(download)?.let(onPlay)
            }
            override fun onCancel(download: Download) = viewModel.cancel(download)
            override fun onRetry(download: Download) = viewModel.retry(download)
            override fun onRemove(download: Download, deleteFile: Boolean) = viewModel.remove(download, deleteFile)
            override fun onClearFinished() = viewModel.clearFinished()
            override fun onViewModeChange(mode: BrowserViewMode) = viewModel.setViewMode(mode)
        }
    }
    DownloadsContent(downloads, viewMode, viewModel.folderLabel, actions)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadsContent(downloads: List<Download>, viewMode: BrowserViewMode, folderLabel: String, actions: DownloadActions) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.downloads_title)) },
                actions = {
                    ViewModeToggle(viewMode, actions::onViewModeChange)
                    IconButton(onClick = actions::onClearFinished, enabled = downloads.any { it.status == DownloadStatus.DONE }) {
                        Icon(Icons.Outlined.DeleteSweep, contentDescription = stringResource(R.string.downloads_clear_finished))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (downloads.isEmpty()) {
                CenteredMessage(stringResource(R.string.downloads_empty, folderLabel))
            } else {
                MediaCollection(viewMode, downloads, key = { it.id }) { download, grid -> DownloadItem(download, grid, actions) }
            }
        }
    }
}

@Composable
private fun DownloadItem(download: Download, grid: Boolean, actions: DownloadActions) {
    val active = download.status == DownloadStatus.RUNNING || download.status == DownloadStatus.QUEUED
    val failed = download.status == DownloadStatus.FAILED || download.status == DownloadStatus.CANCELLED
    val cancelLabel = stringResource(R.string.downloads_cancel)
    val retryLabel = stringResource(R.string.downloads_retry)
    val removeLabel = stringResource(R.string.downloads_remove)
    val deleteLabel = stringResource(R.string.downloads_delete_file)
    MediaItem(
        grid = grid,
        name = download.ref.name,
        kind = download.ref.kind,
        supporting = statusText(download),
        // The bar shows download progress here, not watch progress.
        progress = if (active) WatchProgress(download.fraction.toDouble(), 1.0) else null,
        actions = buildList {
            // The grid has no room for the buttons the list shows, so they are in the menu as well.
            if (grid && active) add(RowAction(cancelLabel) { actions.onCancel(download) })
            if (grid && failed) add(RowAction(retryLabel) { actions.onRetry(download) })
            add(RowAction(removeLabel) { actions.onRemove(download, deleteFile = false) })
            if (download.status == DownloadStatus.DONE) add(RowAction(deleteLabel) { actions.onRemove(download, deleteFile = true) })
        },
        onClick = { if (download.status == DownloadStatus.DONE) actions.onPlay(download) },
        trailing = when {
            active -> {
                {
                    IconButton(onClick = { actions.onCancel(download) }) {
                        Icon(Icons.Outlined.Close, contentDescription = cancelLabel)
                    }
                }
            }
            failed -> {
                {
                    IconButton(onClick = { actions.onRetry(download) }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = retryLabel)
                    }
                }
            }
            else -> null
        },
    )
}

@Composable
private fun statusText(download: Download): String {
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    return when (download.status) {
        DownloadStatus.QUEUED -> stringResource(R.string.downloads_queued)
        DownloadStatus.RUNNING -> download.totalBytes?.let { total ->
            stringResource(R.string.downloads_running, size(download.downloadedBytes), size(total), (download.fraction * 100).toInt())
        } ?: size(download.downloadedBytes)
        DownloadStatus.DONE -> stringResource(R.string.downloads_done, size(download.totalBytes ?: download.downloadedBytes))
        DownloadStatus.FAILED -> stringResource(R.string.downloads_failed, download.error.orEmpty())
        DownloadStatus.CANCELLED -> stringResource(R.string.downloads_cancelled)
    }
}
