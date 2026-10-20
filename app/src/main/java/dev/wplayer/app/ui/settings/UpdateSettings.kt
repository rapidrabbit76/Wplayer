package dev.wplayer.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wplayer.app.R
import dev.wplayer.app.data.SettingsRepository
import dev.wplayer.app.data.model.UpdateSettings
import dev.wplayer.app.data.update.ReleaseManifest
import dev.wplayer.app.data.update.UpdateRepository
import dev.wplayer.app.data.update.UpdateState
import dev.wplayer.app.ui.common.readableWidth
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val updates: UpdateRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    val state: StateFlow<UpdateState> = updates.state
    val config: StateFlow<UpdateSettings> = settings.settings.map { it.update }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), settings.current.value.update)
    val installedVersion: String = updates.installedVersionName

    fun check() = updates.check()

    fun install(release: ReleaseManifest) = updates.install(release)

    fun installPermissionIntent() = updates.installPermissionIntent()

    fun setAutoCheck(enabled: Boolean) {
        viewModelScope.launch { settings.update { it.copy(update = it.update.copy(autoCheck = enabled)) } }
    }
}

/** Settings > Updates: the installed version, checking and installing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSettingsScreen(onBack: (() -> Unit)?, viewModel: UpdateViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_updates)) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.readableWidth(), contentPadding = padding) {
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.update_current_version)) },
                    supportingContent = { Text(viewModel.installedVersion) },
                )
            }
            item { UpdateStatus(state, viewModel) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.update_auto_check)) },
                    supportingContent = { Text(stringResource(R.string.update_auto_check_hint)) },
                    trailingContent = { Switch(checked = config.autoCheck, onCheckedChange = viewModel::setAutoCheck) },
                )
            }
        }
    }
}

@Composable
private fun UpdateStatus(state: UpdateState, viewModel: UpdateViewModel) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = viewModel::check, enabled = state !is UpdateState.Checking && state !is UpdateState.Downloading) {
                Text(stringResource(R.string.update_check))
            }
            val message = when (state) {
                UpdateState.Checking -> stringResource(R.string.update_checking)
                UpdateState.UpToDate -> stringResource(R.string.update_up_to_date)
                is UpdateState.Failed -> stringResource(R.string.update_failed, state.reason)
                is UpdateState.Installing -> stringResource(R.string.update_installing)
                else -> null
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        when (state) {
            is UpdateState.Available -> {
                Text(stringResource(R.string.update_available, state.release.versionName), style = MaterialTheme.typography.titleMedium)
                if (state.release.notes.isNotBlank()) {
                    Text(state.release.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = { viewModel.install(state.release) }) { Text(stringResource(R.string.update_install)) }
            }
            is UpdateState.Downloading -> {
                Text(stringResource(R.string.update_downloading, (state.fraction * 100).toInt()))
                LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth())
            }
            UpdateState.NeedsInstallPermission -> {
                Text(stringResource(R.string.update_needs_permission), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { context.startActivity(viewModel.installPermissionIntent()) }) {
                    Text(stringResource(R.string.update_allow_install))
                }
            }
            else -> Unit
        }
    }
}

/** Offered at start-up when the automatic check finds a newer version. */
@Composable
fun UpdateDialog(
    state: UpdateState,
    onInstall: (ReleaseManifest) -> Unit,
    onAllowInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state) {
                    is UpdateState.Available -> {
                        Text(stringResource(R.string.update_available, state.release.versionName))
                        if (state.release.notes.isNotBlank()) {
                            Text(state.release.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    is UpdateState.Downloading -> {
                        Text(stringResource(R.string.update_downloading, (state.fraction * 100).toInt()))
                        LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth())
                    }
                    UpdateState.NeedsInstallPermission -> Text(stringResource(R.string.update_needs_permission))
                    else -> Unit
                }
            }
        },
        confirmButton = {
            when (state) {
                is UpdateState.Available -> TextButton(onClick = { onInstall(state.release) }) { Text(stringResource(R.string.update_install)) }
                UpdateState.NeedsInstallPermission -> TextButton(onClick = onAllowInstall) { Text(stringResource(R.string.update_allow_install)) }
                else -> Unit
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_later)) } },
    )
}
