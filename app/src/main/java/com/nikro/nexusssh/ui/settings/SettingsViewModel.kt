package com.nikro.nexusssh.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nikro.nexusssh.data.prefs.AppSettings
import com.nikro.nexusssh.data.prefs.KeyboardLayout
import com.nikro.nexusssh.data.prefs.SettingsRepository
import com.nikro.nexusssh.data.prefs.ThemeMode
import com.nikro.nexusssh.domain.model.CursorStyle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One view model behind every settings screen.
 *
 * Each setter writes straight through to DataStore: there is no "save" button, because a preference
 * that needs confirming is a preference people abandon halfway.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private fun edit(block: suspend SettingsRepository.() -> Unit) {
        viewModelScope.launch { repository.block() }
    }

    // Appearance -------------------------------------------------------------------------------
    fun setThemeMode(mode: ThemeMode) = edit { setThemeMode(mode) }
    fun setDynamicColor(enabled: Boolean) = edit { setDynamicColor(enabled) }
    fun setAmoledBlack(enabled: Boolean) = edit { setAmoledBlack(enabled) }

    // Terminal ---------------------------------------------------------------------------------
    fun setTerminalTheme(name: String) = edit { setTerminalTheme(name) }
    fun setFontSize(sp: Int) = edit { setFontSize(sp) }
    fun setFontFamily(family: String) = edit { setFontFamily(family) }
    fun setLineHeight(multiplier: Float) = edit { setLineHeight(multiplier) }
    fun setCursorStyle(style: CursorStyle) = edit { setCursorStyle(style) }
    fun setCursorBlink(enabled: Boolean) = edit { setCursorBlink(enabled) }
    fun setScrollback(lines: Int) = edit { setScrollback(lines) }
    fun setKeepScreenOn(enabled: Boolean) = edit { setKeepScreenOn(enabled) }
    fun setBellVibrate(enabled: Boolean) = edit { setBellVibrate(enabled) }
    fun setBellSound(enabled: Boolean) = edit { setBellSound(enabled) }
    fun setMouseReporting(enabled: Boolean) = edit { setMouseReporting(enabled) }
    fun setCopyOnSelect(enabled: Boolean) = edit { setCopyOnSelect(enabled) }
    fun setExtraKeysRow(layout: KeyboardLayout) = edit { setExtraKeysRow(layout) }
    fun setStickyCtrl(enabled: Boolean) = edit { setStickyCtrl(enabled) }
    fun setUrlDetection(enabled: Boolean) = edit { setUrlDetection(enabled) }

    // Connections ------------------------------------------------------------------------------
    fun setDefaultUsername(value: String) = edit { setDefaultUsername(value) }
    fun setDefaultPort(value: Int) = edit { setDefaultPort(value) }
    fun setKeepAlive(seconds: Int) = edit { setKeepAlive(seconds) }
    fun setConnectTimeout(ms: Int) = edit { setConnectTimeout(ms) }
    fun setAutoReconnect(enabled: Boolean) = edit { setAutoReconnect(enabled) }
    fun setMaxReconnectAttempts(value: Int) = edit { setMaxReconnectAttempts(value) }
    fun setKeepSessionsAlive(enabled: Boolean) = edit { setKeepSessionsAlive(enabled) }
    fun setConfirmDisconnect(enabled: Boolean) = edit { setConfirmDisconnect(enabled) }

    // Security ---------------------------------------------------------------------------------
    fun setAgentEnabled(enabled: Boolean) = edit { setAgentEnabled(enabled) }
    fun setAgentConfirm(enabled: Boolean) = edit { setAgentConfirm(enabled) }
    fun setBiometricLock(enabled: Boolean) = edit { setBiometricLock(enabled) }
    fun setAutoLockMinutes(minutes: Int) = edit { setAutoLockMinutes(minutes) }
    fun setLockOnBackground(enabled: Boolean) = edit { setLockOnBackground(enabled) }
    fun setSecureWindow(enabled: Boolean) = edit { setSecureWindow(enabled) }
    fun setClipboardClearSeconds(seconds: Int) = edit { setClipboardClearSeconds(seconds) }

    // Files ------------------------------------------------------------------------------------
    fun setSftpShowHidden(enabled: Boolean) = edit { setSftpShowHidden(enabled) }
    fun setSftpPreserveTimestamps(enabled: Boolean) = edit { setSftpPreserveTimestamps(enabled) }
    fun setSftpParallelTransfers(count: Int) = edit { setSftpParallelTransfers(count) }
    fun setSftpConfirmOverwrite(enabled: Boolean) = edit { setSftpConfirmOverwrite(enabled) }

    fun setOnboardingComplete(complete: Boolean) = edit { setOnboardingComplete(complete) }
}
