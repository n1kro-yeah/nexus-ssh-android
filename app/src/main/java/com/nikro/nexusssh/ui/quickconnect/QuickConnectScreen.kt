package com.nikro.nexusssh.ui.quickconnect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nikro.nexusssh.core.crypto.CryptoVault
import com.nikro.nexusssh.data.prefs.SettingsRepository
import com.nikro.nexusssh.data.repository.HostRepository
import com.nikro.nexusssh.domain.model.Host
import com.nikro.nexusssh.ssh.SshSessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One-off connection.
 *
 * Accepts what people already have in their clipboard - `user@host`, `user@host:2222`, or a full
 * `ssh://` URL - so a connection can be made without first creating a saved host.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickConnectScreen(
    onConnected: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    prefill: String? = null,
    viewModel: QuickConnectViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(prefill) { prefill?.let(viewModel::prefill) }
    LaunchedEffect(state.sessionId) { state.sessionId?.let(onConnected) }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Quick connect") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            OutlinedTextField(
                value = state.target,
                onValueChange = viewModel::setTarget,
                label = { Text("user@host:port") },
                supportingText = { Text("Port defaults to 22") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::setPassword,
                label = { Text("Password (optional)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = state.saveHost, onCheckedChange = viewModel::setSaveHost)
                Text("Save as a host", style = MaterialTheme.typography.bodyMedium)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = viewModel::connect,
                    enabled = state.target.isNotBlank() && !state.connecting,
                ) { Text("Connect") }
                if (state.connecting) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                }
            }
            Text(
                text = "The host key is still checked and pinned, exactly as it would be for a " +
                    "saved host.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 24.dp),
            )
        }
    }
}

/** Parses the target, connects, and optionally stores the host. */
@HiltViewModel
class QuickConnectViewModel @Inject constructor(
    private val sessions: SshSessionManager,
    private val hosts: HostRepository,
    private val settings: SettingsRepository,
    private val vault: CryptoVault,
) : ViewModel() {

    data class UiState(
        val target: String = "",
        val password: String = "",
        val saveHost: Boolean = false,
        val connecting: Boolean = false,
        val sessionId: String? = null,
        val message: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun prefill(value: String) {
        if (_state.value.target.isBlank()) setTarget(value)
    }

    fun setTarget(value: String) = _state.update { it.copy(target = value) }

    fun setPassword(value: String) = _state.update { it.copy(password = value) }

    fun setSaveHost(value: Boolean) = _state.update { it.copy(saveHost = value) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun connect() {
        val raw = _state.value.target.trim()
        if (raw.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(connecting = true) }
            val defaults = settings.current()
            val parsed = parseTarget(raw, defaults.defaultUsername, defaults.defaultPort)
            if (parsed == null) {
                _state.update {
                    it.copy(connecting = false, message = "Could not read that address")
                }
                return@launch
            }
            val password = _state.value.password
            var host = Host(
                label = "${parsed.username}@${parsed.hostname}",
                hostname = parsed.hostname,
                port = parsed.port,
                username = parsed.username,
                sealedPassword = if (password.isEmpty()) null else vault.seal(password),
                keepAliveSeconds = defaults.defaultKeepAliveSeconds,
                connectTimeoutMs = defaults.defaultConnectTimeoutMs,
                themeName = defaults.terminalTheme,
                fontSizeSp = defaults.fontSizeSp,
            )
            if (_state.value.saveHost) {
                val id = hosts.save(host)
                host = host.copy(id = id)
            }
            sessions.connect(host).fold(
                onSuccess = { id -> _state.update { it.copy(connecting = false, sessionId = id) } },
                onFailure = { failure ->
                    _state.update {
                        it.copy(
                            connecting = false,
                            message = failure.message ?: "Could not connect",
                        )
                    }
                },
            )
        }
    }

    private data class Target(val username: String, val hostname: String, val port: Int)

    /** Handles `user@host`, `host:port`, and `ssh://user@host:port` in one pass. */
    private fun parseTarget(input: String, defaultUser: String, defaultPort: Int): Target? {
        var text = input
        listOf("ssh://", "sftp://", "telnet://").forEach { scheme ->
            if (text.startsWith(scheme, ignoreCase = true)) text = text.removePrefix(scheme)
        }
        text = text.trimEnd('/')
        if (text.isEmpty()) return null

        val username: String
        val hostPart: String
        if (text.contains('@')) {
            username = text.substringBefore('@').ifBlank { defaultUser }
            hostPart = text.substringAfter('@')
        } else {
            username = defaultUser.ifBlank { "root" }
            hostPart = text
        }
        if (hostPart.isBlank()) return null

        // IPv6 in brackets, e.g. [2001:db8::1]:2222
        if (hostPart.startsWith("[")) {
            val closing = hostPart.indexOf(']')
            if (closing <= 0) return null
            val address = hostPart.substring(1, closing)
            val port = hostPart.substring(closing + 1).removePrefix(":").toIntOrNull() ?: defaultPort
            return Target(username, address, port)
        }

        val hostname = hostPart.substringBefore(':')
        val port = hostPart.substringAfter(':', "").toIntOrNull() ?: defaultPort
        if (hostname.isBlank() || port !in 1..65535) return null
        return Target(username, hostname, port)
    }
}
