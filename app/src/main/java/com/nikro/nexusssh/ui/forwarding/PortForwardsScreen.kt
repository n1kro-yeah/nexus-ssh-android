package com.nikro.nexusssh.ui.forwarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nikro.nexusssh.data.repository.HostRepository
import com.nikro.nexusssh.data.repository.PortForwardRepository
import com.nikro.nexusssh.domain.model.ForwardType
import com.nikro.nexusssh.domain.model.Host
import com.nikro.nexusssh.domain.model.PortForwardRule
import com.nikro.nexusssh.service.PortForwardService
import com.nikro.nexusssh.ui.components.EmptyState
import com.nikro.nexusssh.ui.components.MetaChip
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Tunnel rules.
 *
 * Local (-L), remote (-R) and dynamic (-D) forwarding, each bound to a host. Rules marked "start
 * automatically" are brought up by the forwarding service after boot, so a phone that reboots
 * overnight still has its tunnels in the morning.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortForwardsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PortForwardsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<PortForwardRule?>(null) }
    var creating by remember { mutableStateOf(false) }

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
                title = { Text("Port forwarding") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { PortForwardService.startAuto(context) },
                        enabled = state.rules.any { it.enabled },
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = "Start tunnels")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { creating = true },
            ) { Icon(Icons.Rounded.Add, contentDescription = "New rule") }
        },
    ) { padding ->
        if (state.rules.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.Send,
                title = "No tunnels",
                description = "Forward a local port to a remote service, publish a local service " +
                    "to the server, or run a SOCKS proxy through the host.",
                actionLabel = "New rule",
                onAction = { creating = true },
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                items(state.rules, key = { it.id }) { rule ->
                    RuleRow(
                        rule = rule,
                        hostLabel = state.hosts.firstOrNull { it.id == rule.hostId }?.label ?: "",
                        onToggle = { viewModel.setEnabled(rule, it) },
                        onEdit = { editing = rule },
                        onDelete = { viewModel.delete(rule.id) },
                    )
                }
            }
        }
    }

    if (creating || editing != null) {
        RuleEditorDialog(
            initial = editing,
            hosts = state.hosts,
            onDismiss = {
                creating = false
                editing = null
            },
            onSave = { rule ->
                creating = false
                editing = null
                viewModel.save(rule)
            },
        )
    }
}

@Composable
private fun RuleRow(
    rule: PortForwardRule,
    hostLabel: String,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = {
            Text(
                text = rule.label.ifBlank { rule.type.displayName },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column {
                Text(
                    text = rule.asSshCommand(hostLabel),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (hostLabel.isNotBlank()) MetaChip(hostLabel)
                    if (rule.autoStart) MetaChip("auto")
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = rule.enabled, onCheckedChange = onToggle)
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Rounded.Delete, contentDescription = "Delete")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Delete rule") },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit),
    )
}

@Composable
private fun RuleEditorDialog(
    initial: PortForwardRule?,
    hosts: List<Host>,
    onDismiss: () -> Unit,
    onSave: (PortForwardRule) -> Unit,
) {
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var type by remember { mutableStateOf(initial?.type ?: ForwardType.LOCAL) }
    var hostId by remember { mutableStateOf(initial?.hostId ?: hosts.firstOrNull()?.id ?: 0L) }
    var bindAddress by remember { mutableStateOf(initial?.bindAddress ?: "127.0.0.1") }
    var localPort by remember { mutableStateOf((initial?.localPort ?: 8080).toString()) }
    var remoteHost by remember { mutableStateOf(initial?.remoteHost ?: "localhost") }
    var remotePort by remember { mutableStateOf((initial?.remotePort ?: 80).toString()) }
    var autoStart by remember { mutableStateOf(initial?.autoStart ?: false) }
    var typeMenu by remember { mutableStateOf(false) }
    var hostMenu by remember { mutableStateOf(false) }

    val dynamic = type == ForwardType.DYNAMIC
    val selectedHost = hosts.firstOrNull { it.id == hostId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New tunnel" else "Edit tunnel") },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AssistChip(
                        onClick = { typeMenu = true },
                        label = { Text(type.displayName + " (" + type.sshFlag + ")") },
                    )
                    DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                        ForwardType.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.displayName + "  " + option.sshFlag) },
                                onClick = {
                                    type = option
                                    typeMenu = false
                                },
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AssistChip(
                        onClick = { hostMenu = true },
                        label = { Text(selectedHost?.label ?: "Choose host") },
                    )
                    DropdownMenu(expanded = hostMenu, onDismissRequest = { hostMenu = false }) {
                        hosts.forEach { host ->
                            DropdownMenuItem(
                                text = { Text(host.label) },
                                onClick = {
                                    hostId = host.id
                                    hostMenu = false
                                },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = bindAddress,
                    onValueChange = { bindAddress = it },
                    label = { Text("Bind address") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = localPort,
                    onValueChange = { localPort = it.filter(Char::isDigit).take(5) },
                    label = { Text(if (dynamic) "SOCKS port" else "Local port") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                if (!dynamic) {
                    OutlinedTextField(
                        value = remoteHost,
                        onValueChange = { remoteHost = it },
                        label = { Text("Remote host") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                    OutlinedTextField(
                        value = remotePort,
                        onValueChange = { remotePort = it.filter(Char::isDigit).take(5) },
                        label = { Text("Remote port") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = autoStart, onCheckedChange = { autoStart = it })
                    Text(
                        text = "Start automatically after boot",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = hostId != 0L && localPort.isNotBlank(),
                onClick = {
                    onSave(
                        (initial ?: PortForwardRule(type = type, hostId = hostId)).copy(
                            label = label.trim(),
                            type = type,
                            hostId = hostId,
                            bindAddress = bindAddress.trim().ifBlank { "127.0.0.1" },
                            localPort = localPort.toIntOrNull() ?: 0,
                            remoteHost = remoteHost.trim(),
                            remotePort = remotePort.toIntOrNull() ?: 0,
                            autoStart = autoStart,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Rule list, validation and persistence. */
@HiltViewModel
class PortForwardsViewModel @Inject constructor(
    private val repository: PortForwardRepository,
    hostRepository: HostRepository,
) : ViewModel() {

    data class UiState(
        val rules: List<PortForwardRule> = emptyList(),
        val hosts: List<Host> = emptyList(),
        val message: String? = null,
    )

    private val message = MutableStateFlow<String?>(null)

    val state: StateFlow<UiState> = combine(
        repository.observeAll(),
        hostRepository.observeHosts(),
        message,
    ) { rules, hosts, text ->
        UiState(rules = rules, hosts = hosts, message = text)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    fun dismissMessage() {
        message.value = null
    }

    /** Rejects impossible rules before they reach the tunnel manager. */
    fun save(rule: PortForwardRule) {
        val problem = repository.validate(rule)
        if (problem != null) {
            message.value = problem
            return
        }
        viewModelScope.launch { repository.save(rule) }
    }

    fun setEnabled(rule: PortForwardRule, enabled: Boolean) {
        viewModelScope.launch { repository.save(rule.copy(enabled = enabled)) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { repository.delete(id) }
    }
}
