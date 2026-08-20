package com.nikro.nexusssh.ui.hosts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nikro.nexusssh.core.crypto.CryptoVault
import com.nikro.nexusssh.data.prefs.SettingsRepository
import com.nikro.nexusssh.data.repository.HostRepository
import com.nikro.nexusssh.data.repository.KeychainRepository
import com.nikro.nexusssh.data.repository.SnippetRepository
import com.nikro.nexusssh.domain.model.Host
import com.nikro.nexusssh.domain.model.HostGroup
import com.nikro.nexusssh.domain.model.Identity
import com.nikro.nexusssh.domain.model.Snippet
import com.nikro.nexusssh.domain.model.SshKey
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Editing state for a single host. */
@HiltViewModel
class HostEditorViewModel @Inject constructor(
    private val hosts: HostRepository,
    private val keychain: KeychainRepository,
    private val snippets: SnippetRepository,
    private val settings: SettingsRepository,
    private val vault: CryptoVault,
) : ViewModel() {

    data class UiState(
        val host: Host = Host(label = "", hostname = "", username = ""),
        val password: String = "",
        val passwordTouched: Boolean = false,
        val groups: List<HostGroup> = emptyList(),
        val identities: List<Identity> = emptyList(),
        val keys: List<SshKey> = emptyList(),
        val jumpCandidates: List<Host> = emptyList(),
        val snippets: List<Snippet> = emptyList(),
        val isNew: Boolean = true,
        val loading: Boolean = true,
        val savedId: Long? = null,
        val message: String? = null,
    ) {
        val canSave: Boolean
            get() = host.label.isNotBlank() &&
                host.hostname.isNotBlank() &&
                host.port in 1..65535
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var loadedFor: Long? = null

    /** Loads an existing host, or prepares a new one seeded from app defaults. */
    fun load(hostId: Long) {
        if (loadedFor == hostId) return
        loadedFor = hostId
        viewModelScope.launch {
            val defaults = settings.current()
            val existing = if (hostId > 0) hosts.host(hostId) else null
            val host = existing ?: Host(
                label = "",
                hostname = "",
                username = defaults.defaultUsername,
                port = defaults.defaultPort,
                keepAliveSeconds = defaults.defaultKeepAliveSeconds,
                connectTimeoutMs = defaults.defaultConnectTimeoutMs,
                themeName = defaults.terminalTheme,
                fontSizeSp = defaults.fontSizeSp,
            )
            _state.update {
                it.copy(
                    host = host,
                    isNew = existing == null,
                    loading = false,
                    groups = hosts.allGroups(),
                    identities = hosts.allIdentities(),
                    keys = keychain.allKeys(),
                    jumpCandidates = hosts.allHosts().filter { candidate -> candidate.id != hostId },
                    snippets = snippets.all(),
                )
            }
        }
    }

    fun edit(transform: (Host) -> Host) {
        _state.update { it.copy(host = transform(it.host)) }
    }

    fun setPassword(value: String) {
        _state.update { it.copy(password = value, passwordTouched = true) }
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    /** Applies group defaults, seals a touched password, then persists the host. */
    fun save() {
        val current = _state.value
        if (!current.canSave) {
            _state.update { it.copy(message = "A label, a hostname and a valid port are required") }
            return
        }
        viewModelScope.launch {
            val sealedPassword = when {
                !current.passwordTouched -> current.host.sealedPassword
                current.password.isEmpty() -> null
                else -> vault.seal(current.password)
            }
            val prepared = hosts.applyGroupDefaults(
                current.host.copy(
                    label = current.host.label.trim(),
                    hostname = current.host.hostname.trim(),
                    username = current.host.username?.trim()?.ifBlank { null },
                    sealedPassword = sealedPassword,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            runCatching { hosts.save(prepared) }.fold(
                onSuccess = { id -> _state.update { it.copy(savedId = id) } },
                onFailure = { failure ->
                    _state.update { it.copy(message = failure.message ?: "Could not save this host") }
                },
            )
        }
    }

    fun delete() {
        val id = _state.value.host.id
        if (id <= 0) return
        viewModelScope.launch {
            hosts.deleteById(id)
            _state.update { it.copy(savedId = id) }
        }
    }
}
