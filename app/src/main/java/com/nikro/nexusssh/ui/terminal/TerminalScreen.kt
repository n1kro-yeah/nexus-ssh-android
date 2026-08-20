package com.nikro.nexusssh.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nikro.nexusssh.terminal.TerminalKeyMapper
import com.nikro.nexusssh.ui.components.EmptyState

/**
 * The terminal tab.
 *
 * Sessions are tabs across the top, the buffer is painted by [TerminalView], and input arrives from
 * three places: the soft keyboard, the extra-keys row, and snippets. A hidden text field is what
 * makes the soft keyboard appear at all - Android has no other way to raise it for a canvas.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    onOpenHosts: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TerminalViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prompts by viewModel.prompts.collectAsStateWithLifecycle()
    val activeId = state.activeSessionId
    val session = viewModel.session(activeId)
    val theme = viewModel.terminalTheme(state.themeName)
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var menuOpen by remember { mutableStateOf(false) }
    var snippetsOpen by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }

    // Keeping the screen awake is the expected behaviour while a session is open.
    val view = LocalView.current
    DisposableEffect(state.keepScreenOn, activeId) {
        view.keepScreenOn = state.keepScreenOn && activeId != null
        onDispose { view.keepScreenOn = false }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.tabs.firstOrNull { it.id == activeId }?.title ?: "Terminal",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    IconButton(onClick = viewModel::toggleSearch) {
                        Icon(Icons.Rounded.Search, contentDescription = "Find")
                    }
                    IconButton(onClick = { snippetsOpen = true }) {
                        Icon(Icons.Rounded.List, contentDescription = "Snippets")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Reconnect") },
                                onClick = {
                                    menuOpen = false
                                    activeId?.let(viewModel::reconnect)
                                },
                                leadingIcon = {
                                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Clear scrollback") },
                                onClick = {
                                    menuOpen = false
                                    viewModel.clearScrollback(activeId)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Close session") },
                                onClick = {
                                    menuOpen = false
                                    activeId?.let(viewModel::closeTab)
                                },
                                leadingIcon = {
                                    Icon(Icons.Rounded.Clear, contentDescription = null)
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .imePadding(),
        ) {
            if (state.tabs.size > 1) {
                val selected = state.tabs.indexOfFirst { it.id == activeId }.coerceAtLeast(0)
                ScrollableTabRow(selectedTabIndex = selected, edgePadding = 8.dp) {
                    state.tabs.forEachIndexed { index, tab ->
                        Tab(
                            selected = index == selected,
                            onClick = { viewModel.selectTab(tab.id) },
                            text = {
                                Text(
                                    text = tab.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (tab.alive) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                )
                            },
                        )
                    }
                }
            }

            if (state.searchVisible) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    TextField(
                        value = state.searchQuery,
                        onValueChange = { viewModel.onSearchQueryChange(activeId, it) },
                        placeholder = { Text("Find in buffer") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        trailingIcon = {
                            Text(
                                text = if (state.searchQuery.isBlank()) {
                                    ""
                                } else {
                                    state.searchMatches.toString()
                                },
                                style = MaterialTheme.typography.labelMedium,
                            )
                        },
                    )
                    IconButton(onClick = { viewModel.searchPrevious(activeId) }) {
                        Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Previous match")
                    }
                    IconButton(onClick = { viewModel.searchNext(activeId) }) {
                        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Next match")
                    }
                }
            }

            if (session == null) {
                EmptyState(
                    icon = Icons.Rounded.List,
                    title = "No open sessions",
                    description = "Connect to a host to open a terminal here.",
                    actionLabel = "Go to hosts",
                    onAction = onOpenHosts,
                )
                return@Column
            }

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .background(Color(theme.background)),
            ) {
                TerminalView(
                    session = session,
                    theme = theme,
                    fontSizeSp = state.fontSizeSp.toFloat(),
                    modifier = Modifier.fillMaxSize(),
                    onGridChanged = { columns, rows -> viewModel.resize(activeId, columns, rows) },
                    onLinkTapped = { link -> viewModel.sendText(activeId, link.text) },
                    onTapped = { keyboard?.show() },
                )

                // Invisible field: it owns the IME connection, the canvas owns the pixels.
                BasicTextField(
                    value = input,
                    onValueChange = { typed ->
                        if (typed.isNotEmpty()) {
                            if (state.ctrlActive || state.altActive) {
                                typed.codePoints().forEach { codePoint ->
                                    viewModel.sendCharacter(activeId, codePoint)
                                }
                            } else {
                                viewModel.sendText(activeId, typed)
                            }
                        }
                        input = ""
                    },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier
                        .size(1.dp)
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            val native = event.nativeKeyEvent
                            val bytes = TerminalKeyMapper.map(
                                native.keyCode,
                                event.isShiftPressed,
                                event.isAltPressed || state.altActive,
                                event.isCtrlPressed || state.ctrlActive,
                                session.emulator.applicationCursorKeys,
                                session.emulator.applicationKeypad,
                                session.config.backspaceMode,
                            )
                            when {
                                bytes != null -> {
                                    session.send(bytes)
                                    true
                                }

                                (state.ctrlActive || state.altActive) && event.utf16CodePoint > 0 -> {
                                    viewModel.sendCharacter(activeId, event.utf16CodePoint)
                                    true
                                }

                                else -> false
                            }
                        },
                )
            }

            ExtraKeysRow(
                ctrlActive = state.ctrlActive,
                altActive = state.altActive,
                onToggleCtrl = viewModel::toggleCtrl,
                onToggleAlt = viewModel::toggleAlt,
                onKey = { bytes -> viewModel.sendKey(activeId, bytes) },
            )
        }
    }

    LaunchedEffect(activeId) {
        if (activeId != null) focusRequester.requestFocus()
    }

    prompts.firstOrNull()?.let { pending -> SshPromptDialog(pending) }

    if (snippetsOpen) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { snippetsOpen = false },
            sheetState = sheetState,
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text(
                    text = "Snippets",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
                state.snippets.forEach { snippet ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(snippet.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = snippet.script.lineSequence().first().take(60),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        onClick = {
                            snippetsOpen = false
                            viewModel.runSnippet(activeId, snippet)
                        },
                    )
                }
            }
        }
    }
}
