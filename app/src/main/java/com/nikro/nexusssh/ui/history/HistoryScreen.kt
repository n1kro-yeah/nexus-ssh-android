package com.nikro.nexusssh.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nikro.nexusssh.data.repository.HistoryRepository
import com.nikro.nexusssh.domain.model.ConnectionHistoryEntry
import com.nikro.nexusssh.ui.components.ConfirmDialog
import com.nikro.nexusssh.ui.components.EmptyState
import com.nikro.nexusssh.ui.components.MetaChip
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Connection log.
 *
 * Keeps duration, traffic and the error text for every attempt. When a host "sometimes doesn't
 * work", this is the only screen that can tell you whether it fails at DNS, at the handshake or at
 * authentication, and how often.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    var clearing by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("History") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (entries.isNotEmpty()) {
                        IconButton(onClick = { clearing = true }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Clear history")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.DateRange,
                title = "No connections yet",
                description = "Every session is logged here with its duration, traffic and errors.",
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                items(entries, key = { it.id }) { entry ->
                    ListItem(
                        headlineContent = { Text(entry.label) },
                        supportingContent = {
                            Column {
                                Text(
                                    text = entry.address,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(
                                    modifier = Modifier.padding(top = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    MetaChip(formatTimestamp(entry.startedAt))
                                    entry.durationMs?.let { MetaChip(formatDuration(it)) }
                                    val traffic = entry.bytesIn + entry.bytesOut
                                    if (traffic > 0) MetaChip(formatBytes(traffic))
                                }
                                entry.errorMessage?.let { message ->
                                    Text(
                                        text = message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                }
                            }
                        },
                        leadingContent = {
                            if (entry.succeeded) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = "Succeeded",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Failed",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    if (clearing) {
        ConfirmDialog(
            title = "Clear history?",
            message = "All connection records are deleted. Hosts, keys and settings are untouched.",
            confirmLabel = "Clear",
            onConfirm = {
                clearing = false
                viewModel.clear()
            },
            onDismiss = { clearing = false },
        )
    }
}

private fun formatTimestamp(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))

private fun formatDuration(millis: Long): String {
    val hours = TimeUnit.MILLISECONDS.toHours(millis)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return String.format(java.util.Locale.US, "%.1f %s", value, units[index])
}

/** Recent connection records. */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repository: HistoryRepository,
) : ViewModel() {

    val entries: StateFlow<List<ConnectionHistoryEntry>> = repository.observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun clear() {
        viewModelScope.launch { repository.clear() }
    }
}
