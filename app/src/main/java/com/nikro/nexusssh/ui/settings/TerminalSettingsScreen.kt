package com.nikro.nexusssh.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nikro.nexusssh.data.prefs.KeyboardLayout
import com.nikro.nexusssh.domain.model.CursorStyle
import com.nikro.nexusssh.terminal.TerminalThemes
import com.nikro.nexusssh.ui.components.SectionHeader

/**
 * Terminal appearance and input behaviour.
 *
 * The theme row previews the actual palette, because names like "Gruvbox" mean nothing until you
 * see the background and the first eight ANSI colours next to each other.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Terminal") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader("Colour scheme")
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(TerminalThemes.all.size) { index ->
                    val theme = TerminalThemes.all[index]
                    val selected = settings.terminalTheme == theme.name
                    Column(
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    ) {
                        Surface(
                            color = Color(theme.background),
                            shape = MaterialTheme.shapes.medium,
                            border = if (selected) {
                                androidx.compose.foundation.BorderStroke(
                                    2.dp,
                                    MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                null
                            },
                            modifier = Modifier
                                .padding(vertical = 8.dp)
                                .clickable(onClick = { viewModel.setTerminalTheme(theme.name) }),
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Text(
                                    text = "user@host:~$",
                                    color = Color(theme.foreground),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                )
                                Row(
                                    modifier = Modifier.padding(top = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    theme.ansi.take(8).forEach { colour ->
                                        Surface(
                                            color = Color(colour),
                                            modifier = Modifier
                                                .padding(0.dp)
                                                .size(10.dp),
                                            shape = MaterialTheme.shapes.extraSmall,
                                        ) {}
                                    }
                                }
                            }
                        }
                        Text(
                            text = theme.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }

            HorizontalDivider()
            SectionHeader("Text")
            SliderRow(
                title = "Font size",
                value = settings.fontSizeSp.toFloat(),
                range = 7f..32f,
                steps = 24,
                valueLabel = "${settings.fontSizeSp} sp",
                onValueChange = { viewModel.setFontSize(it.toInt()) },
            )
            SliderRow(
                title = "Line height",
                value = settings.lineHeightMultiplier,
                range = 0.9f..2.0f,
                steps = 21,
                valueLabel = String.format(java.util.Locale.US, "%.2f\u00d7", settings.lineHeightMultiplier),
                onValueChange = viewModel::setLineHeight,
            )
            SliderRow(
                title = "Scrollback",
                value = settings.scrollbackLines.toFloat(),
                range = 500f..100_000f,
                steps = 0,
                valueLabel = "${settings.scrollbackLines} lines",
                onValueChange = { viewModel.setScrollback(it.toInt()) },
            )

            HorizontalDivider()
            SectionHeader("Cursor")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CursorStyle.entries.forEach { style ->
                    FilterChip(
                        selected = settings.cursorStyle == style,
                        onClick = { viewModel.setCursorStyle(style) },
                        label = { Text(style.name.lowercase().replaceFirstChar(Char::uppercase)) },
                    )
                }
            }
            SwitchRow(
                title = "Blink",
                subtitle = null,
                checked = settings.cursorBlink,
                onCheckedChange = viewModel::setCursorBlink,
            )

            HorizontalDivider()
            SectionHeader("Keys and input")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                KeyboardLayout.entries.forEach { layout ->
                    FilterChip(
                        selected = settings.extraKeysRow == layout,
                        onClick = { viewModel.setExtraKeysRow(layout) },
                        label = {
                            Text(layout.name.lowercase().replaceFirstChar(Char::uppercase))
                        },
                    )
                }
            }
            SwitchRow(
                title = "Sticky Ctrl and Alt",
                subtitle = "Tap once to arm the modifier for the next key",
                checked = settings.ctrlKeyToggleSticky,
                onCheckedChange = viewModel::setStickyCtrl,
            )
            SwitchRow(
                title = "Mouse reporting",
                subtitle = "Let full-screen programs handle taps and scrolling",
                checked = settings.mouseReporting,
                onCheckedChange = viewModel::setMouseReporting,
            )
            SwitchRow(
                title = "Copy on select",
                subtitle = "Put a selection on the clipboard as soon as it ends",
                checked = settings.copyOnSelect,
                onCheckedChange = viewModel::setCopyOnSelect,
            )
            SwitchRow(
                title = "Detect links",
                subtitle = "Underline URLs, paths and addresses in the output",
                checked = settings.urlDetection,
                onCheckedChange = viewModel::setUrlDetection,
            )

            HorizontalDivider()
            SectionHeader("Behaviour")
            SwitchRow(
                title = "Keep the screen on",
                subtitle = "While a terminal is in the foreground",
                checked = settings.keepScreenOn,
                onCheckedChange = viewModel::setKeepScreenOn,
            )
            SwitchRow(
                title = "Vibrate on bell",
                subtitle = null,
                checked = settings.bellVibrate,
                onCheckedChange = viewModel::setBellVibrate,
            )
            SwitchRow(
                title = "Sound on bell",
                subtitle = null,
                checked = settings.bellSound,
                onCheckedChange = viewModel::setBellSound,
            )
        }
    }
}
