package com.nikro.nexusssh.ui.identities

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Person
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.nikro.nexusssh.data.repository.HostRepository
import com.nikro.nexusssh.data.repository.KeychainRepository
import com.nikro.nexusssh.domain.model.Identity
import com.nikro.nexusssh.domain.model.SshKey
import com.nikro.nexusssh.ui.components.EmptyState
import com.nikro.nexusssh.ui.components.MetaChip
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Reusable credentials.
 *
 * An identity is a username plus an optional password and key, shared by many hosts. Changing the
 * password in one place then fixes every host that uses it, which is the difference between
 * rotating a credential in a minute and doing it forty times.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdentitiesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: IdentitiesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Identity?>(null) }
    var creating by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Identities") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) {
                Icon(Icons.Rounded.Add, contentDescription = "New identity")
            }
        },
    ) { padding ->
        if (state.identities.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.Person,
                title = "No identities",
                description = "Store a username, password and key once, then reuse it across hosts.",
                modifier = Modifier.padding(padding),
                actionLabel = "New identity",
                onAction = { creating = true },
            )
        } else {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                items(state.identities, key = { it.id }) { identity ->
                    ListItem(
                        headlineContent = { Text(identity.label) },
                        supportingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = identity.username,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (identity.sealedPassword != null) MetaChip("password")
                                state.keys.firstOrNull { it.id == identity.keyId }?.let { key ->
                                    MetaChip(key.label)
                                }
                            }
                        },
                        leadingContent = {
                            Icon(Icons.Rounded.Person, contentDescription = null)
                        },
                        trailingContent = {
                            IconButton(onClick = { viewModel.delete(identity.id) }) {
                                Icon(Icons.Rounded.Delete, contentDescription = "Delete")
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { editing = identity },
                    )
                }
            }
        }
    }

    if (creating || editing != null) {
        IdentityDialog(
            initial = editing,
            keys = state.keys,
            onDismiss = {
                creating = false
                editing = null
            },
            onSave = { identity, password, passwordTouched ->
                creating = false
                editing = null
                viewModel.save(identity, password, passwordTouched)
            },
        )
    }
}

@Composable
private fun IdentityDialog(
    initial: Identity?,
    keys: List<SshKey>,
    onDismiss: () -> Unit,
    onSave: (Identity, String, Boolean) -> Unit,
) {
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var username by remember { mutableStateOf(initial?.username ?: "") }
    var password by remember { mutableStateOf("") }
    var passwordTouched by remember { mutableStateOf(false) }
    var keyId by remember { mutableStateOf(initial?.keyId) }
    var askEveryTime by remember { mutableStateOf(initial?.askPasswordEveryTime ?: false) }
    var keyMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New identity" else "Edit identity") },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        passwordTouched = true
                    },
                    label = {
                        Text(
                            if (initial?.sealedPassword != null && !passwordTouched) {
                                "Password (leave empty to keep)"
                            } else {
                                "Password"
                            },
                        )
                    },
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
                        .padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Key", modifier = Modifier.weight(1f))
                    AssistChip(
                        onClick = { keyMenu = true },
                        label = {
                            Text(keys.firstOrNull { it.id == keyId }?.label ?: "None")
                        },
                    )
                    DropdownMenu(expanded = keyMenu, onDismissRequest = { keyMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("None") },
                            onClick = {
                                keyId = null
                                keyMenu = false
                            },
                        )
                        keys.forEach { key ->
                            DropdownMenuItem(
                                text = { Text(key.label) },
                                onClick = {
                                    keyId = key.id
                                    keyMenu = false
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
                    Checkbox(checked = askEveryTime, onCheckedChange = { askEveryTime = it })
                    Text(
                        text = "Ask for the password every time",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = label.isNotBlank() && username.isNotBlank(),
                onClick = {
                    onSave(
                        (initial ?: Identity(label = label, username = username)).copy(
                            label = label.trim(),
                            username = username.trim(),
                            keyId = keyId,
                            askPasswordEveryTime = askEveryTime,
                        ),
                        password,
                        passwordTouched,
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Identity list plus sealing of stored passwords. */
@HiltViewModel
class IdentitiesViewModel @Inject constructor(
    private val hosts: HostRepository,
    keychain: KeychainRepository,
    private val vault: CryptoVault,
) : ViewModel() {

    data class UiState(
        val identities: List<Identity> = emptyList(),
        val keys: List<SshKey> = emptyList(),
    )

    val state: StateFlow<UiState> = combine(
        hosts.observeIdentities(),
        keychain.observeKeys(),
    ) { identities, keys ->
        UiState(identities = identities, keys = keys)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    fun save(identity: Identity, password: String, passwordTouched: Boolean) {
        viewModelScope.launch {
            val sealed = when {
                !passwordTouched -> identity.sealedPassword
                password.isEmpty() -> null
                else -> vault.seal(password)
            }
            hosts.saveIdentity(identity.copy(sealedPassword = sealed))
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch { hosts.deleteIdentity(id) }
    }
}
