package com.nikro.nexusssh.ui.snippets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nikro.nexusssh.data.repository.SnippetRepository
import com.nikro.nexusssh.domain.model.Snippet
import com.nikro.nexusssh.ui.components.ConfirmDialog
import com.nikro.nexusssh.ui.components.EmptyState
import com.nikro.nexusssh.ui.components.MetaChip
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Saved commands.
 *
 * A snippet is a small script with optional `{{placeholders}}`; the terminal fills them in before
 * sending. This is what turns "the command I always forget" into one tap, which is the whole point
 * of having a client instead of typing into a raw shell.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnippetsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SnippetsViewModel = hiltViewModel(),
) {
    val snippets by viewModel.snippets.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Snippet?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Snippet?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Snippets") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) {
                Icon(Icons.Rounded.Add, contentDescription = "New snippet")
            }
        },
    ) { padding ->
        if (snippets.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.PlayArrow,
                title = "No snippets",
                description = "Save the commands you keep retyping and run them with one tap.",
                modifier = Modifier.padding(padding),
                actionLabel = "New snippet",
                onAction = { creating = true },
            )
        } else {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                items(snippets, key = { it.id }) { snippet ->
                    ListItem(
                        headlineContent = { Text(snippet.name) },
                        supportingContent = {
                            Column {
                                Text(
                                    text = snippet.script.lineSequence().first().take(80),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(
                                    modifier = Modifier.padding(top = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    val lines = snippet.script.lines().size
                                    if (lines > 1) MetaChip("$lines lines")
                                    val variables = snippet.variables
                                    if (variables.isNotEmpty()) {
                                        MetaChip("${variables.size} variables")
                                    }
                                    if (snippet.runInBackground) MetaChip("background")
                                    snippet.tags.take(2).forEach { tag -> MetaChip(tag) }
                                }
                            }
                        },
                        trailingContent = {
                            IconButton(onClick = { deleting = snippet }) {
                                Icon(Icons.Rounded.Delete, contentDescription = "Delete")
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { editing = snippet },
                    )
                }
            }
        }
    }

    if (creating || editing != null) {
        SnippetDialog(
            initial = editing,
            onDismiss = {
                creating = false
                editing = null
            },
            onSave = { snippet ->
                creating = false
                editing = null
                viewModel.save(snippet)
            },
        )
    }

    deleting?.let { snippet ->
        ConfirmDialog(
            title = "Delete snippet?",
            message = "\"${snippet.name}\" will be removed. Hosts that ran it on connect fall " +
                "back to doing nothing.",
            confirmLabel = "Delete",
            onConfirm = {
                viewModel.delete(snippet.id)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun SnippetDialog(
    initial: Snippet?,
    onDismiss: () -> Unit,
    onSave: (Snippet) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var script by remember { mutableStateOf(initial?.script ?: "") }
    var description by remember { mutableStateOf(initial?.description ?: "") }
    var tags by remember { mutableStateOf(initial?.tags?.joinToString(", ") ?: "") }
    var background by remember { mutableStateOf(initial?.runInBackground ?: false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New snippet" else "Edit snippet") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = script,
                    onValueChange = { script = it },
                    label = { Text("Script") },
                    minLines = 3,
                    maxLines = 8,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                Text(
                    text = "Placeholders use dollar-brace syntax and are asked for before the snippet runs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    label = { Text("Tags, comma separated") },
                    singleLine = true,
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
                    Checkbox(checked = background, onCheckedChange = { background = it })
                    Text(
                        text = "Run without showing the output",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && script.isNotBlank(),
                onClick = {
                    val parsedTags = tags.split(',')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    onSave(
                        (initial ?: Snippet(name = name, script = script)).copy(
                            name = name.trim(),
                            script = script,
                            description = description.trim(),
                            tags = parsedTags,
                            runInBackground = background,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Snippet list with create, update and delete. */
@HiltViewModel
class SnippetsViewModel @Inject constructor(
    private val repository: SnippetRepository,
) : ViewModel() {

    val snippets: StateFlow<List<Snippet>> = repository.observeAll()
        .map { list -> list.sortedBy { it.name.lowercase() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun save(snippet: Snippet) {
        viewModelScope.launch { repository.save(snippet) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { repository.delete(id) }
    }
}
