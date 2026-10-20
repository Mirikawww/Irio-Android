package com.irio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.isUnspecified
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.shapes.RoundedRectangle
import com.kyant.shapes.UnevenRoundedRectangle
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import com.composables.ui.theme.backgroundColor
import com.composables.ui.theme.colors
import com.composables.ui.theme.onBackgroundColor
import com.composeunstyled.theme.Theme
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownBulletList
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownOrderedList
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableBasicText
import com.mikepenz.markdown.compose.elements.listDepth
import com.mikepenz.markdown.compose.extendedspans.ExtendedSpanPainter
import com.mikepenz.markdown.compose.extendedspans.ExtendedSpans
import com.mikepenz.markdown.compose.extendedspans.SpanDrawInstructions
import com.mikepenz.markdown.compose.extendedspans.internal.update
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.model.ImageWidth
import com.mikepenz.markdown.model.MarkdownState
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.PlaceholderConfig
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.markdownExtendedSpans
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import androidx.compose.ui.geometry.Size
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxThemes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.CELL
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.TABLE_SEPARATOR
import org.intellij.markdown.parser.MarkdownParser

@Composable
internal fun IrioMarkdown(
    content: String,
    typography: MarkdownTypography,
    imageTransformer: ImageTransformer,
    // Pre-parsed state hoisted above the LazyColumn (scroll-back reuse).
    // Null = parse inline, same as before.
    state: MarkdownState? = null,
    glyphFade: Boolean = false,
) {
    val onBg = Theme[colors][onBackgroundColor]
    val dark = LocalResolvedTheme.current.isDark
    val highlightsBuilder = remember(dark) {
        Highlights.Builder().theme(SyntaxThemes.atom(darkMode = dark))
    }
    val colors = markdownColor(
        text = onBg,
        codeBackground = onBg.copy(alpha = 0.08f),
        inlineCodeBackground = onBg.copy(alpha = 0.12f),
        dividerColor = onBg.copy(alpha = 0.18f),
        tableBackground = onBg.copy(alpha = 0.04f),
        darkTheme = dark,
    )
    val inlineSize = typography.inlineCode.fontSize
    val extendedSpans = markdownExtendedSpans {
        remember(inlineSize) {
            ExtendedSpans(IrioInlineCodeSpanPainter(fontSize = inlineSize))
        }
    }
    val components = markdownComponents(
        checkbox = { IrioMarkdownCheckBox(it.content, it.node) },
        orderedList = {
            MarkdownOrderedList(
                content = it.content,
                node = it.node,
                style = it.typography.ordered,
                depth = it.listDepth,
                markerModifier = { Modifier.alignByBaseline() },
                listModifier = { Modifier.alignByBaseline().weight(1f) },
            )
        },
        unorderedList = {
            MarkdownBulletList(
                content = it.content,
                node = it.node,
                style = it.typography.bullet,
                depth = it.listDepth,
                markerModifier = { Modifier.alignByBaseline() },
                listModifier = { Modifier.alignByBaseline().weight(1f) },
            )
        },
        table = { IrioMarkdownTable(it.content, it.node, it.typography.table) },
        codeFence = {
            MarkdownHighlightedCodeFence(
                content = it.content,
                node = it.node,
                style = it.typography.code,
                highlightsBuilder = highlightsBuilder,
                showHeader = true,
                softWrap = ThemeStore.codeBlockWrap,
                lineNumbers = ThemeStore.codeBlockLineNumbers,
            )
        },
        codeBlock = {
            MarkdownHighlightedCodeBlock(
                content = it.content,
                node = it.node,
                style = it.typography.code,
                highlightsBuilder = highlightsBuilder,
                showHeader = true,
                softWrap = ThemeStore.codeBlockWrap,
                lineNumbers = ThemeStore.codeBlockLineNumbers,
            )
        },
        // Always this path, not only while streaming. Swapping back to MarkdownParagraph
        // when the reply settles disposes the text and flashes it.
        paragraph = { FadingMarkdownParagraph(it) },
        heading1 = { FadingMarkdownHeading(it, it.typography.h1) },
        heading2 = { FadingMarkdownHeading(it, it.typography.h2) },
        heading3 = { FadingMarkdownHeading(it, it.typography.h3) },
        heading4 = { FadingMarkdownHeading(it, it.typography.h4) },
        heading5 = { FadingMarkdownHeading(it, it.typography.h5) },
        heading6 = { FadingMarkdownHeading(it, it.typography.h6) },
        setextHeading1 = {
            FadingMarkdownHeading(it, it.typography.h1, MarkdownTokenTypes.SETEXT_CONTENT)
        },
        setextHeading2 = {
            FadingMarkdownHeading(it, it.typography.h2, MarkdownTokenTypes.SETEXT_CONTENT)
        },
    )
    // Lock the renderer on first composition. Switching from Markdown(content)
    // to Markdown(markdownState) — or falling back to raw text while a cache
    // fills — disposes the paragraph and flashes it. A cache hit at entry
    // (scroll-back) skips parsing; a live tail stays on one retained parser.
    val entryState = remember { state }
    val flavour = remember { GFMFlavourDescriptor() }
    val parser = remember(flavour) { MarkdownParser(flavour) }
    // Default animations are animateContentSize on every MarkdownText. A streaming
    // tail changes that size on almost every line, so the paragraph bounds tween
    // open. Snap, including when a formula bitmap replaces its placeholder.
    val snapTextSize = remember<Modifier.() -> Modifier> { { this } }
    val animations = markdownAnimations(animateTextSize = snapTextSize)
    val glyphFadeTracker = remember { StreamingTailFadeTracker() }
    val trackedFade = remember(content) { glyphFadeTracker.update(content, streamingNowMs()) }
    val glyphFadeSample = if (glyphFade) trackedFade else null
    CompositionLocalProvider(LocalStreamingGlyphFade provides glyphFadeSample) {
        if (entryState != null) {
            Markdown(
                markdownState = entryState,
                colors = colors,
                typography = typography,
                modifier = Modifier.fillMaxWidth(),
                imageTransformer = imageTransformer,
                extendedSpans = extendedSpans,
                components = components,
                animations = animations,
            )
        } else {
            // retainState: a new token must not reset this block to Loading (empty box).
            // immediate: the first frame is parsed before it is shown. A remembered parser
            // keeps later parent recompositions from looking like a new document.
            val live = rememberMarkdownState(
                content = content,
                retainState = true,
                flavour = flavour,
                parser = parser,
                immediate = true,
            )
            Markdown(
                markdownState = live,
                colors = colors,
                typography = typography,
                modifier = Modifier.fillMaxWidth(),
                imageTransformer = imageTransformer,
                extendedSpans = extendedSpans,
                components = components,
                animations = animations,
            )
        }
    }
}

internal class IrioImageTransformer(
    private val tex: ImageTransformer,
) : ImageTransformer {

    @Composable
    override fun transform(link: String): ImageData? {
        if (link.startsWith(TEX_LINK_PREFIX)) return tex.transform(link)
        return Coil3ImageTransformerImpl.transform(link)
    }

    @Composable
    override fun intrinsicSize(painter: androidx.compose.ui.graphics.painter.Painter): Size {
        return Coil3ImageTransformerImpl.intrinsicSize(painter)
    }

    override fun placeholderConfig(
        link: String,
        density: Density,
        containerSize: Size,
        imageWidth: ImageWidth,
        imageSize: Size,
        imageSizeChanged: ((String, Size) -> Unit)?,
    ): PlaceholderConfig {
        return if (link.startsWith(TEX_LINK_PREFIX)) {
            tex.placeholderConfig(link, density, containerSize, imageWidth, imageSize, imageSizeChanged)
        } else {
            super.placeholderConfig(link, density, containerSize, imageWidth, imageSize, imageSizeChanged)
        }
    }
}

@Composable
private fun IrioMarkdownCheckBox(content: String, node: ASTNode) {
    val checked = node.getTextInNode(content).contains("[x]", ignoreCase = true)
    val stroke = Theme[colors][onBackgroundColor]
    val fill = Theme[colors][backgroundColor]
    Box(
        modifier = Modifier
            .padding(end = 8.dp)
            .semantics {
                role = Role.Checkbox
                stateDescription = if (checked) tr("已勾选") else tr("未勾选")
            },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Text(
            text = "\u00A0",
            fontSize = 17.sp,
            lineHeight = 17.sp,
            color = Color.Transparent,
        )
        Canvas(Modifier.size(18.dp)) {
            val box = RoundedRectangle(2.dp)
            val strokeWidth = 1.5.dp.toPx()
            if (checked) {
                drawContinuous(stroke, box, style = Fill)
                val check = Path().apply {
                    moveTo(size.width * 0.22f, size.height * 0.52f)
                    lineTo(size.width * 0.42f, size.height * 0.72f)
                    lineTo(size.width * 0.78f, size.height * 0.28f)
                }
                drawPath(
                    path = check,
                    color = fill,
                    style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            } else {
                drawContinuous(stroke, box, style = Stroke(width = strokeWidth))
            }
        }
    }
}

@Composable
private fun IrioMarkdownTable(content: String, node: ASTNode, style: TextStyle) {
    val separator = node.children.firstOrNull { it.type == TABLE_SEPARATOR }
    val alignments = remember(content, node) {
        separator?.let { parseTableAlignments(it.getUnescapedTextInNode(content)) }.orEmpty()
    }
    MarkdownTable(
        content = content,
        node = node,
        style = style,
        headerBlock = { tableContent, header, tableWidth, tableStyle ->
            IrioTableRow(
                content = tableContent,
                row = header,
                tableWidth = tableWidth,
                style = tableStyle.copy(fontWeight = FontWeight.Bold),
                alignments = alignments,
                header = true,
            )
        },
        rowBlock = { tableContent, row, tableWidth, tableStyle ->
            IrioTableRow(
                content = tableContent,
                row = row,
                tableWidth = tableWidth,
                style = tableStyle,
                alignments = alignments,
                header = false,
            )
        },
    )
}

@Composable
private fun IrioTableRow(
    content: String,
    row: ASTNode,
    tableWidth: androidx.compose.ui.unit.Dp,
    style: TextStyle,
    alignments: List<MarkdownTableAlign>,
    header: Boolean,
) {
    val tableCellPadding = LocalMarkdownDimens.current.tableCellPadding
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.widthIn(tableWidth).then(
            if (header) Modifier.height(IntrinsicSize.Max) else Modifier,
        ),
    ) {
        row.children.filter { it.type == CELL }.forEachIndexed { index, cell ->
            val align = alignments.getOrNull(index) ?: MarkdownTableAlign.START
            val textAlign = when (align) {
                MarkdownTableAlign.START -> TextAlign.Start
                MarkdownTableAlign.CENTER -> TextAlign.Center
                MarkdownTableAlign.END -> TextAlign.End
            }
            val horizontal = when (align) {
                MarkdownTableAlign.START -> Alignment.Start
                MarkdownTableAlign.CENTER -> Alignment.CenterHorizontally
                MarkdownTableAlign.END -> Alignment.End
            }
            Column(
                modifier = Modifier.padding(tableCellPadding).weight(1f),
                horizontalAlignment = horizontal,
            ) {
                MarkdownTableBasicText(
                    content = content,
                    cell = cell,
                    style = style.copy(textAlign = textAlign),
                    maxLines = Int.MAX_VALUE,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}

internal fun irioInlineCodeStyle(): TextStyle = TextStyle(
    fontSize = 14.sp,
    lineHeight = 14.sp,
    fontFamily = FontFamily.Monospace,
)

internal fun irioCodeBlockStyle(): TextStyle = TextStyle(
    fontSize = 13.sp,
    lineHeight = 20.sp,
    fontFamily = FontFamily.Monospace,
)

/**
 * [RoundedCornerSpanPainter] uses the paragraph line box (Irio body is 30sp).
 * 14sp inline code sits low in that box, so equal insets leave a gap above
 * and clip the glyphs at the bottom. Draw from the line baseline instead.
 */
private class IrioInlineCodeSpanPainter(
    private val fontSize: TextUnit,
    private val cornerRadius: TextUnit = 4.sp,
    private val horizontalPadding: TextUnit = 4.sp,
    private val verticalPadding: TextUnit = 4.sp,
) : ExtendedSpanPainter() {

    override fun decorate(
        span: SpanStyle,
        start: Int,
        end: Int,
        text: AnnotatedString,
        builder: AnnotatedString.Builder,
    ): SpanStyle {
        return if (span.background.isUnspecified) {
            span
        } else {
            builder.addStringAnnotation(TAG, annotation = span.background.toArgb().toString(), start = start, end = end)
            span.copy(background = Color.Unspecified)
        }
    }

    override fun decorate(
        linkAnnotation: LinkAnnotation,
        start: Int,
        end: Int,
        text: AnnotatedString,
        builder: AnnotatedString.Builder,
    ): LinkAnnotation {
        val defaultStyle = linkAnnotation.styles?.style
        if (defaultStyle == null || defaultStyle.background.isUnspecified) return linkAnnotation
        builder.addStringAnnotation(TAG, annotation = defaultStyle.background.toArgb().toString(), start = start, end = end)
        val updatedTextLinkStyles = linkAnnotation.styles?.update { copy(background = Color.Unspecified) }
        return when (linkAnnotation) {
            is LinkAnnotation.Url -> {
                LinkAnnotation.Url(linkAnnotation.url, updatedTextLinkStyles, linkAnnotation.linkInteractionListener)
            }
            is LinkAnnotation.Clickable -> {
                LinkAnnotation.Clickable(linkAnnotation.tag, updatedTextLinkStyles, linkAnnotation.linkInteractionListener)
            }
            else -> linkAnnotation
        }
    }

    override fun drawInstructionsFor(layoutResult: TextLayoutResult, color: Color?): SpanDrawInstructions {
        val text = layoutResult.layoutInput.text
        val annotations = text.getStringAnnotations(TAG, start = 0, end = text.length)
        return SpanDrawInstructions {
            val radius = with(this) { cornerRadius.toPx().toDp() }
            val fontPx = fontSize.toPx()
            val padH = horizontalPadding.toPx()
            val padV = verticalPadding.toPx()
            annotations.fastForEach { annotation ->
                val backgroundColor = annotation.item.toIntOrNull()?.let(::Color) ?: return@fastForEach
                val boxes = layoutResult.inlineCodeBoxes(
                    startOffset = annotation.start,
                    endOffset = annotation.end,
                    fontPx = fontPx,
                    padH = padH,
                    padV = padV,
                )
                boxes.fastForEachIndexed { index, box ->
                    val left = if (index == 0) radius else 0.dp
                    val right = if (index == boxes.lastIndex) radius else 0.dp
                    val background = UnevenRoundedRectangle(
                        topStart = left,
                        topEnd = right,
                        bottomEnd = right,
                        bottomStart = left,
                    ).continuousPath(Size(box.width, box.height), LayoutDirection.Ltr, this).apply {
                        translate(Offset(box.left, box.top))
                    }
                    drawPath(path = background, color = backgroundColor, style = Fill)
                }
            }
        }
    }

    private fun TextLayoutResult.inlineCodeBoxes(
        startOffset: Int,
        endOffset: Int,
        fontPx: Float,
        padH: Float,
        padV: Float,
    ): List<Rect> {
        if (startOffset == endOffset) return emptyList()
        val startLine = getLineForOffset(startOffset)
        val endLine = getLineForOffset(endOffset)
        val isLtr = multiParagraph.getParagraphDirection(offset = layoutInput.text.lastIndex) ==
            ResolvedTextDirection.Ltr
        return (startLine..endLine).map { lineNum ->
            val baseline = getLineBaseline(lineNum)
            val left = if (lineNum == startLine) {
                getHorizontalPosition(startOffset, usePrimaryDirection = isLtr)
            } else {
                getLineLeft(lineNum)
            }
            val right = if (lineNum == endLine) {
                getHorizontalPosition(endOffset, usePrimaryDirection = isLtr)
            } else {
                getLineRight(lineNum)
            }
            Rect(
                left = left - padH,
                top = baseline - fontPx - padV,
                right = right + padH,
                bottom = baseline + fontPx * 0.3f + padV,
            )
        }
    }

    companion object {
        private const val TAG = "irio_inline_code"
    }
}
