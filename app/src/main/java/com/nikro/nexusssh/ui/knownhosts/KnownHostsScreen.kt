package com.nikro.nexusssh.ui.knownhosts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nikro.nexusssh.data.repository.KnownHostRepository
import com.nikro.nexusssh.domain.model.KnownHost
import com.nikro.nexusssh.ui.components.EmptyState
import com.nikro.nexusssh.ui.components.MetaChip
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * The trust store.
 *
 * This is the screen that makes host key checking auditable: every fingerprint the device has
 * accepted, when it was last seen, and the ability to revoke one so a compromised key can never be
 * silently accepted again. Import and export use the standard `known_hosts` format.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnownHostsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: KnownHostsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    var menuOpen by remember { mutableStateOf(false) }
    var importOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }
    LaunchedEffect(state.clipboardText) {
        state.clipboardText?.let {
            clipboard.setText(AnnotatedString(it))
            viewModel.clipboardConsumed()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Known hosts") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Import known_hosts") },
                            onClick = {
                                menuOpen = false
                                importOpen = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Export to clipboard") },
                            onClick = {
                                menuOpen = false
                                viewModel.export()
                            },
                            leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text("Forget all") },
                            onClick = {
                                menuOpen = false
                                confirmClear = true
                            },
                            leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (state.entries.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.Lock,
                title = "No trusted hosts yet",
                description = "Fingerprints appear here the first time you accept a server's key.",
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                items(state.entries, key = { it.id }) { entry ->
                    KnownHostRow(
                        entry = entry,
                        onToggleRevoked = { viewModel.setRevoked(entry.id, !entry.isRevoked) },
                        onDelete = { viewModel.delete(entry.id) },
                    )
                }
            }
        }
    }

    if (importOpen) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { importOpen = false },
            title = { Text("Import known_hosts") },
            text = {
                Column {
                    Text(
                        text = "Paste the contents of a known_hosts file. Hashed entries are " +
                            "skipped, because a hashed host cannot be matched without the salt.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        minLines = 4,
                        maxLines = 8,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = text.isNotBlank(),
                    onClick = {
                        importOpen = false
                        viewModel.import(text)
                    },
                ) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { importOpen = false }) { Text("Cancel") } },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            icon = { Icon(Icons.Rounded.Warning, contentDescription = null) },
            title = { Text("Forget all host keys?") },
            text = {
                Text(
                    "Every server will be treated as new on the next connection, and you will be " +
                        "asked to verify its fingerprint again.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    viewModel.deleteAll()
                }) { Text("Forget all") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun KnownHostRow(
    entry: KnownHost,
    onToggleRevoked: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = {
            Text(
                text = if (entry.port == 22) {
                    entry.hostPattern
                } else {
                    "${entry.hostPattern}:${entry.port}"
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column {
                Text(
                    text = entry.fingerprintSha256,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "seen " + formatDate(entry.lastSeenAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        leadingContent = {
            Icon(
                imageVector = if (entry.isRevoked) Icons.Rounded.Warning else Icons.Rounded.Lock,
                contentDescription = null,
                tint = if (entry.isRevoked) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        },
        trailingContent = {
            Column {
                MetaChip(entry.keyType)
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(if (entry.isRevoked) "Un-revoke" else "Revoke") },
                        onClick = {
                            menuOpen = false
                            onToggleRevoked()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Forget") },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                        leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                    )
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun formatDate(epochMillis: Long): String =
    if (epochMillis <= 0) {
        "never"
    } else {
        SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(epochMillis))
    }

/** State and operations for the trust store screen. */
@HiltViewModel
class KnownHostsViewModel @Inject constructor(
    private val repository: KnownHostRepository,
) : ViewModel() {

    data class UiState(
        val entries: List<KnownHost> = emptyList(),
        val message: String? = null,
        val clipboardText: String? = null,
    )

    private val message = MutableStateFlow<String?>(null)
    private val clipboardText = MutableStateFlow<String?>(null)

    val state: StateFlow<UiState> = combine(
        repository.observeAll(),
        message,
        clipboardText,
    ) { entries, text, clip ->
        UiState(entries = entries, message = text, clipboardText = clip)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    fun dismissMessage() {
        message.value = null
    }

    fun clipboardConsumed() {
        clipboardText.value = null
    }

    fun delete(id: Long) {
        viewModelScope.launch { repository.delete(id) }
    }

    fun setRevoked(id: Long, revoked: Boolean) {
        viewModelScope.launch {
            repository.setRevoked(id, revoked)
            message.value = if (revoked) "Key revoked" else "Revocation removed"
        }
    }

    fun deleteAll() {
        viewModelScope.launch {
            repository.deleteAll()
            message.value = "All host keys forgotten"
        }
    }

    fun import(text: String) {
        viewModelScope.launch {
            val count = runCatching { repository.importOpenSshFormat(text) }.getOrDefault(0)
            message.value = if (count == 0) {
                "Nothing imported - no usable entries found"
            } else {
                "Imported $count host key(s)"
            }
        }
    }

    fun export() {
        viewModelScope.launch {
            val text = repository.exportOpenSshFormat()
            if (text.isBlank()) {
                message.value = "Nothing to export"
            } else {
                clipboardText.value = text
                message.value = "known_hosts copied to the clipboard"
            }
        }
    }
}
