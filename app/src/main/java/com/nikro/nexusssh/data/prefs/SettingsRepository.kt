package com.nikro.nexusssh.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nikro.nexusssh.domain.model.CursorStyle
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class KeyboardLayout { COMPACT, FULL, HIDDEN }

/** Everything the user can tweak in Settings, resolved into one immutable snapshot. */
data class AppSettings(
    // Appearance
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val amoledBlack: Boolean = false,
    // Terminal
    val terminalTheme: String = "Nexus Dark",
    val fontSizeSp: Int = 13,
    val fontFamily: String = "JetBrains Mono",
    val lineHeightMultiplier: Float = 1.15f,
    val cursorStyle: CursorStyle = CursorStyle.BLOCK,
    val cursorBlink: Boolean = true,
    val scrollbackLines: Int = 10_000,
    val keepScreenOn: Boolean = true,
    val bellVibrate: Boolean = true,
    val bellSound: Boolean = false,
    val mouseReporting: Boolean = true,
    val copyOnSelect: Boolean = false,
    val pasteOnMiddleClick: Boolean = true,
    val extraKeysRow: KeyboardLayout = KeyboardLayout.COMPACT,
    val ctrlKeyToggleSticky: Boolean = true,
    val urlDetection: Boolean = true,
    // Connections
    val defaultUsername: String = "",
    val defaultPort: Int = 22,
    val defaultKeepAliveSeconds: Int = 30,
    val defaultConnectTimeoutMs: Int = 15_000,
    val autoReconnect: Boolean = true,
    val maxReconnectAttempts: Int = 5,
    val keepSessionsAlive: Boolean = true,
    val confirmBeforeDisconnect: Boolean = true,
    val agentEnabled: Boolean = true,
    val agentConfirmEachUse: Boolean = true,
    // Security
    val biometricLock: Boolean = false,
    val autoLockMinutes: Int = 5,
    val lockOnBackground: Boolean = false,
    val hideSecretsInScreenshots: Boolean = true,
    val clipboardClearSeconds: Int = 45,
    // SFTP
    val sftpShowHidden: Boolean = false,
    val sftpPreserveTimestamps: Boolean = true,
    val sftpParallelTransfers: Int = 2,
    val sftpConfirmOverwrite: Boolean = true,
    // Misc
    val onboardingComplete: Boolean = false,
    val lastBackupAt: Long = 0L,
    val analyticsOptIn: Boolean = false,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "nexus_settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    val settings: Flow<AppSettings> = context.dataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { prefs -> prefs.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    private fun Preferences.toSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            themeMode = this[Keys.THEME_MODE]?.let { name ->
                runCatching { ThemeMode.valueOf(name) }.getOrDefault(defaults.themeMode)
            } ?: defaults.themeMode,
            dynamicColor = this[Keys.DYNAMIC_COLOR] ?: defaults.dynamicColor,
            amoledBlack = this[Keys.AMOLED] ?: defaults.amoledBlack,
            terminalTheme = this[Keys.TERMINAL_THEME] ?: defaults.terminalTheme,
            fontSizeSp = this[Keys.FONT_SIZE] ?: defaults.fontSizeSp,
            fontFamily = this[Keys.FONT_FAMILY] ?: defaults.fontFamily,
            lineHeightMultiplier = this[Keys.LINE_HEIGHT]?.let { it / 100f } ?: defaults.lineHeightMultiplier,
            cursorStyle = this[Keys.CURSOR_STYLE]?.let { name ->
                runCatching { CursorStyle.valueOf(name) }.getOrDefault(defaults.cursorStyle)
            } ?: defaults.cursorStyle,
            cursorBlink = this[Keys.CURSOR_BLINK] ?: defaults.cursorBlink,
            scrollbackLines = this[Keys.SCROLLBACK] ?: defaults.scrollbackLines,
            keepScreenOn = this[Keys.KEEP_SCREEN_ON] ?: defaults.keepScreenOn,
            bellVibrate = this[Keys.BELL_VIBRATE] ?: defaults.bellVibrate,
            bellSound = this[Keys.BELL_SOUND] ?: defaults.bellSound,
            mouseReporting = this[Keys.MOUSE_REPORTING] ?: defaults.mouseReporting,
            copyOnSelect = this[Keys.COPY_ON_SELECT] ?: defaults.copyOnSelect,
            pasteOnMiddleClick = this[Keys.PASTE_MIDDLE] ?: defaults.pasteOnMiddleClick,
            extraKeysRow = this[Keys.EXTRA_KEYS]?.let { name ->
                runCatching { KeyboardLayout.valueOf(name) }.getOrDefault(defaults.extraKeysRow)
            } ?: defaults.extraKeysRow,
            ctrlKeyToggleSticky = this[Keys.STICKY_CTRL] ?: defaults.ctrlKeyToggleSticky,
            urlDetection = this[Keys.URL_DETECTION] ?: defaults.urlDetection,
            defaultUsername = this[Keys.DEFAULT_USERNAME] ?: defaults.defaultUsername,
            defaultPort = this[Keys.DEFAULT_PORT] ?: defaults.defaultPort,
            defaultKeepAliveSeconds = this[Keys.KEEPALIVE] ?: defaults.defaultKeepAliveSeconds,
            defaultConnectTimeoutMs = this[Keys.CONNECT_TIMEOUT] ?: defaults.defaultConnectTimeoutMs,
            autoReconnect = this[Keys.AUTO_RECONNECT] ?: defaults.autoReconnect,
            maxReconnectAttempts = this[Keys.MAX_RECONNECT] ?: defaults.maxReconnectAttempts,
            keepSessionsAlive = this[Keys.KEEP_SESSIONS] ?: defaults.keepSessionsAlive,
            confirmBeforeDisconnect = this[Keys.CONFIRM_DISCONNECT] ?: defaults.confirmBeforeDisconnect,
            agentEnabled = this[Keys.AGENT_ENABLED] ?: defaults.agentEnabled,
            agentConfirmEachUse = this[Keys.AGENT_CONFIRM] ?: defaults.agentConfirmEachUse,
            biometricLock = this[Keys.BIOMETRIC_LOCK] ?: defaults.biometricLock,
            autoLockMinutes = this[Keys.AUTO_LOCK] ?: defaults.autoLockMinutes,
            lockOnBackground = this[Keys.LOCK_ON_BACKGROUND] ?: defaults.lockOnBackground,
            hideSecretsInScreenshots = this[Keys.SECURE_WINDOW] ?: defaults.hideSecretsInScreenshots,
            clipboardClearSeconds = this[Keys.CLIPBOARD_CLEAR] ?: defaults.clipboardClearSeconds,
            sftpShowHidden = this[Keys.SFTP_HIDDEN] ?: defaults.sftpShowHidden,
            sftpPreserveTimestamps = this[Keys.SFTP_TIMESTAMPS] ?: defaults.sftpPreserveTimestamps,
            sftpParallelTransfers = this[Keys.SFTP_PARALLEL] ?: defaults.sftpParallelTransfers,
            sftpConfirmOverwrite = this[Keys.SFTP_OVERWRITE] ?: defaults.sftpConfirmOverwrite,
            onboardingComplete = this[Keys.ONBOARDING] ?: defaults.onboardingComplete,
            lastBackupAt = this[Keys.LAST_BACKUP] ?: defaults.lastBackupAt,
            analyticsOptIn = this[Keys.ANALYTICS] ?: defaults.analyticsOptIn,
        )
    }

    // ------------------------------------------------------------------------------- mutators

    suspend fun setThemeMode(mode: ThemeMode) = put(Keys.THEME_MODE, mode.name)

    suspend fun setDynamicColor(enabled: Boolean) = put(Keys.DYNAMIC_COLOR, enabled)

    suspend fun setAmoledBlack(enabled: Boolean) = put(Keys.AMOLED, enabled)

    suspend fun setTerminalTheme(name: String) = put(Keys.TERMINAL_THEME, name)

    suspend fun setFontSize(sp: Int) = put(Keys.FONT_SIZE, sp.coerceIn(7, 32))

    suspend fun setFontFamily(family: String) = put(Keys.FONT_FAMILY, family)

    suspend fun setLineHeight(multiplier: Float) = put(Keys.LINE_HEIGHT, (multiplier * 100).toInt().coerceIn(90, 200))

    suspend fun setCursorStyle(style: CursorStyle) = put(Keys.CURSOR_STYLE, style.name)

    suspend fun setCursorBlink(enabled: Boolean) = put(Keys.CURSOR_BLINK, enabled)

    suspend fun setScrollback(lines: Int) = put(Keys.SCROLLBACK, lines.coerceIn(500, 200_000))

    suspend fun setKeepScreenOn(enabled: Boolean) = put(Keys.KEEP_SCREEN_ON, enabled)

    suspend fun setBellVibrate(enabled: Boolean) = put(Keys.BELL_VIBRATE, enabled)

    suspend fun setBellSound(enabled: Boolean) = put(Keys.BELL_SOUND, enabled)

    suspend fun setMouseReporting(enabled: Boolean) = put(Keys.MOUSE_REPORTING, enabled)

    suspend fun setCopyOnSelect(enabled: Boolean) = put(Keys.COPY_ON_SELECT, enabled)

    suspend fun setPasteOnMiddleClick(enabled: Boolean) = put(Keys.PASTE_MIDDLE, enabled)

    suspend fun setExtraKeysRow(layout: KeyboardLayout) = put(Keys.EXTRA_KEYS, layout.name)

    suspend fun setStickyCtrl(enabled: Boolean) = put(Keys.STICKY_CTRL, enabled)

    suspend fun setUrlDetection(enabled: Boolean) = put(Keys.URL_DETECTION, enabled)

    suspend fun setDefaultUsername(value: String) = put(Keys.DEFAULT_USERNAME, value)

    suspend fun setDefaultPort(value: Int) = put(Keys.DEFAULT_PORT, value.coerceIn(1, 65535))

    suspend fun setKeepAlive(seconds: Int) = put(Keys.KEEPALIVE, seconds.coerceIn(0, 600))

    suspend fun setConnectTimeout(ms: Int) = put(Keys.CONNECT_TIMEOUT, ms.coerceIn(1_000, 120_000))

    suspend fun setAutoReconnect(enabled: Boolean) = put(Keys.AUTO_RECONNECT, enabled)

    suspend fun setMaxReconnectAttempts(value: Int) = put(Keys.MAX_RECONNECT, value.coerceIn(0, 50))

    suspend fun setKeepSessionsAlive(enabled: Boolean) = put(Keys.KEEP_SESSIONS, enabled)

    suspend fun setConfirmDisconnect(enabled: Boolean) = put(Keys.CONFIRM_DISCONNECT, enabled)

    suspend fun setAgentEnabled(enabled: Boolean) = put(Keys.AGENT_ENABLED, enabled)

    suspend fun setAgentConfirm(enabled: Boolean) = put(Keys.AGENT_CONFIRM, enabled)

    suspend fun setBiometricLock(enabled: Boolean) = put(Keys.BIOMETRIC_LOCK, enabled)

    suspend fun setAutoLockMinutes(minutes: Int) = put(Keys.AUTO_LOCK, minutes.coerceIn(0, 240))

    suspend fun setLockOnBackground(enabled: Boolean) = put(Keys.LOCK_ON_BACKGROUND, enabled)

    suspend fun setSecureWindow(enabled: Boolean) = put(Keys.SECURE_WINDOW, enabled)

    suspend fun setClipboardClearSeconds(seconds: Int) = put(Keys.CLIPBOARD_CLEAR, seconds.coerceIn(0, 600))

    suspend fun setSftpShowHidden(enabled: Boolean) = put(Keys.SFTP_HIDDEN, enabled)

    suspend fun setSftpPreserveTimestamps(enabled: Boolean) = put(Keys.SFTP_TIMESTAMPS, enabled)

    suspend fun setSftpParallelTransfers(count: Int) = put(Keys.SFTP_PARALLEL, count.coerceIn(1, 6))

    suspend fun setSftpConfirmOverwrite(enabled: Boolean) = put(Keys.SFTP_OVERWRITE, enabled)

    suspend fun setOnboardingComplete(complete: Boolean) = put(Keys.ONBOARDING, complete)

    suspend fun setLastBackupAt(timestamp: Long) = put(Keys.LAST_BACKUP, timestamp)

    suspend fun setAnalyticsOptIn(enabled: Boolean) = put(Keys.ANALYTICS, enabled)

    suspend fun resetAll() {
        context.dataStore.edit { it.clear() }
    }

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { prefs -> prefs[key] = value }
    }

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val AMOLED = booleanPreferencesKey("amoled_black")
        val TERMINAL_THEME = stringPreferencesKey("terminal_theme")
        val FONT_SIZE = intPreferencesKey("font_size")
        val FONT_FAMILY = stringPreferencesKey("font_family")
        val LINE_HEIGHT = intPreferencesKey("line_height_pct")
        val CURSOR_STYLE = stringPreferencesKey("cursor_style")
        val CURSOR_BLINK = booleanPreferencesKey("cursor_blink")
        val SCROLLBACK = intPreferencesKey("scrollback")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val BELL_VIBRATE = booleanPreferencesKey("bell_vibrate")
        val BELL_SOUND = booleanPreferencesKey("bell_sound")
        val MOUSE_REPORTING = booleanPreferencesKey("mouse_reporting")
        val COPY_ON_SELECT = booleanPreferencesKey("copy_on_select")
        val PASTE_MIDDLE = booleanPreferencesKey("paste_middle_click")
        val EXTRA_KEYS = stringPreferencesKey("extra_keys_row")
        val STICKY_CTRL = booleanPreferencesKey("sticky_ctrl")
        val URL_DETECTION = booleanPreferencesKey("url_detection")
        val DEFAULT_USERNAME = stringPreferencesKey("default_username")
        val DEFAULT_PORT = intPreferencesKey("default_port")
        val KEEPALIVE = intPreferencesKey("keepalive_seconds")
        val CONNECT_TIMEOUT = intPreferencesKey("connect_timeout_ms")
        val AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        val MAX_RECONNECT = intPreferencesKey("max_reconnect")
        val KEEP_SESSIONS = booleanPreferencesKey("keep_sessions_alive")
        val CONFIRM_DISCONNECT = booleanPreferencesKey("confirm_disconnect")
        val AGENT_ENABLED = booleanPreferencesKey("agent_enabled")
        val AGENT_CONFIRM = booleanPreferencesKey("agent_confirm")
        val BIOMETRIC_LOCK = booleanPreferencesKey("biometric_lock")
        val AUTO_LOCK = intPreferencesKey("auto_lock_minutes")
        val LOCK_ON_BACKGROUND = booleanPreferencesKey("lock_on_background")
        val SECURE_WINDOW = booleanPreferencesKey("secure_window")
        val CLIPBOARD_CLEAR = intPreferencesKey("clipboard_clear_seconds")
        val SFTP_HIDDEN = booleanPreferencesKey("sftp_show_hidden")
        val SFTP_TIMESTAMPS = booleanPreferencesKey("sftp_preserve_timestamps")
        val SFTP_PARALLEL = intPreferencesKey("sftp_parallel")
        val SFTP_OVERWRITE = booleanPreferencesKey("sftp_confirm_overwrite")
        val ONBOARDING = booleanPreferencesKey("onboarding_complete")
        val LAST_BACKUP = longPreferencesKey("last_backup_at")
        val ANALYTICS = booleanPreferencesKey("analytics_opt_in")
    }
}
