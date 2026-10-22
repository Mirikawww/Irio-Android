package com.irio

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.extendedspans.ExtendedSpanPainter
import com.mikepenz.markdown.compose.extendedspans.SpanDrawInstructions

internal val LocalPronunciationRecordings = staticCompositionLocalOf<Map<String, PronunciationRecording>> { emptyMap() }
internal val LocalPronunciationPlayback = staticCompositionLocalOf<((PronunciationRecording) -> Unit)?> { null }
internal const val PRONUNCIATION_SPAN = "irio_pronunciation"
internal const val PRONUNCIATION_ICON_PREFIX = "\u2003\u2060" // reserve an em; keep the icon with its word
private const val PRONUNCIATION_PREFIX = "irio-pronunciation:"

/** Only matching, verified recordings from this reply become playable. Ordinary links are retained. */
internal fun decoratePronunciationLinks(
    source: AnnotatedString,
    recordings: Map<String, PronunciationRecording>,
    color: Color,
    onPlay: (PronunciationRecording) -> Unit,
): AnnotatedString {
    val links = source.getLinkAnnotations(0, source.length)
    val audioLinks = links.filter { (it.item as? LinkAnnotation.Url)?.url?.startsWith(PRONUNCIATION_PREFIX) == true }
    if (audioLinks.isEmpty()) return source
    val verified = audioLinks.mapNotNull { link ->
        val id = (link.item as LinkAnnotation.Url).url.removePrefix(PRONUNCIATION_PREFIX)
        val recording = recordings[id] ?: return@mapNotNull null
        if (link.start >= link.end || pronunciationWord(source.text.substring(link.start, link.end)) != pronunciationWord(recording.word)) return@mapNotNull null
        link to recording
    }.sortedBy { it.first.start }
    val starts = verified.map { it.first.start }.toSet()
    fun mapped(offset: Int): Int = offset + starts.count { it < offset } * PRONUNCIATION_ICON_PREFIX.length
    val builder = AnnotatedString.Builder()
    source.text.forEachIndexed { index, char ->
        if (index in starts) builder.append(PRONUNCIATION_ICON_PREFIX)
        builder.append(char)
    }
    source.spanStyles.forEach { builder.addStyle(it.item, mapped(it.start), mapped(it.end)) }
    source.paragraphStyles.forEach { builder.addStyle(it.item, mapped(it.start), mapped(it.end)) }
    source.getStringAnnotations(0, source.length).forEach {
        builder.addStringAnnotation(it.tag, it.item, mapped(it.start), mapped(it.end))
    }
    links.filterNot { it in audioLinks }.forEach { range ->
        when (val item = range.item) {
            is LinkAnnotation.Url -> builder.addLink(item, mapped(range.start), mapped(range.end))
            is LinkAnnotation.Clickable -> builder.addLink(item, mapped(range.start), mapped(range.end))
        }
    }
    verified.forEach { (link, recording) ->
        val start = mapped(link.start)
        val end = mapped(link.end)
        val style = SpanStyle(color = color, textDecoration = TextDecoration.None)
        builder.addStyle(style, start, end)
        builder.addStringAnnotation(PRONUNCIATION_SPAN, recording.id, start, end)
        builder.addLink(LinkAnnotation.Clickable(
            tag = "pronunciation:${recording.id}", styles = TextLinkStyles(style = style),
            linkInteractionListener = LinkInteractionListener { onPlay(recording) },
        ), start, end)
    }
    return builder.toAnnotatedString()
}

/** Underline the word only; the speaker slot remains clickable without an underline. */
internal fun pronunciationUnderlineBounds(layout: TextLayoutResult, start: Int, end: Int): List<Rect> {
    if (start < 0 || start >= end || end > layout.layoutInput.text.length) return emptyList()
    val wordStart = start + PRONUNCIATION_ICON_PREFIX.length
    if (wordStart >= end) return emptyList()
    val firstLine = layout.getLineForOffset(wordStart)
    val lastLine = layout.getLineForOffset(end - 1)
    return (firstLine..lastLine).mapNotNull { line ->
        val from = maxOf(wordStart, layout.getLineStart(line))
        val until = minOf(end, layout.getLineEnd(line, visibleEnd = true))
        if (from >= until) return@mapNotNull null
        val boxes = (from until until).map(layout::getBoundingBox)
        Rect(boxes.minOf { it.left }, layout.getLineBaseline(line), boxes.maxOf { it.right }, layout.getLineBottom(line))
    }
}

internal class PronunciationSpanPainter(private val speaker: Painter, private val ink: Color) : ExtendedSpanPainter() {
    override fun decorate(span: SpanStyle, start: Int, end: Int, text: AnnotatedString, builder: AnnotatedString.Builder) = span
    override fun decorate(linkAnnotation: LinkAnnotation, start: Int, end: Int, text: AnnotatedString, builder: AnnotatedString.Builder) = linkAnnotation

    override fun drawInstructionsFor(layoutResult: TextLayoutResult, color: Color?): SpanDrawInstructions {
        val text = layoutResult.layoutInput.text
        val ranges = text.getStringAnnotations(PRONUNCIATION_SPAN, 0, text.length)
        return SpanDrawInstructions {
            ranges.forEach { range ->
                if (range.start >= range.end || range.end > text.length) return@forEach
                val box = layoutResult.getBoundingBox(range.start)
                val iconSize = minOf(box.width, box.height) * 0.85f
                val baseline = layoutResult.getLineBaseline(layoutResult.getLineForOffset(range.start))
                translate(box.left + (box.width - iconSize) / 2f, baseline - iconSize * 0.85f) {
                    with(speaker) { draw(androidx.compose.ui.geometry.Size(iconSize, iconSize), colorFilter = ColorFilter.tint(ink)) }
                }
                for (underline in pronunciationUnderlineBounds(layoutResult, range.start, range.end)) {
                    val y = minOf(underline.top + 2.dp.toPx(), underline.bottom - 1.dp.toPx())
                    drawLine(ink, Offset(underline.left, y), Offset(underline.right, y), strokeWidth = 1.dp.toPx(), cap = StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx())))
                }
            }
        }
    }
}
