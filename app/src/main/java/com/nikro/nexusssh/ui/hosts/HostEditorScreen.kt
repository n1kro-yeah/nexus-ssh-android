package com.nikro.nexusssh.ui.hosts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nikro.nexusssh.domain.model.BackspaceMode
import com.nikro.nexusssh.domain.model.Protocol
import com.nikro.nexusssh.ui.components.ConfirmDialog
import com.nikro.nexusssh.ui.components.SectionHeader
import com.nikro.nexusssh.ui.settings.SwitchRow

/** Host editor grouped by connection, authentication, network and terminal settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostEditorScreen(
    hostId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HostEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(hostId) { viewModel.load(hostId) }
    LaunchedEffect(state.savedId) { if (state.savedId != null) onBack() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    val host = state.host
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(if (state.isNew) "New host" else "Edit host") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!state.isNew) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Delete host")
                        }
                    }
                    IconButton(onClick = viewModel::save, enabled = state.canSave) {
                        Icon(Icons.Rounded.Check, contentDescription = "Save")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            SectionHeader("Connection")
            OutlinedTextField(
                value = host.label,
                onValueChange = { value -> viewModel.edit { it.copy(label = value) } },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = host.hostname,
                onValueChange = { value -> viewModel.edit { it.copy(hostname = value) } },
                label = { Text("Hostname or IP") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = host.port.toString(),
                    onValueChange = { value ->
                        val port = value.filter(Char::isDigit).take(5).toIntOrNull() ?: 0
                        viewModel.edit { it.copy(port = port) }
                    },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = host.username.orEmpty(),
                    onValueChange = { value -> viewModel.edit { it.copy(username = value) } },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.weight(2f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Protocol.entries.forEach { protocol ->
                    FilterChip(
                        selected = host.protocol == protocol,
                        onClick = {
                            viewModel.edit {
                                it.copy(
                                    protocol = protocol,
                                    port = if (protocol.defaultPort > 0) protocol.defaultPort else it.port,
                                )
                            }
                        },
                        label = { Text(protocol.displayName) },
                    )
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            SectionHeader("Authentication")
            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::setPassword,
                label = {
                    Text(if (state.isNew || state.passwordTouched) "Password" else "Password (leave empty to keep)")
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            PickerRow(
                label = "Key",
                selected = state.keys.firstOrNull { it.id == host.keyId }?.label ?: "None",
                options = listOf(0L to "None") + state.keys.map { it.id to it.label },
                onSelect = { id -> viewModel.edit { it.copy(keyId = id.takeIf { value -> value > 0 }) } },
            )
            PickerRow(
                label = "Identity",
                selected = state.identities.firstOrNull { it.id == host.identityId }?.label ?: "None",
                options = listOf(0L to "None") + state.identities.map { it.id to it.label },
                onSelect = { id -> viewModel.edit { it.copy(identityId = id.takeIf { value -> value > 0 }) } },
            )
            SwitchRow(
                title = "Agent forwarding",
                subtitle = "Let this server use your keys for its own connections",
                checked = host.agentForwarding,
                onCheckedChange = { value -> viewModel.edit { it.copy(agentForwarding = value) } },
            )

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            SectionHeader("Network")
            PickerRow(
                label = "Jump host",
                selected = state.jumpCandidates.firstOrNull { it.id == host.jumpHostId }?.label ?: "Direct",
                options = listOf(0L to "Direct") + state.jumpCandidates.map { it.id to it.label },
                onSelect = { id -> viewModel.edit { it.copy(jumpHostId = id.takeIf { value -> value > 0 }) } },
            )
            SwitchRow(
                title = "Compression",
                subtitle = "Helps on slow mobile links, costs CPU",
                checked = host.compression,
                onCheckedChange = { value -> viewModel.edit { it.copy(compression = value) } },
            )
            SwitchRow(
                title = "Strict host key checking",
                subtitle = "Refuse to connect if the server key changed",
                checked = host.strictHostKeyChecking,
                onCheckedChange = { value -> viewModel.edit { it.copy(strictHostKeyChecking = value) } },
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = host.keepAliveSeconds.toString(),
                    onValueChange = { value ->
                        val seconds = value.filter(Char::isDigit).take(4).toIntOrNull() ?: 0
                        viewModel.edit { it.copy(keepAliveSeconds = seconds) }
                    },
                    label = { Text("Keep-alive s") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = (host.connectTimeoutMs / 1000).toString(),
                    onValueChange = { value ->
                        val seconds = value.filter(Char::isDigit).take(3).toIntOrNull() ?: 0
                        viewModel.edit { it.copy(connectTimeoutMs = seconds * 1000) }
                    },
                    label = { Text("Timeout s") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            SectionHeader("Terminal")
            OutlinedTextField(
                value = host.terminalType,
                onValueChange = { value -> viewModel.edit { it.copy(terminalType = value) } },
                label = { Text("TERM") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = host.charset,
                onValueChange = { value -> viewModel.edit { it.copy(charset = value) } },
                label = { Text("Character set") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BackspaceMode.entries.forEach { mode ->
                    FilterChip(
                        selected = host.backspaceMode == mode,
                        onClick = { viewModel.edit { it.copy(backspaceMode = mode) } },
                        label = { Text("Backspace: " + mode.displayName) },
                    )
                }
            }
            PickerRow(
                label = "Run on connect",
                selected = state.snippets.firstOrNull { it.id == host.startupSnippetId }?.name ?: "Nothing",
                options = listOf(0L to "Nothing") + state.snippets.map { it.id to it.name },
                onSelect = { id -> viewModel.edit { it.copy(startupSnippetId = id.takeIf { value -> value > 0 }) } },
            )

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            SectionHeader("Organisation")
            PickerRow(
                label = "Group",
                selected = state.groups.firstOrNull { it.id == host.groupId }?.name ?: "Ungrouped",
                options = listOf(0L to "Ungrouped") + state.groups.map { it.id to it.name },
                onSelect = { id -> viewModel.edit { it.copy(groupId = id.takeIf { value -> value > 0 }) } },
            )
            OutlinedTextField(
                value = host.tags.joinToString(", "),
                onValueChange = { value ->
                    val tags = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    viewModel.edit { it.copy(tags = tags) }
                },
                label = { Text("Tags, comma separated") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = host.notes,
                onValueChange = { value -> viewModel.edit { it.copy(notes = value) } },
                label = { Text("Notes") },
                minLines = 2,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            SwitchRow(
                title = "Favourite",
                subtitle = "Pin to the top of the host list",
                checked = host.isFavorite,
                onCheckedChange = { value -> viewModel.edit { it.copy(isFavorite = value) } },
            )
            Text(
                text = "Empty fields inherit the group default, then the app default.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete host?",
            message = "The host and its port-forwarding rules are removed. Keys and known-host entries are kept.",
            confirmLabel = "Delete",
            onConfirm = { confirmDelete = false; viewModel.delete() },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun PickerRow(
    label: String,
    selected: String,
    options: List<Pair<Long, String>>,
    onSelect: (Long) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        AssistChip(onClick = { open = true }, label = { Text(selected) })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (id, title) ->
                DropdownMenuItem(
                    text = { Text(title) },
                    onClick = { open = false; onSelect(id) },
                )
            }
        }
    }
}
