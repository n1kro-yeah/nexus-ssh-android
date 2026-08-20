package com.nikro.nexusssh.ui.hosts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nikro.nexusssh.domain.model.ConnectionStatus
import com.nikro.nexusssh.domain.model.Host
import com.nikro.nexusssh.ui.components.EmptyState
import com.nikro.nexusssh.ui.components.MetaChip
import com.nikro.nexusssh.ui.components.SectionHeader
import com.nikro.nexusssh.ui.components.StatusDot
import com.nikro.nexusssh.ui.components.listContentPadding

/**
 * The home screen: favourites, recents and the grouped host tree, with search.
 *
 * Tapping a host connects and hands the new session id back to the caller, which switches to the
 * terminal tab. Everything destructive lives behind the row's overflow menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostsScreen(
    onOpenSession: (String) -> Unit,
    onEditHost: (Long?) -> Unit,
    onQuickConnect: () -> Unit,
    onOpenSftp: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HostsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val openSessions by viewModel.openSessions.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    var searchOpen by remember { mutableStateOf(false) }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Hosts") },
                actions = {
                    IconButton(onClick = { searchOpen = !searchOpen }) {
                        Icon(Icons.Rounded.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = onQuickConnect) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = "Quick connect")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onEditHost(null) },
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("New host") },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            if (searchOpen) {
                TextField(
                    value = state.query,
                    onValueChange = viewModel::onQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("Search hosts, tags, addresses") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = viewModel::clearQuery) {
                                Icon(Icons.Rounded.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                )
            }

            when {
                state.isSearching -> HostList(
                    hosts = state.searchResults,
                    state = state,
                    openSessions = openSessions,
                    viewModel = viewModel,
                    onOpenSession = onOpenSession,
                    onEditHost = onEditHost,
                    onOpenSftp = onOpenSftp,
                    emptyTitle = "Nothing found",
                    emptyDescription = "No host matches \"${state.query}\".",
                )

                state.isEmpty -> EmptyState(
                    icon = Icons.Rounded.List,
                    title = "No hosts yet",
                    description = "Add the first server, or use quick connect to try an address without saving it.",
                    actionLabel = "Add host",
                    onAction = { onEditHost(null) },
                )

                else -> GroupedHostList(
                    state = state,
                    openSessions = openSessions,
                    viewModel = viewModel,
                    onOpenSession = onOpenSession,
                    onEditHost = onEditHost,
                    onOpenSftp = onOpenSftp,
                )
            }
        }
    }
}

@Composable
private fun GroupedHostList(
    state: HostsViewModel.UiState,
    openSessions: List<com.nikro.nexusssh.ssh.SshSessionManager.Entry>,
    viewModel: HostsViewModel,
    onOpenSession: (String) -> Unit,
    onEditHost: (Long?) -> Unit,
    onOpenSftp: (Long) -> Unit,
) {
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = listContentPadding,
    ) {
        if (state.favorites.isNotEmpty()) {
            item { SectionHeader("Favorites") }
            items(state.favorites, key = { "fav-" + it.id }) { host ->
                HostRow(host, state, openSessions, viewModel, onOpenSession, onEditHost, onOpenSftp)
            }
        }
        if (state.recent.isNotEmpty()) {
            item { SectionHeader("Recent") }
            items(state.recent, key = { "recent-" + it.id }) { host ->
                HostRow(host, state, openSessions, viewModel, onOpenSession, onEditHost, onOpenSftp)
            }
        }
        state.groups.forEach { group ->
            if (group.hosts.isEmpty()) return@forEach
            item(key = "group-" + (group.group?.id ?: 0L)) { SectionHeader(group.title) }
            items(group.hosts, key = { "host-" + it.id }) { host ->
                HostRow(host, state, openSessions, viewModel, onOpenSession, onEditHost, onOpenSftp)
            }
        }
    }
}

@Composable
private fun HostList(
    hosts: List<Host>,
    state: HostsViewModel.UiState,
    openSessions: List<com.nikro.nexusssh.ssh.SshSessionManager.Entry>,
    viewModel: HostsViewModel,
    onOpenSession: (String) -> Unit,
    onEditHost: (Long?) -> Unit,
    onOpenSftp: (Long) -> Unit,
    emptyTitle: String,
    emptyDescription: String,
) {
    if (hosts.isEmpty()) {
        EmptyState(
            icon = Icons.Rounded.Search,
            title = emptyTitle,
            description = emptyDescription,
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = listContentPadding) {
        items(hosts, key = { it.id }) { host ->
            HostRow(host, state, openSessions, viewModel, onOpenSession, onEditHost, onOpenSftp)
        }
    }
}

@Composable
private fun HostRow(
    host: Host,
    state: HostsViewModel.UiState,
    openSessions: List<com.nikro.nexusssh.ssh.SshSessionManager.Entry>,
    viewModel: HostsViewModel,
    onOpenSession: (String) -> Unit,
    onEditHost: (Long?) -> Unit,
    onOpenSftp: (Long) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val liveSession = openSessions.firstOrNull { it.config.hostId == host.id && it.isAlive }
    val connecting = state.connectingHostId == host.id
    val status = when {
        liveSession != null -> ConnectionStatus.CONNECTED
        connecting -> ConnectionStatus.CONNECTING
        else -> ConnectionStatus.IDLE
    }

    ListItem(
        headlineContent = {
            Text(
                text = host.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        supportingContent = {
            Column {
                Text(
                    text = host.displayAddress,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (host.tags.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        host.tags.take(3).forEach { tag -> MetaChip(tag) }
                    }
                }
            }
        },
        leadingContent = {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = host.color?.let { Color(it) }
                    ?: MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = host.label.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (connecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    StatusDot(status)
                }
                Spacer(Modifier.width(4.dp))
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Open files") },
                            onClick = {
                                menuOpen = false
                                onOpenSftp(host.id)
                            },
                            leadingIcon = { Icon(Icons.Rounded.List, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text(if (host.isFavorite) "Remove favorite" else "Add favorite") },
                            onClick = {
                                menuOpen = false
                                viewModel.toggleFavorite(host)
                            },
                            leadingIcon = { Icon(Icons.Rounded.Star, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text("Edit") },
                            onClick = {
                                menuOpen = false
                                onEditHost(host.id)
                            },
                            leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            onClick = {
                                menuOpen = false
                                viewModel.delete(host)
                            },
                            leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                        )
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { viewModel.connect(host, onConnected = onOpenSession) },
    )
}
