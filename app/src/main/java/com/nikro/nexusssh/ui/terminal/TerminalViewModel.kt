package com.nikro.nexusssh.ui.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nikro.nexusssh.data.prefs.SettingsRepository
import com.nikro.nexusssh.data.repository.SnippetRepository
import com.nikro.nexusssh.domain.model.Snippet
import com.nikro.nexusssh.ssh.SshSessionManager
import com.nikro.nexusssh.terminal.TerminalKeyMapper
import com.nikro.nexusssh.terminal.TerminalSearch
import com.nikro.nexusssh.terminal.TerminalSession
import com.nikro.nexusssh.terminal.TerminalThemes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the terminal screen: tabs, keyboard state, search, and snippets.
 *
 * The sessions themselves live in [SshSessionManager], so this view model holds only what the UI
 * needs - which tab is on screen, whether Ctrl is armed, and the appearance from settings.
 */
@HiltViewModel
class TerminalViewModel @Inject constructor(
    private val sessions: SshSessionManager,
    private val settings: SettingsRepository,
    private val snippets: SnippetRepository,
) : ViewModel() {

    /** Everything the screen needs that is not the buffer itself. */
    data class UiState(
        val tabs: List<TabInfo> = emptyList(),
        val activeSessionId: String? = null,
        val ctrlActive: Boolean = false,
        val altActive: Boolean = false,
        val themeName: String = "Nexus Dark",
        val fontSizeSp: Int = 13,
        val keepScreenOn: Boolean = true,
        val bellVibrate: Boolean = true,
        val urlDetection: Boolean = true,
        val searchVisible: Boolean = false,
        val searchQuery: String = "",
        val searchMatches: Int = 0,
        val snippets: List<Snippet> = emptyList(),
    )

    data class TabInfo(val id: String, val title: String, val alive: Boolean)

    private val ctrlActive = MutableStateFlow(false)
    private val altActive = MutableStateFlow(false)
    private val searchVisible = MutableStateFlow(false)
    private val searchQuery = MutableStateFlow("")
    private val searchMatches = MutableStateFlow(0)

    private val tabs = sessions.sessions.map { list ->
        list.map { TabInfo(it.id, it.terminal.title.value.ifBlank { it.label }, it.isAlive) }
    }

    private val keyboardState = combine(
        ctrlActive,
        altActive,
        searchVisible,
        searchQuery,
        searchMatches,
    ) { ctrl, alt, visible, query, matches ->
        KeyboardState(ctrl, alt, visible, query, matches)
    }

    val state: StateFlow<UiState> = combine(
        tabs,
        sessions.activeSessionId,
        settings.settings,
        snippets.observeAll(),
        keyboardState,
    ) { tabList, activeId, appSettings, snippetList, keyboard ->
        UiState(
            tabs = tabList,
            activeSessionId = activeId,
            ctrlActive = keyboard.ctrl,
            altActive = keyboard.alt,
            themeName = appSettings.terminalTheme,
            fontSizeSp = appSettings.fontSizeSp,
            keepScreenOn = appSettings.keepScreenOn,
            bellVibrate = appSettings.bellVibrate,
            urlDetection = appSettings.urlDetection,
            searchVisible = keyboard.searchVisible,
            searchQuery = keyboard.query,
            searchMatches = keyboard.matches,
            snippets = snippetList,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), UiState())

    /** Pending password, passphrase, 2FA and host key questions. */
    val prompts: StateFlow<List<SshSessionManager.PendingPrompt>> = sessions.pendingPrompts

    fun session(id: String?): TerminalSession? = id?.let { sessions.terminal(it) }

    fun terminalTheme(name: String) = TerminalThemes.byName(name)

    // ---------------------------------------------------------------------------------------
    // Tabs
    // ---------------------------------------------------------------------------------------

    fun selectTab(sessionId: String) = sessions.setActive(sessionId)

    fun closeTab(sessionId: String) = sessions.close(sessionId)

    fun reconnect(sessionId: String) {
        viewModelScope.launch { sessions.reconnect(sessionId) }
    }

    // ---------------------------------------------------------------------------------------
    // Input
    // ---------------------------------------------------------------------------------------

    fun toggleCtrl() {
        ctrlActive.value = !ctrlActive.value
    }

    fun toggleAlt() {
        altActive.value = !altActive.value
    }

    /** Sends raw bytes from the extra-keys row. */
    fun sendKey(sessionId: String?, bytes: ByteArray) {
        session(sessionId)?.send(bytes)
    }

    /**
     * Sends a typed character, applying the armed modifiers.
     *
     * Ctrl and Alt are consumed after one character, which matches how a physical keyboard behaves
     * and avoids leaving the terminal in a state where every keystroke is a control code.
     */
    fun sendCharacter(sessionId: String?, codePoint: Int) {
        val session = session(sessionId) ?: return
        val bytes = TerminalKeyMapper.encodeCharacter(
            codePoint,
            ctrlActive.value,
            altActive.value,
        )
        session.send(bytes)
        if (ctrlActive.value) ctrlActive.value = false
        if (altActive.value) altActive.value = false
    }

    fun sendText(sessionId: String?, text: String) {
        session(sessionId)?.sendText(text)
    }

    fun paste(sessionId: String?, text: String) {
        session(sessionId)?.paste(text)
    }

    fun resize(sessionId: String?, columns: Int, rows: Int) {
        session(sessionId)?.resize(columns, rows)
    }

    fun scrollBy(sessionId: String?, lines: Int) {
        session(sessionId)?.scrollBy(lines)
    }

    fun copySelection(sessionId: String?): String? = session(sessionId)?.selectedText()

    fun clearSelection(sessionId: String?) {
        session(sessionId)?.clearSelection()
    }

    // ---------------------------------------------------------------------------------------
    // Search
    // ---------------------------------------------------------------------------------------

    fun toggleSearch() {
        searchVisible.value = !searchVisible.value
        if (!searchVisible.value) searchQuery.value = ""
    }

    fun onSearchQueryChange(sessionId: String?, query: String) {
        searchQuery.value = query
        val session = session(sessionId) ?: return
        searchMatches.value = if (query.isBlank()) {
            session.search.clear()
            0
        } else {
            session.runSearch(query, TerminalSearch.Options())
        }
    }

    fun searchNext(sessionId: String?) = session(sessionId)?.searchNext()

    fun searchPrevious(sessionId: String?) = session(sessionId)?.searchPrevious()

    // ---------------------------------------------------------------------------------------
    // Snippets
    // ---------------------------------------------------------------------------------------

    /** Runs a snippet, substituting any `${variable}` values the user filled in. */
    fun runSnippet(sessionId: String?, snippet: Snippet, values: Map<String, String> = emptyMap()) {
        val session = session(sessionId) ?: return
        val script = snippet.render(values)
        session.sendText(if (script.endsWith("\n")) script else script + "\n")
    }

    fun clearScrollback(sessionId: String?) {
        session(sessionId)?.emulator?.clearScreenAndScrollback()
    }

    private data class KeyboardState(
        val ctrl: Boolean,
        val alt: Boolean,
        val searchVisible: Boolean,
        val query: String,
        val matches: Int,
    )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
