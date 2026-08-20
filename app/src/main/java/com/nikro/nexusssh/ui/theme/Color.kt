package com.nikro.nexusssh.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Material 3 baseline palette generated from the brand seed `#3D6DF2` (Nexus blue).
 *
 * On Android 12+ the theme prefers wallpaper-derived dynamic colours; these tokens are the
 * fallback and are also used for the light/dark previews.
 */

// ---------------------------------------------------------------------------------- light scheme
val md_light_primary = Color(0xFF3B5FDA)
val md_light_onPrimary = Color(0xFFFFFFFF)
val md_light_primaryContainer = Color(0xFFDCE1FF)
val md_light_onPrimaryContainer = Color(0xFF001452)
val md_light_secondary = Color(0xFF585E71)
val md_light_onSecondary = Color(0xFFFFFFFF)
val md_light_secondaryContainer = Color(0xFFDDE1F9)
val md_light_onSecondaryContainer = Color(0xFF151B2C)
val md_light_tertiary = Color(0xFF00696E)
val md_light_onTertiary = Color(0xFFFFFFFF)
val md_light_tertiaryContainer = Color(0xFF6FF6FF)
val md_light_onTertiaryContainer = Color(0xFF002021)
val md_light_error = Color(0xFFBA1A1A)
val md_light_onError = Color(0xFFFFFFFF)
val md_light_errorContainer = Color(0xFFFFDAD6)
val md_light_onErrorContainer = Color(0xFF410002)
val md_light_background = Color(0xFFFEFBFF)
val md_light_onBackground = Color(0xFF1B1B1F)
val md_light_surface = Color(0xFFFEFBFF)
val md_light_onSurface = Color(0xFF1B1B1F)
val md_light_surfaceVariant = Color(0xFFE2E1EC)
val md_light_onSurfaceVariant = Color(0xFF45464F)
val md_light_outline = Color(0xFF757780)
val md_light_outlineVariant = Color(0xFFC5C6D0)
val md_light_inverseSurface = Color(0xFF303034)
val md_light_inverseOnSurface = Color(0xFFF2F0F4)
val md_light_inversePrimary = Color(0xFFB7C4FF)
val md_light_surfaceTint = md_light_primary
val md_light_scrim = Color(0xFF000000)

// ----------------------------------------------------------------------------------- dark scheme
val md_dark_primary = Color(0xFFB7C4FF)
val md_dark_onPrimary = Color(0xFF002585)
val md_dark_primaryContainer = Color(0xFF1E3FBA)
val md_dark_onPrimaryContainer = Color(0xFFDCE1FF)
val md_dark_secondary = Color(0xFFC1C5DD)
val md_dark_onSecondary = Color(0xFF2A3042)
val md_dark_secondaryContainer = Color(0xFF404659)
val md_dark_onSecondaryContainer = Color(0xFFDDE1F9)
val md_dark_tertiary = Color(0xFF4CD9E2)
val md_dark_onTertiary = Color(0xFF003739)
val md_dark_tertiaryContainer = Color(0xFF004F53)
val md_dark_onTertiaryContainer = Color(0xFF6FF6FF)
val md_dark_error = Color(0xFFFFB4AB)
val md_dark_onError = Color(0xFF690005)
val md_dark_errorContainer = Color(0xFF93000A)
val md_dark_onErrorContainer = Color(0xFFFFDAD6)
val md_dark_background = Color(0xFF1B1B1F)
val md_dark_onBackground = Color(0xFFE4E1E6)
val md_dark_surface = Color(0xFF13131A)
val md_dark_onSurface = Color(0xFFE4E1E6)
val md_dark_surfaceVariant = Color(0xFF45464F)
val md_dark_onSurfaceVariant = Color(0xFFC5C6D0)
val md_dark_outline = Color(0xFF8F9099)
val md_dark_outlineVariant = Color(0xFF45464F)
val md_dark_inverseSurface = Color(0xFFE4E1E6)
val md_dark_inverseOnSurface = Color(0xFF303034)
val md_dark_inversePrimary = Color(0xFF3B5FDA)
val md_dark_surfaceTint = md_dark_primary
val md_dark_scrim = Color(0xFF000000)

// ---------------------------------------------------------------------------- semantic accents
/** Connection status colours reused by chips, badges and the session list. */
object StatusColors {
    val connected = Color(0xFF2E9E5B)
    val connecting = Color(0xFFE0A21A)
    val reconnecting = Color(0xFFE0721A)
    val failed = Color(0xFFD64545)
    val idle = Color(0xFF8A8F98)
}

/** Palette offered when tagging hosts and groups with a colour. */
val hostColorSwatches = listOf(
    Color(0xFF3B5FDA),
    Color(0xFF00838F),
    Color(0xFF2E7D32),
    Color(0xFF9E7B00),
    Color(0xFFD84315),
    Color(0xFFAD1457),
    Color(0xFF6A1B9A),
    Color(0xFF37474F),
)
