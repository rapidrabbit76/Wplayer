package dev.wplayer.app.ui.sources

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wplayer.app.R
import dev.wplayer.app.data.SourceRepository
import dev.wplayer.app.data.model.Source
import dev.wplayer.app.data.model.SourceProtocol
import dev.wplayer.app.ui.common.UserError
import dev.wplayer.app.ui.common.catchingFailures
import dev.wplayer.app.ui.navigation.SourceEditorRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ConnectionTest {
    data object Idle : ConnectionTest
    data object Running : ConnectionTest
    data class Succeeded(val entryCount: Int) : ConnectionTest
    data class Failed(val error: UserError) : ConnectionTest
}

data class SourceEditorUiState(
    val isNew: Boolean = true,
    val protocol: SourceProtocol = SourceProtocol.SMB,
    val name: String = "",
    val host: String = "",
    val port: String = "",
    val path: String = "",
    val username: String = "",
    val password: String = "",
    /** Editing a source whose password is stored; an empty field keeps it. */
    val hasStoredPassword: Boolean = false,
    val allowUntrustedCertificates: Boolean = false,
    @StringRes val validationError: Int? = null,
    /** Saving or deleting failed. */
    val actionError: UserError? = null,
    val connectionTest: ConnectionTest = ConnectionTest.Idle,
    val isSaving: Boolean = false,
    /** Set once saved or deleted; the screen then closes. */
    val isFinished: Boolean = false,
)

@HiltViewModel(assistedFactory = SourceEditorViewModel.Factory::class)
class SourceEditorViewModel @AssistedInject constructor(
    @Assisted private val route: SourceEditorRoute,
    private val sourceRepository: SourceRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SourceEditorUiState(isNew = route.sourceKey == null))
    val uiState: StateFlow<SourceEditorUiState> = _uiState.asStateFlow()

    init {
        route.sourceKey?.let { key ->
            viewModelScope.launch {
                val source = sourceRepository.find(key) ?: return@launch
                _uiState.update {
                    it.copy(
                        protocol = source.protocol,
                        name = source.name,
                        host = source.host,
                        port = source.port?.toString().orEmpty(),
                        path = source.rootPath,
                        username = source.username.orEmpty(),
                        hasStoredPassword = sourceRepository.hasStoredPassword(key),
                        allowUntrustedCertificates = source.allowUntrustedCertificates,
                    )
                }
            }
        }
    }

    fun onProtocolChange(protocol: SourceProtocol) = edit { it.copy(protocol = protocol) }
    fun onNameChange(name: String) = edit { it.copy(name = name) }
    fun onHostChange(host: String) = edit { it.copy(host = host.trim()) }
    fun onPortChange(port: String) = edit { it.copy(port = port.filter(Char::isDigit)) }
    fun onPathChange(path: String) = edit { it.copy(path = path) }
    fun onUsernameChange(username: String) = edit { it.copy(username = username) }
    fun onPasswordChange(password: String) = edit { it.copy(password = password) }
    fun onAllowUntrustedChange(allow: Boolean) = edit { it.copy(allowUntrustedCertificates = allow) }

    fun testConnection() {
        val source = validatedSource() ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(connectionTest = ConnectionTest.Running) }
            val result = catchingFailures { sourceRepository.testConnection(source, passwordToSend()) }.fold(
                onSuccess = { ConnectionTest.Succeeded(it) },
                onFailure = { ConnectionTest.Failed(UserError.from(it)) },
            )
            _uiState.update { it.copy(connectionTest = result) }
        }
    }

    fun save() {
        val source = validatedSource() ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            catchingFailures { sourceRepository.save(source, passwordToSend()) }
                .onSuccess { _uiState.update { it.copy(isSaving = false, isFinished = true) } }
                .onFailure { error -> _uiState.update { it.copy(isSaving = false, actionError = UserError.from(error)) } }
        }
    }

    fun delete() {
        val key = route.sourceKey ?: return
        viewModelScope.launch {
            catchingFailures { sourceRepository.delete(key) }
                .onSuccess { _uiState.update { it.copy(isFinished = true) } }
                .onFailure { error -> _uiState.update { it.copy(actionError = UserError.from(error)) } }
        }
    }

    /** Any edit invalidates a previous test result and validation message. */
    private inline fun edit(transform: (SourceEditorUiState) -> SourceEditorUiState) =
        _uiState.update { transform(it).copy(validationError = null, actionError = null, connectionTest = ConnectionTest.Idle) }

    /** Null keeps the stored password; otherwise the typed one (possibly empty). */
    private fun passwordToSend(): String? = with(uiState.value) {
        if (hasStoredPassword && password.isEmpty()) null else password
    }

    private fun validatedSource(): Source? {
        val state = uiState.value
        val port = state.port.takeIf { it.isNotEmpty() }?.toIntOrNull()
        val error = when {
            state.host.isEmpty() -> R.string.source_error_host
            state.port.isNotEmpty() && (port == null || port !in 1..65535) -> R.string.source_error_port
            else -> null
        }
        if (error != null) {
            _uiState.update { it.copy(validationError = error) }
            return null
        }
        return Source(
            key = route.sourceKey.orEmpty(),
            name = state.name.ifBlank { state.host },
            protocol = state.protocol,
            host = state.host,
            port = port,
            rootPath = "/" + state.path.trim().trim('/'),
            username = state.username.takeIf { it.isNotBlank() },
            allowUntrustedCertificates = state.protocol.usesTls && state.allowUntrustedCertificates,
        )
    }

    @AssistedFactory
    interface Factory {
        fun create(route: SourceEditorRoute): SourceEditorViewModel
    }
}
