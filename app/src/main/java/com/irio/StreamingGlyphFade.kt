package com.irio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isUnspecified
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.mikepenz.markdown.annotator.DefaultAnnotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.compose.LocalMarkdownAnnotator
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownTypography
import com.mikepenz.markdown.compose.LocalReferenceLinkHandler
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.elements.MarkdownText
import com.mikepenz.markdown.utils.codeSpanStyle
import kotlinx.coroutines.delay
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import kotlin.math.min

/** Agora's glyph fade: 2 alpha per second, so a new code point settles in 500ms. */
internal const val STREAM_TAIL_ALPHA_PER_SECOND = 2f
private const val STREAM_TAIL_FADE_TICK_MS = 40L

private val streamingClockBaseNs = System.nanoTime()

/** Monotonic milliseconds. Birth times only need to compare with each other. */
internal fun streamingNowMs(): Long =
    ((System.nanoTime() - streamingClockBaseNs) / 1_000_000L).coerceAtLeast(0L)

/**
 * Birth times of code points that have not reached opaque yet, aligned to the end of the
 * source string. Settled code points are dropped, so a later token cannot replay them.
 */
@Stable
internal class StreamingTailFadeSample(
    val birthTimesMs: LongArray,
)

/**
 * Append-only stream text keeps the births it already published. A rewrite (closed emphasis,
 * an inline formula becoming an image link) keeps the common prefix and births only the
 * diverging tail, instead of fading the whole paragraph again.
 */
internal class StreamingTailFadeTracker {
    private var previousText = ""
    private val fadingGlyphs = java.util.ArrayDeque<Long>()

    fun update(text: String, nowMs: Long): StreamingTailFadeSample {
        pruneSolidPrefix(nowMs)
        if (text != previousText) {
            val previousCodePoints = previousText.codePointCount(0, previousText.length)
            val nextCodePoints = text.codePointCount(0, text.length)
            val common = commonCodePointPrefixLength(previousText, text)
            val fadeStart = previousCodePoints - fadingGlyphs.size
            if (common <= fadeStart) {
                fadingGlyphs.clear()
            } else {
                var drop = previousCodePoints - common
                while (drop > 0 && fadingGlyphs.isNotEmpty()) {
                    fadingGlyphs.pollLast()
                    drop--
                }
            }
            repeat((nextCodePoints - common).coerceAtLeast(0)) {
                fadingGlyphs.addLast(nowMs)
            }
            previousText = text
        }
        val births = LongArray(fadingGlyphs.size)
        fadingGlyphs.forEachIndexed { index, birth -> births[index] = birth }
        return StreamingTailFadeSample(births)
    }

    private fun pruneSolidPrefix(nowMs: Long) {
        while (true) {
            val birth = fadingGlyphs.peekFirst() ?: return
            if (streamingGlyphAlpha(birth, nowMs) < 0.999f) return
            fadingGlyphs.pollFirst()
        }
    }
}

internal fun streamingGlyphAlpha(
    birthTimeMs: Long,
    nowMs: Long,
    alphaPerSecond: Float = STREAM_TAIL_ALPHA_PER_SECOND,
): Float {
    val elapsedSeconds = (nowMs - birthTimeMs).coerceAtLeast(0L) / 1_000f
    return (alphaPerSecond.coerceAtLeast(0f) * elapsedSeconds).coerceIn(0f, 1f)
}

internal fun commonCodePointPrefixLength(left: String, right: String): Int {
    val limit = min(left.length, right.length)
    var index = 0
    var count = 0
    while (index < limit) {
        val leftCodePoint = left.codePointAt(index)
        val rightCodePoint = right.codePointAt(index)
        if (leftCodePoint != rightCodePoint) break
        index += Character.charCount(leftCodePoint)
        count++
    }
    return count
}

/** Slice of [births] (the source tail) that overlaps one AST node's source range. */
internal fun nodeFade(
    source: String,
    nodeStart: Int,
    nodeEnd: Int,
    births: LongArray,
): StreamingGlyphNodeFade? {
    if (births.isEmpty() || nodeEnd <= nodeStart) return null
    val nodeStartCodePoints = source.codePointCount(0, nodeStart.coerceIn(0, source.length))
    val nodeEndCodePoints = source.codePointCount(0, nodeEnd.coerceIn(0, source.length))
    val totalCodePoints = source.codePointCount(0, source.length)
    val windowStart = totalCodePoints - births.size
    val overlapStart = maxOf(nodeStartCodePoints, windowStart)
    val overlapCount = nodeEndCodePoints - overlapStart
    if (overlapCount <= 0) return null
    val sliceStart = overlapStart - windowStart
    return StreamingGlyphNodeFade(
        tailCodePoints = overlapCount,
        birthTimesMs = births.copyOfRange(sliceStart, sliceStart + overlapCount),
    )
}

internal class StreamingGlyphNodeFade(
    val tailCodePoints: Int,
    val birthTimesMs: LongArray,
)

/**
 * Foreground alpha on an already-built Markdown string. Layout, emphasis, and links stay;
 * only glyphs that are still settling change color. Alpha 1 adds no span.
 */
internal fun streamingTailAnnotatedString(
    text: AnnotatedString,
    color: Color,
    birthTimesMs: LongArray,
    nowMs: Long,
    alphaPerSecond: Float = STREAM_TAIL_ALPHA_PER_SECOND,
): AnnotatedString {
    if (text.isEmpty() || birthTimesMs.isEmpty() || color.isUnspecified) return text
    val rawText = text.text
    val codePointCount = rawText.codePointCount(0, rawText.length)
    val fadedCount = min(codePointCount, birthTimesMs.size)
    if (fadedCount <= 0) return text
    val metadataStart = birthTimesMs.size - fadedCount
    val prefixCodePoints = codePointCount - fadedCount
    val builder = AnnotatedString.Builder().apply { append(text) }
    var rangeStartCodePoint = prefixCodePoints
    var rangeAlpha: Float? = null

    fun flushRange(endCodePoint: Int) {
        val alpha = rangeAlpha ?: return
        if (alpha < 0.999f) {
            builder.addStyle(
                SpanStyle(color = color.copy(alpha = color.alpha * alpha)),
                rawText.offsetByCodePoints(0, rangeStartCodePoint),
                rawText.offsetByCodePoints(0, endCodePoint),
            )
        }
    }

    for (suffixIndex in 0 until fadedCount) {
        val alpha = streamingGlyphAlpha(
            birthTimeMs = birthTimesMs[metadataStart + suffixIndex],
            nowMs = nowMs,
            alphaPerSecond = alphaPerSecond,
        )
        if (rangeAlpha == null) {
            rangeAlpha = alpha
        } else if (kotlin.math.abs(checkNotNull(rangeAlpha) - alpha) > 0.0001f) {
            flushRange(prefixCodePoints + suffixIndex)
            rangeStartCodePoint = prefixCodePoints + suffixIndex
            rangeAlpha = alpha
        }
    }
    flushRange(codePointCount)
    return builder.toAnnotatedString()
}

internal fun streamingTailFadeActive(
    birthTimesMs: LongArray,
    nowMs: Long,
): Boolean = birthTimesMs.any { streamingGlyphAlpha(it, nowMs) < 0.999f }

/** Null while this block is not the live tail. Settled messages never read a clock. */
internal val LocalStreamingGlyphFade = staticCompositionLocalOf<StreamingTailFadeSample?> { null }

/**
 * The clock lives in this leaf. A tick does not reparse Markdown or recompose the transcript.
 * [source] is the segment Markdown; births are sliced onto this node's range, then onto the
 * rendered string's tail when markers made the display text shorter.
 */
@Composable
internal fun rememberStreamingGlyphFade(
    content: AnnotatedString,
    color: Color,
    source: String,
    nodeStart: Int,
    nodeEnd: Int,
): AnnotatedString {
    val sample = LocalStreamingGlyphFade.current
    if (sample == null || content.isEmpty() || color.isUnspecified) return content
    val fade = remember(sample, source, nodeStart, nodeEnd) {
        nodeFade(source, nodeStart, nodeEnd, sample.birthTimesMs)
    } ?: return content
    val effective = remember(fade, content.text) {
        val displayCodePoints = content.text.codePointCount(0, content.text.length)
        val fadedCount = min(fade.tailCodePoints, displayCodePoints)
        when {
            fadedCount <= 0 -> null
            fadedCount == fade.birthTimesMs.size -> fade
            else -> StreamingGlyphNodeFade(
                tailCodePoints = fadedCount,
                birthTimesMs = fade.birthTimesMs.copyOfRange(
                    fade.birthTimesMs.size - fadedCount,
                    fade.birthTimesMs.size,
                ),
            )
        }
    } ?: return content
    var fadeClockMs by remember(effective) { mutableLongStateOf(streamingNowMs()) }
    LaunchedEffect(effective) {
        while (streamingTailFadeActive(effective.birthTimesMs, fadeClockMs)) {
            delay(STREAM_TAIL_FADE_TICK_MS)
            fadeClockMs = streamingNowMs()
        }
    }
    return remember(content, color, effective, fadeClockMs) {
        streamingTailAnnotatedString(
            text = content,
            color = color,
            birthTimesMs = effective.birthTimesMs,
            nowMs = fadeClockMs,
        )
    }
}

/**
 * Same annotated-string path as mikepenz [MarkdownParagraph] / [MarkdownHeader], plus the glyph
 * fade when [LocalStreamingGlyphFade] is set. The component stays mounted after the stream ends
 * so the paragraph is not disposed and flashed back in.
 */
@Composable
internal fun FadingMarkdownAnnotated(
    content: String,
    node: ASTNode,
    style: TextStyle,
    contentChildType: IElementType? = null,
) {
    val child = contentChildType?.let { node.findChildOfType(it) } ?: node
    val typography = LocalMarkdownTypography.current
    val linkStyle = typography.textLink
    val codeStyle = typography.codeSpanStyle
    val colors = LocalMarkdownColors.current
    val highlightStyle = SpanStyle(color = colors.highlightText, background = colors.highlightBackground)
    val annotator = LocalMarkdownAnnotator.current
    val references = LocalReferenceLinkHandler.current
    val uriHandler = LocalUriHandler.current
    val settings = remember(linkStyle, codeStyle, highlightStyle, annotator, references, uriHandler) {
        DefaultAnnotatorSettings(
            linkTextSpanStyle = linkStyle,
            codeSpanStyle = codeStyle,
            highlightSpanStyle = highlightStyle,
            annotator = annotator,
            referenceLinkHandler = references,
            linkInteractionListener = LinkInteractionListener { link ->
                val url = (link as? LinkAnnotation.Url)?.url ?: return@LinkInteractionListener
                val target = references?.find(url)?.takeIf { it.isNotEmpty() } ?: url
                runCatching { uriHandler.openUri(target) }
            },
        )
    }
    val styled = remember(content, child.startOffset, child.endOffset, style, settings) {
        buildAnnotatedString {
            pushStyle(style.toSpanStyle())
            buildMarkdownAnnotatedString(
                content = content,
                node = child,
                annotatorSettings = settings,
            )
            pop()
        }
    }
    val recordings = LocalPronunciationRecordings.current
    val context = LocalContext.current
    val suppliedPlayback = LocalPronunciationPlayback.current
    val playback = remember(context, suppliedPlayback) {
        suppliedPlayback ?: { recording: PronunciationRecording -> PronunciationPlayer.play(context, recording) }
    }
    val marked = remember(styled, recordings, style.color, playback) {
        decoratePronunciationLinks(styled, recordings, style.color, playback)
    }
    val rendered = rememberStreamingGlyphFade(
        content = marked,
        color = style.color,
        source = content,
        nodeStart = child.startOffset,
        nodeEnd = child.endOffset,
    )
    MarkdownText(
        content = rendered,
        node = node,
        style = style,
        sourceContent = content,
    )
}

@Composable
internal fun FadingMarkdownParagraph(model: MarkdownComponentModel) {
    FadingMarkdownAnnotated(
        content = model.content,
        node = model.node,
        style = model.typography.paragraph,
    )
}

/**
 * mikepenz only walks known element types. An ATX heading's title lives in
 * [MarkdownTokenTypes.ATX_CONTENT], which that walk drops, so annotating the
 * heading node itself draws a blank line. [com.mikepenz.markdown.compose.elements.MarkdownHeader]
 * defaults to the same child.
 */
internal fun headingInlineNode(
    node: ASTNode,
    contentChildType: IElementType = MarkdownTokenTypes.ATX_CONTENT,
): ASTNode = node.findChildOfType(contentChildType) ?: node

@Composable
internal fun FadingMarkdownHeading(
    model: MarkdownComponentModel,
    style: TextStyle,
    contentChildType: IElementType = MarkdownTokenTypes.ATX_CONTENT,
) {
    FadingMarkdownAnnotated(
        content = model.content,
        node = headingInlineNode(model.node, contentChildType),
        style = style,
    )
}
