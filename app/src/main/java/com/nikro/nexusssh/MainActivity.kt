package com.nikro.nexusssh

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.hilt.navigation.compose.hiltViewModel
import com.nikro.nexusssh.data.prefs.AppSettings
import com.nikro.nexusssh.data.prefs.SettingsRepository
import com.nikro.nexusssh.ui.NexusApp
import com.nikro.nexusssh.ui.theme.NexusTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * The only activity.
 *
 * It resolves the theme before the first frame, applies the screenshot-blocking flag when the user
 * asked for it, and hands any deep link (`ssh://`, `sftp://`, `nexusssh://`) to the navigation host.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    private var deepLink by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        deepLink = intent?.data?.toString()

        var settingsLoaded = false
        splash.setKeepOnScreenCondition { !settingsLoaded }

        setContent {
            val viewModel: AppViewModel = hiltViewModel()
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            settingsLoaded = true

            // Terminals show credentials often enough that this is worth honouring immediately.
            if (settings.hideSecretsInScreenshots) {
                window.setFlags(
                    WindowManager.LayoutParams.FLAG_SECURE,
                    WindowManager.LayoutParams.FLAG_SECURE,
                )
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }

            NexusTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
                amoled = settings.amoledBlack,
            ) {
                NexusApp(
                    settings = settings,
                    deepLink = deepLink,
                    onDeepLinkHandled = { deepLink = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLink = intent.data?.toString()
    }
}

/** Holds the settings snapshot the whole app reads from. */
@HiltViewModel
class AppViewModel @Inject constructor(
    repository: SettingsRepository,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = repository.settings.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        AppSettings(),
    )
}
