package com.nikro.nexusssh.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.nikro.nexusssh.data.prefs.ThemeMode

private val LightColors: ColorScheme = lightColorScheme(
    primary = md_light_primary,
    onPrimary = md_light_onPrimary,
    primaryContainer = md_light_primaryContainer,
    onPrimaryContainer = md_light_onPrimaryContainer,
    inversePrimary = md_light_inversePrimary,
    secondary = md_light_secondary,
    onSecondary = md_light_onSecondary,
    secondaryContainer = md_light_secondaryContainer,
    onSecondaryContainer = md_light_onSecondaryContainer,
    tertiary = md_light_tertiary,
    onTertiary = md_light_onTertiary,
    tertiaryContainer = md_light_tertiaryContainer,
    onTertiaryContainer = md_light_onTertiaryContainer,
    background = md_light_background,
    onBackground = md_light_onBackground,
    surface = md_light_surface,
    onSurface = md_light_onSurface,
    surfaceVariant = md_light_surfaceVariant,
    onSurfaceVariant = md_light_onSurfaceVariant,
    surfaceTint = md_light_surfaceTint,
    inverseSurface = md_light_inverseSurface,
    inverseOnSurface = md_light_inverseOnSurface,
    error = md_light_error,
    onError = md_light_onError,
    errorContainer = md_light_errorContainer,
    onErrorContainer = md_light_onErrorContainer,
    outline = md_light_outline,
    outlineVariant = md_light_outlineVariant,
    scrim = md_light_scrim,
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = md_dark_primary,
    onPrimary = md_dark_onPrimary,
    primaryContainer = md_dark_primaryContainer,
    onPrimaryContainer = md_dark_onPrimaryContainer,
    inversePrimary = md_dark_inversePrimary,
    secondary = md_dark_secondary,
    onSecondary = md_dark_onSecondary,
    secondaryContainer = md_dark_secondaryContainer,
    onSecondaryContainer = md_dark_onSecondaryContainer,
    tertiary = md_dark_tertiary,
    onTertiary = md_dark_onTertiary,
    tertiaryContainer = md_dark_tertiaryContainer,
    onTertiaryContainer = md_dark_onTertiaryContainer,
    background = md_dark_background,
    onBackground = md_dark_onBackground,
    surface = md_dark_surface,
    onSurface = md_dark_onSurface,
    surfaceVariant = md_dark_surfaceVariant,
    onSurfaceVariant = md_dark_onSurfaceVariant,
    surfaceTint = md_dark_surfaceTint,
    inverseSurface = md_dark_inverseSurface,
    inverseOnSurface = md_dark_inverseOnSurface,
    error = md_dark_error,
    onError = md_dark_onError,
    errorContainer = md_dark_errorContainer,
    onErrorContainer = md_dark_onErrorContainer,
    outline = md_dark_outline,
    outlineVariant = md_dark_outlineVariant,
    scrim = md_dark_scrim,
)

/**
 * The app theme.
 *
 * Dynamic colour is on by default on Android 12 and later, because a terminal app spends most of
 * its time showing someone else's colours and the chrome should stay out of the way. The AMOLED
 * option pushes the dark scheme to true black for OLED screens, which is a common request from
 * people who use a terminal at night.
 */
@Composable
fun NexusTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    amoled: Boolean = false,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val supportsDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val scheme = when {
        dynamicColor && supportsDynamic && darkTheme -> dynamicDarkColorScheme(context)
        dynamicColor && supportsDynamic -> dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }.let { base ->
        if (darkTheme && amoled) {
            base.copy(
                background = Color.Black,
                surface = Color.Black,
                surfaceContainerLowest = Color.Black,
                surfaceContainerLow = Color(0xFF0A0A0A),
                surfaceContainer = Color(0xFF121212),
            )
        } else {
            base
        }
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = scheme,
        typography = NexusTypography,
        content = content,
    )
}
