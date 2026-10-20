package dev.wplayer.app.ui.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wplayer.app.R
import dev.wplayer.app.data.model.SourceProtocol
import dev.wplayer.app.ui.common.message

private val EDITABLE_PROTOCOLS = SourceProtocol.entries - SourceProtocol.LOCAL

/** User intents from the source editor form. */
internal interface SourceEditorActions {
    fun onProtocolChange(protocol: SourceProtocol)
    fun onNameChange(name: String)
    fun onHostChange(host: String)
    fun onPortChange(port: String)
    fun onPathChange(path: String)
    fun onUsernameChange(username: String)
    fun onPasswordChange(password: String)
    fun onAllowUntrustedChange(allow: Boolean)
    fun onTest()
    fun onSave()
    fun onDelete()
    fun onDone()
}

@Composable
fun SourceEditorScreen(
    viewModel: SourceEditorViewModel,
    onDone: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.isFinished) {
        if (state.isFinished) onDone()
    }
    val actions = remember(viewModel) {
        object : SourceEditorActions {
            override fun onProtocolChange(protocol: SourceProtocol) = viewModel.onProtocolChange(protocol)
            override fun onNameChange(name: String) = viewModel.onNameChange(name)
            override fun onHostChange(host: String) = viewModel.onHostChange(host)
            override fun onPortChange(port: String) = viewModel.onPortChange(port)
            override fun onPathChange(path: String) = viewModel.onPathChange(path)
            override fun onUsernameChange(username: String) = viewModel.onUsernameChange(username)
            override fun onPasswordChange(password: String) = viewModel.onPasswordChange(password)
            override fun onAllowUntrustedChange(allow: Boolean) = viewModel.onAllowUntrustedChange(allow)
            override fun onTest() = viewModel.testConnection()
            override fun onSave() = viewModel.save()
            override fun onDelete() = viewModel.delete()
            override fun onDone() = onDone()
        }
    }
    SourceEditorContent(state, actions)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SourceEditorContent(state: SourceEditorUiState, actions: SourceEditorActions) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (state.isNew) R.string.source_add else R.string.source_edit)) },
                navigationIcon = {
                    IconButton(onClick = actions::onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (!state.isNew) {
                        IconButton(onClick = actions::onDelete) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.source_delete))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
        Column(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ProtocolField(state.protocol, actions::onProtocolChange)
            Text(
                text = stringResource(state.protocol.hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Field(R.string.source_name, state.name, actions::onNameChange)
            Field(R.string.source_host, state.host, actions::onHostChange, keyboardType = KeyboardType.Uri)
            Field(R.string.source_port, state.port, actions::onPortChange, keyboardType = KeyboardType.Number)
            Field(R.string.source_path, state.path, actions::onPathChange, keyboardType = KeyboardType.Uri)
            Field(R.string.source_username, state.username, actions::onUsernameChange, autoCorrect = false)
            OutlinedTextField(
                value = state.password,
                onValueChange = actions::onPasswordChange,
                label = {
                    Text(stringResource(if (state.hasStoredPassword) R.string.source_password_kept else R.string.source_password))
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.protocol.usesTls) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.source_allow_untrusted)) },
                    supportingContent = { Text(stringResource(R.string.source_allow_untrusted_hint)) },
                    trailingContent = {
                        Switch(checked = state.allowUntrustedCertificates, onCheckedChange = actions::onAllowUntrustedChange)
                    },
                )
            }

            state.validationError?.let {
                Text(stringResource(it), color = MaterialTheme.colorScheme.error)
            }
            state.actionError?.let {
                Text(it.message(), color = MaterialTheme.colorScheme.error)
            }
            ConnectionTestResult(state.connectionTest)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                OutlinedButton(
                    onClick = actions::onTest,
                    enabled = state.connectionTest != ConnectionTest.Running,
                ) { Text(stringResource(R.string.source_test)) }
                Button(onClick = actions::onSave, enabled = !state.isSaving) {
                    Text(stringResource(R.string.source_save))
                }
            }
        }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProtocolField(selected: SourceProtocol, onSelect: (SourceProtocol) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.source_protocol)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            EDITABLE_PROTOCOLS.forEach { protocol ->
                DropdownMenuItem(
                    text = { Text(protocol.label) },
                    onClick = {
                        onSelect(protocol)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun Field(
    label: Int,
    value: String,
    onChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    autoCorrect: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        // Next moves focus down the form; addresses and user names must not be autocorrected.
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = ImeAction.Next,
            autoCorrectEnabled = autoCorrect && keyboardType == KeyboardType.Text,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ConnectionTestResult(test: ConnectionTest) {
    val (text, isError) = when (test) {
        ConnectionTest.Idle -> return
        ConnectionTest.Running -> stringResource(R.string.source_test_running) to false
        is ConnectionTest.Succeeded -> pluralStringResource(R.plurals.source_test_ok, test.entryCount, test.entryCount) to false
        is ConnectionTest.Failed -> stringResource(R.string.source_test_failed, test.error.message()) to true
    }
    Text(
        text = text,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
    )
}

internal val SourceProtocol.label: String
    get() = when (this) {
        SourceProtocol.LOCAL -> "Local"
        SourceProtocol.SMB -> "SMB"
        SourceProtocol.NFS -> "NFS"
        SourceProtocol.FTP -> "FTP"
        SourceProtocol.FTPS -> "FTPS"
        SourceProtocol.WEBDAV -> "WebDAV"
        SourceProtocol.WEBDAVS -> "WebDAV (HTTPS)"
    }

private val SourceProtocol.hint: Int
    get() = when (this) {
        SourceProtocol.SMB, SourceProtocol.LOCAL -> R.string.source_hint_smb
        SourceProtocol.NFS -> R.string.source_hint_nfs
        SourceProtocol.FTP, SourceProtocol.FTPS -> R.string.source_hint_ftp
        SourceProtocol.WEBDAV, SourceProtocol.WEBDAVS -> R.string.source_hint_webdav
    }
