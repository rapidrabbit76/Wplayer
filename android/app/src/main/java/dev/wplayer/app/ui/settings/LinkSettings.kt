package dev.wplayer.app.ui.settings

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wplayer.app.R
import dev.wplayer.app.data.SettingsRepository
import dev.wplayer.app.data.link.IndexCheck
import dev.wplayer.app.data.link.LinkPluginManifest
import dev.wplayer.app.data.link.LinkPluginRepository
import dev.wplayer.app.data.link.PluginOffer
import dev.wplayer.app.data.link.PluginStatus
import dev.wplayer.app.data.link.pluginStatuses
import dev.wplayer.app.data.model.LinkSettings
import dev.wplayer.app.data.reason
import dev.wplayer.app.ui.common.BackTopAppBar
import dev.wplayer.app.ui.common.ConfirmDialog
import dev.wplayer.app.ui.common.RemoteOutlinedTextField
import dev.wplayer.app.ui.common.SwitchRow
import dev.wplayer.app.ui.common.readableWidth
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The outcome of the last install, shown under the buttons. */
sealed interface PluginNotice {
    data class Installed(val name: String, val version: String) : PluginNotice
    data class Failed(val reason: String) : PluginNotice
}

data class LinkSettingsUiState(
    val config: LinkSettings = LinkSettings(),
    val installed: List<LinkPluginManifest> = emptyList(),
    val index: IndexCheck = IndexCheck.Idle,
    /** By plugin id, for installed plugins and the index's offers. */
    val statuses: Map<String, PluginStatus> = emptyMap(),
    val notice: PluginNotice? = null,
) {
    /** What the index offers that is not installed, by name. */
    val available: List<PluginStatus> get() {
        val installedIds = installed.map { it.id }.toSet()
        return statuses.filterKeys { it !in installedIds }.values.sortedBy { it.offer?.manifest?.name?.lowercase() }
    }
}

private val PluginStatus.offer: PluginOffer?
    get() = when (this) {
        PluginStatus.UpToDate -> null
        is PluginStatus.UpdateAvailable -> offer
        is PluginStatus.NeedsNewerApp -> offer
        is PluginStatus.NotInstalled -> offer
    }

@HiltViewModel
class LinkSettingsViewModel @Inject constructor(
    private val plugins: LinkPluginRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val notice = MutableStateFlow<PluginNotice?>(null)

    val uiState: StateFlow<LinkSettingsUiState> = combine(settings.settings, plugins.installed, plugins.index, notice) { appSettings, installed, index, notice ->
        val offers = (index as? IndexCheck.Checked)?.offers.orEmpty()
        LinkSettingsUiState(appSettings.links, installed, index, pluginStatuses(installed, offers, plugins.hostApi), notice)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LinkSettingsUiState(config = settings.current.value.links))

    fun setIndexUrl(url: String) = updateLinks { it.copy(indexUrl = url.trim()) }

    fun setAutoUpdate(enabled: Boolean) = updateLinks { it.copy(autoUpdate = enabled) }

    fun setMaxHeight(height: Int?) = updateLinks { it.copy(maxHeight = height) }

    fun check() = plugins.check()

    fun install(offer: PluginOffer) = installing { plugins.install(offer) }

    fun installFile(uri: Uri) = installing { plugins.install(uri) }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { plugins.setEnabled(id, enabled) }
    }

    fun remove(id: String) {
        viewModelScope.launch { plugins.remove(id) }
    }

    private fun installing(install: suspend () -> LinkPluginManifest) {
        notice.value = null
        viewModelScope.launch {
            notice.value = try {
                install().let { PluginNotice.Installed(it.name, it.version.toString()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PluginNotice.Failed(e.reason())
            }
        }
    }

    private fun updateLinks(transform: (LinkSettings) -> LinkSettings) {
        viewModelScope.launch { settings.update { it.copy(links = transform(it.links)) } }
    }
}

/** Settings > Links: where plugins come from, how links play, and the installed and offered plugins. */
@Composable
fun LinkSettingsScreen(onBack: (() -> Unit)?, viewModel: LinkSettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var removing by remember { mutableStateOf<LinkPluginManifest?>(null) }
    Scaffold(
        topBar = { BackTopAppBar(stringResource(R.string.settings_links), onBack) },
    ) { padding ->
        LazyColumn(modifier = Modifier.readableWidth(), contentPadding = padding) {
            item { IndexUrlField(state.config.indexUrl, viewModel::setIndexUrl, onDone = viewModel::check) }
            item {
                SwitchRow(
                    title = stringResource(R.string.links_auto_update),
                    checked = state.config.autoUpdate,
                    onCheckedChange = viewModel::setAutoUpdate,
                    supporting = stringResource(R.string.links_auto_update_hint),
                )
            }
            item {
                ChoiceRow(R.string.links_max_height, MAX_HEIGHTS, state.config.maxHeight, { height ->
                    height?.let { stringResource(R.string.links_lines, it) } ?: stringResource(R.string.links_highest)
                }, viewModel::setMaxHeight)
            }
            item { PluginActions(state, viewModel) }
            item { SectionHeader(R.string.links_installed) }
            if (state.installed.isEmpty()) {
                item { ListItem(headlineContent = { Text(stringResource(R.string.links_none_installed)) }) }
            }
            items(state.installed, key = { "installed-${it.id}" }) { plugin ->
                InstalledPlugin(
                    plugin = plugin,
                    enabled = plugin.id !in state.config.disabledPlugins,
                    status = state.statuses[plugin.id],
                    onEnabledChange = { viewModel.setEnabled(plugin.id, it) },
                    onUpdate = viewModel::install,
                    onRemove = { removing = plugin },
                )
            }
            if (state.available.isNotEmpty()) {
                item { SectionHeader(R.string.links_available) }
                items(state.available, key = { "offered-${it.offer?.manifest?.id}" }) { status ->
                    OfferedPlugin(status, onInstall = viewModel::install)
                }
            }
        }
    }
    removing?.let { plugin ->
        ConfirmDialog(
            text = stringResource(R.string.links_remove_confirm, plugin.name),
            onConfirm = {
                removing = null
                viewModel.remove(plugin.id)
            },
            onDismiss = { removing = null },
        )
    }
}

@Composable
private fun IndexUrlField(saved: String, onChange: (String) -> Unit, onDone: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(saved) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        RemoteOutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                onChange(it)
            },
            label = stringResource(R.string.links_index_url),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.links_index_url_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PluginActions(state: LinkSettingsUiState, viewModel: LinkSettingsViewModel) {
    var noPicker by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::installFile) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            // Kept enabled while busy: a disabled button loses focus, which a remote then finds elsewhere.
            Button(onClick = { if (state.index != IndexCheck.Checking) viewModel.check() }) { Text(stringResource(R.string.links_check)) }
            OutlinedButton(
                onClick = {
                    try {
                        // Zip files often arrive without a zip MIME type, so any file can be picked; installing checks it.
                        picker.launch(arrayOf("*/*"))
                    } catch (_: ActivityNotFoundException) {
                        noPicker = true
                    }
                },
            ) { Text(stringResource(R.string.links_install_file)) }
        }
        val status = when (val index = state.index) {
            IndexCheck.Idle -> if (state.config.indexUrl.isBlank()) stringResource(R.string.links_no_index) else null
            IndexCheck.Checking -> stringResource(R.string.update_checking)
            is IndexCheck.Checked -> stringResource(R.string.links_checked)
            is IndexCheck.Failed -> stringResource(R.string.update_failed, index.reason)
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (noPicker) Text(stringResource(R.string.links_no_picker), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        when (val notice = state.notice) {
            is PluginNotice.Installed -> Text(stringResource(R.string.links_installed_notice, notice.name, notice.version), style = MaterialTheme.typography.bodyMedium)
            is PluginNotice.Failed -> Text(
                stringResource(R.string.links_install_failed, notice.reason),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            null -> Unit
        }
    }
}

/** The switch row turns the plugin on and off; its buttons sit below it, so a remote reaches each. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InstalledPlugin(
    plugin: LinkPluginManifest,
    enabled: Boolean,
    status: PluginStatus?,
    onEnabledChange: (Boolean) -> Unit,
    onUpdate: (PluginOffer) -> Unit,
    onRemove: () -> Unit,
) {
    Column {
        SwitchRow(
            title = plugin.name,
            checked = enabled,
            onCheckedChange = onEnabledChange,
            supporting = listOf(stringResource(R.string.links_version, plugin.version.toString()), plugin.description)
                .filter { it.isNotBlank() }
                .joinToString(" · "),
        )
        FlowRow(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            when (status) {
                is PluginStatus.UpdateAvailable -> Button(onClick = { onUpdate(status.offer) }) {
                    Text(stringResource(R.string.links_update, status.offer.manifest.version.toString()))
                }
                is PluginStatus.NeedsNewerApp -> Text(
                    stringResource(R.string.links_update_needs_newer_app, status.offer.manifest.version.toString()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Unit
            }
            OutlinedButton(onClick = onRemove) { Text(stringResource(R.string.action_delete)) }
        }
    }
}

@Composable
private fun OfferedPlugin(status: PluginStatus, onInstall: (PluginOffer) -> Unit) {
    val offer = status.offer ?: return
    val manifest = offer.manifest
    ListItem(
        headlineContent = { Text(manifest.name) },
        supportingContent = {
            Text(
                listOf(stringResource(R.string.links_version, manifest.version.toString()), manifest.description)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
            )
        },
        trailingContent = {
            if (status is PluginStatus.NeedsNewerApp) {
                Text(stringResource(R.string.links_needs_newer_app), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Button(onClick = { onInstall(offer) }) { Text(stringResource(R.string.links_install)) }
            }
        },
    )
}

/** The quality limits offered; null plays the plugin's first choice. */
private val MAX_HEIGHTS = listOf(null, 1080, 720, 480)
