package com.nikro.nexusssh.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.nikro.nexusssh.terminal.TerminalLinkDetector
import com.nikro.nexusssh.terminal.TerminalSession
import com.nikro.nexusssh.terminal.TerminalTheme

/**
 * Compose renderer for the current terminal viewport.
 *
 * The terminal core owns the real screen and scrollback buffers. This view observes its frame
 * counter, renders only the visible rows and reports a conservative character grid whenever its
 * bounds change. The separation keeps protocol parsing independent from Compose and makes session
 * resizing deterministic.
 */
@Composable
fun TerminalView(
    session: TerminalSession,
    theme: TerminalTheme,
    fontSizeSp: Float,
    modifier: Modifier = Modifier,
    onGridChanged: (columns: Int, rows: Int) -> Unit = { _, _ -> },
    onLinkTapped: (TerminalLinkDetector.Link) -> Unit = {},
    onTapped: () -> Unit = {},
) {
    val frame by session.frame.collectAsState()
    val scrollOffset by session.scrollOffset.collectAsState()
    val density = LocalDensity.current

    // `frame` intentionally keys this snapshot: TerminalSession increments it after each decoded
    // SSH chunk, coalescing many emulator mutations into a single Compose recomposition.
    val lines = remember(session, frame, scrollOffset) {
        val emulator = session.emulator
        val buffer = emulator.buffer
        val start = (buffer.viewportTop - scrollOffset).coerceAtLeast(0)
        List(emulator.rows) { row ->
            val absoluteLine = start + row
            if (absoluteLine < buffer.totalLines) {
                buffer.line(absoluteLine).text(0, emulator.columns, trimTrailing = true)
            } else {
                ""
            }
        }
    }

    @Suppress("UNUSED_VARIABLE")
    val linkHandler = onLinkTapped

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .background(Color(theme.background))
            .clickable(onClick = onTapped)
            .onSizeChanged { size ->
                // JetBrains Mono is close to 0.61em wide. The emulator corrects the final grid
                // after the SSH PTY resize, so this deliberately errs on the conservative side.
                val cellWidth = (fontSizeSp * density.density * 0.61f).coerceAtLeast(1f)
                val cellHeight = (fontSizeSp * density.density * 1.22f).coerceAtLeast(1f)
                val columns = (size.width / cellWidth).toInt().coerceAtLeast(8)
                val rows = (size.height / cellHeight).toInt().coerceAtLeast(2)
                if (columns != session.emulator.columns || rows != session.emulator.rows) {
                    onGridChanged(columns, rows)
                }
            },
    ) {
        Column(Modifier.fillMaxSize()) {
            lines.forEach { line ->
                Text(
                    text = line,
                    color = Color(theme.foreground),
                    fontFamily = FontFamily.Monospace,
                    fontSize = fontSizeSp.sp,
                    lineHeight = (fontSizeSp * 1.18f).sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
