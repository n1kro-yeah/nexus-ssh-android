package com.nikro.nexusssh.ui.hosts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nikro.nexusssh.data.repository.HostRepository
import com.nikro.nexusssh.domain.model.Host
import com.nikro.nexusssh.domain.model.HostGroup
import com.nikro.nexusssh.ssh.SshSessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State for the host list: the grouped tree, favourites, recents, and search.
 *
 * Connecting is started here rather than in the screen so a rotation or a tab switch cannot orphan
 * a half-open session.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class HostsViewModel @Inject constructor(
    private val hosts: HostRepository,
    private val sessions: SshSessionManager,
) : ViewModel() {

    data class UiState(
        val query: String = "",
        val groups: List<HostRepository.GroupedHosts> = emptyList(),
        val favorites: List<Host> = emptyList(),
        val recent: List<Host> = emptyList(),
        val searchResults: List<Host> = emptyList(),
        val connectingHostId: Long? = null,
        val error: String? = null,
    ) {
        val isSearching: Boolean get() = query.isNotBlank()
        val isEmpty: Boolean get() = groups.all { it.isEmpty } && favorites.isEmpty()
    }

    private val query = MutableStateFlow("")
    private val connecting = MutableStateFlow<Long?>(null)
    private val error = MutableStateFlow<String?>(null)

    private val searchResults = query
        .debounce(SEARCH_DEBOUNCE_MS)
        .flatMapLatest { text ->
            if (text.isBlank()) flowOf(emptyList()) else hosts.search(text)
        }

    private val transient = combine(query, connecting, error) { text, connectingId, message ->
        Triple(text, connectingId, message)
    }

    val state: StateFlow<UiState> = combine(
        hosts.observeGrouped(),
        hosts.observeFavorites(),
        hosts.observeRecent(),
        searchResults,
        transient,
    ) { grouped, favorites, recent, results, (text, connectingId, message) ->
        UiState(
            query = text,
            groups = grouped,
            favorites = favorites,
            recent = recent,
            searchResults = results,
            connectingHostId = connectingId,
            error = message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), UiState())

    /** Live sessions, so a host row can show that it is already open. */
    val openSessions: StateFlow<List<SshSessionManager.Entry>> = sessions.sessions

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun clearQuery() {
        query.value = ""
    }

    fun dismissError() {
        error.value = null
    }

    fun toggleFavorite(host: Host) {
        viewModelScope.launch { hosts.setFavorite(host.id, !host.isFavorite) }
    }

    fun delete(host: Host) {
        viewModelScope.launch { hosts.deleteById(host.id) }
    }

    fun moveToGroup(hostIds: List<Long>, group: HostGroup?) {
        viewModelScope.launch { hosts.moveToGroup(hostIds, group?.id) }
    }

    /**
     * Opens a session and reports the id so the caller can switch to the terminal tab.
     *
     * An existing live session for the same host is reused, which is what people expect when they
     * tap a host they are already connected to.
     */
    fun connect(host: Host, columns: Int = 80, rows: Int = 24, onConnected: (String) -> Unit) {
        sessions.sessionsFor(host.id).firstOrNull { it.isAlive }?.let { existing ->
            sessions.setActive(existing.id)
            onConnected(existing.id)
            return
        }
        viewModelScope.launch {
            connecting.value = host.id
            val result = sessions.connect(host, columns, rows)
            connecting.value = null
            result
                .onSuccess(onConnected)
                .onFailure { error.value = it.message ?: "Could not connect" }
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 150L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
