package com.irio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.ui.components.Text
import com.kyant.shapes.RoundedRectangle

/** Text copied/read aloud includes bubbles in their visible chronological order. */
internal fun ChatMessage.visibleText(): String {
    return if (fromUser) text else visibleTranscriptText(text, process)
}

/** Combine ordinary assistant text and colored bubbles in their display order. */
internal fun visibleTranscriptText(text: String, process: List<ProcessEvent>): String {
    if (process.none { it.kind == ProcessEventKind.BUBBLE }) return text
    return interleaveTranscript(text, process).mapNotNull { piece ->
        when (piece) {
            is TranscriptPiece.Text -> piece.text.trim().takeIf { it.isNotEmpty() }
            is TranscriptPiece.Event -> piece.event.takeIf { it.kind == ProcessEventKind.BUBBLE }?.content
        }
    }.joinToString("\n\n")
}

/** Previous named colors remain readable in saved conversations; new calls use arbitrary RGB. */
internal fun normalizeColoredBubbleColor(value: String): String? {
    val color = value.trim()
    val legacy = when (color.lowercase()) {
        "pink" -> "#A82462"
        "blue" -> "#2258A5"
        "purple" -> "#7830A8"
        "green" -> "#226B42"
        "yellow" -> "#885400"
        "orange" -> "#A24415"
        "red" -> "#A32B35"
        else -> null
    }
    if (legacy != null) return legacy
    if (!color.matches(Regex("#[0-9a-fA-F]{3}([0-9a-fA-F]{3})?"))) return null
    return if (color.length == 4) "#" + color.drop(1).flatMap { listOf(it, it) }.joinToString("").uppercase()
    else color.uppercase()
}

internal fun coloredBubbleColors(color: String, dark: Boolean): UserBubbleColors {
    val rgb = normalizeColoredBubbleColor(color) ?: "#7830A8"
    val hue = Color(0xFF000000L or rgb.drop(1).toLong(16))
    val background = if (dark) hue.mixOver(Color(0xFF141414), 0.28f) else hue.softened(0.12f)
    val originalText = if (dark) hue.softened(0.32f) else hue
    val contrastTarget = if (dark) Color.White else Color.Black
    var content = originalText
    var amount = 0f
    // Very bright/light hues need darker text; arbitrary RGB must remain readable.
    while (wcagContrastRatio(background, content) < 4.5f && amount < 1f) {
        amount = (amount + 0.05f).coerceAtMost(1f)
        content = contrastTarget.mixOver(originalText, amount)
    }
    return UserBubbleColors(background, content)
}

@Composable
internal fun ColoredBubbleView(event: ProcessEvent, onQuote: (String) -> Unit) {
    val colors = coloredBubbleColors(event.bubbleColor, LocalResolvedTheme.current.isDark)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        QuoteSelectionContainer(onQuote = onQuote) {
            Text(
                event.content,
                modifier = Modifier.widthIn(max = maxWidth * 0.9f)
                    .background(colors.background, RoundedRectangle(22.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                color = colors.content, fontSize = 17.sp, lineHeight = 26.sp,
            )
        }
    }
}
