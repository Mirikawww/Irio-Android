/*
 * Copyright 2022 Mike Penz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Vendored from mikepenz/multiplatform-markdown-renderer v0.46.0.
 * Modified by Irio: code fences and blocks accept softWrap. When it is true,
 * lines wrap to the block width instead of scrolling horizontally.
 * Line numbers stay on the source line: a wrapped row does not get a new number.
 */
package com.mikepenz.markdown.compose.elements

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.LocalMarkdownTypography
import com.mikepenz.markdown.compose.components.MarkdownComponent
import com.mikepenz.markdown.compose.elements.material.MarkdownBasicText
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxThemes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.intellij.markdown.ast.ASTNode

/** Default definition for the [MarkdownHighlightedCodeFence]. Uses default theme, attempts to apply language from markdown. */
val highlightedCodeFence: MarkdownComponent = {
    MarkdownHighlightedCodeFence(content = it.content, node = it.node, style = it.typography.code)
}

/** Default definition for the [MarkdownHighlightedCodeBlock]. Uses default theme, attempts to apply language from markdown. */
val highlightedCodeBlock: MarkdownComponent = {
    MarkdownHighlightedCodeBlock(content = it.content, node = it.node, style = it.typography.code)
}

@Composable
fun MarkdownHighlightedCodeFence(
    content: String,
    node: ASTNode,
    style: TextStyle = LocalMarkdownTypography.current.code,
    highlightsBuilder: Highlights.Builder = rememberHighlightsBuilder(),
    showHeader: Boolean = false,
    immediate: Boolean = LocalInspectionMode.current,
    softWrap: Boolean = false,
    lineNumbers: Boolean = false,
) {
    MarkdownCodeFence(content, node, style) { code, language, style ->
        MarkdownHighlightedCode(
            code = code,
            language = language,
            style = style,
            highlightsBuilder = highlightsBuilder,
            showHeader = showHeader,
            immediate = immediate,
            softWrap = softWrap,
            lineNumbers = lineNumbers,
        )
    }
}

@Composable
fun MarkdownHighlightedCodeBlock(
    content: String,
    node: ASTNode,
    style: TextStyle = LocalMarkdownTypography.current.code,
    highlightsBuilder: Highlights.Builder = rememberHighlightsBuilder(),
    showHeader: Boolean = false,
    immediate: Boolean = LocalInspectionMode.current,
    softWrap: Boolean = false,
    lineNumbers: Boolean = false,
) {
    MarkdownCodeBlock(content, node, style) { code, language, style ->
        MarkdownHighlightedCode(
            code = code,
            language = language,
            style = style,
            highlightsBuilder = highlightsBuilder,
            showHeader = showHeader,
            immediate = immediate,
            softWrap = softWrap,
            lineNumbers = lineNumbers,
        )
    }
}

@Composable
fun MarkdownHighlightedCode(
    code: String,
    language: String?,
    style: TextStyle = LocalMarkdownTypography.current.code,
    highlightsBuilder: Highlights.Builder = rememberHighlightsBuilder(),
    showHeader: Boolean = false,
    immediate: Boolean = false,
    softWrap: Boolean = false,
    lineNumbers: Boolean = false,
) {
    val backgroundCodeColor = LocalMarkdownColors.current.codeBackground
    val codeBackgroundCornerSize = LocalMarkdownDimens.current.codeBackgroundCornerSize
    val codeBlockPadding = LocalMarkdownPadding.current.codeBlock
    val codeHighlights: AnnotatedString by produceHighlightsState(
        code = code,
        language = language,
        highlightsBuilder = highlightsBuilder,
        immediate = immediate,
    )
    val resolvedStyle = if (softWrap && style.lineBreak == LineBreak.Unspecified) {
        style.copy(lineBreak = WrappedCodeLineBreak)
    } else {
        style
    }
    val scroll = rememberScrollState()

    MarkdownCodeBackground(
        color = backgroundCodeColor,
        shape = RoundedCornerShape(codeBackgroundCornerSize),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        showHeader = showHeader,
        language = language,
        code = code
    ) {
        if (lineNumbers) {
            NumberedCodeText(
                code = code,
                text = codeHighlights,
                style = resolvedStyle,
                softWrap = softWrap,
                padding = codeBlockPadding,
            )
        } else {
            MarkdownBasicText(
                text = codeHighlights,
                style = resolvedStyle,
                modifier = Modifier
                    .then(
                        if (softWrap) Modifier.fillMaxWidth()
                        else Modifier.horizontalScroll(scroll)
                    )
                    .padding(codeBlockPadding),
            )
        }
    }
}

private val CodeLineNumberGap = 12.dp

@Composable
private fun NumberedCodeText(
    code: String,
    text: AnnotatedString,
    style: TextStyle,
    softWrap: Boolean,
    padding: PaddingValues,
) {
    val layoutDirection = LocalLayoutDirection.current
    val numberStyle = style.copy(
        color = LocalMarkdownColors.current.text.copy(alpha = 0.45f),
        textAlign = TextAlign.End,
    )
    val measurer = rememberTextMeasurer()
    val lineCount = remember(code) { codeLineStartOffsets(code).size }
    val numberBaseline = remember(numberStyle) {
        measurer.measure(AnnotatedString("8"), style = numberStyle, maxLines = 1, softWrap = false).firstBaseline
    }
    val gutterPx = remember(lineCount, numberStyle) {
        measurer.measure(
            AnnotatedString(lineCount.toString()),
            style = numberStyle,
            maxLines = 1,
            softWrap = false,
        ).size.width
    }
    val scroll = rememberScrollState()
    var cachedBaselines by remember(code) { mutableStateOf(IntArray(0)) }
    SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
        val startPx = padding.calculateStartPadding(layoutDirection).roundToPx()
        val topPx = padding.calculateTopPadding().roundToPx()
        val gapPx = CodeLineNumberGap.roundToPx()
        val codeMaxWidth = (constraints.maxWidth - startPx - gutterPx - gapPx).coerceAtLeast(0)
        var baselines = IntArray(0)
        val codePlaceable = subcompose("code") {
            MarkdownBasicText(
                text = text,
                style = style,
                softWrap = softWrap,
                onTextLayout = { result ->
                    val next = codeLineBaselines(
                        text = code,
                        lineForOffset = result::getLineForOffset,
                        baselineOfLine = { line -> result.getLineBaseline(line).roundToInt() },
                    )
                    baselines = next
                    if (!next.contentEquals(cachedBaselines)) cachedBaselines = next
                },
                modifier = Modifier
                    .then(if (softWrap) Modifier else Modifier.horizontalScroll(scroll))
                    .padding(top = padding.calculateTopPadding(), end = padding.calculateEndPadding(layoutDirection), bottom = padding.calculateBottomPadding()),
            )
        }.first().measure(
            Constraints(maxWidth = codeMaxWidth, maxHeight = constraints.maxHeight),
        )
        val placedBaselines = if (baselines.isNotEmpty()) baselines else cachedBaselines
        val numberPlaceables = if (placedBaselines.isEmpty()) {
            emptyList()
        } else {
            subcompose("numbers") {
                placedBaselines.indices.forEach { index ->
                    MarkdownBasicText(
                        text = AnnotatedString((index + 1).toString()),
                        style = numberStyle,
                        softWrap = false,
                        maxLines = 1,
                    )
                }
            }.map { it.measure(Constraints()) }
        }
        val width = constraints.maxWidth
        val height = codePlaceable.height
        layout(width, height) {
            codePlaceable.place(startPx + gutterPx + gapPx, 0)
            numberPlaceables.forEachIndexed { index, placeable ->
                val baseline = placedBaselines.getOrElse(index) { 0 }
                placeable.place(
                    startPx + gutterPx - placeable.width,
                    topPx + codeLineNumberTop(baseline, numberBaseline.roundToInt()),
                )
            }
        }
    }
}

/** Loose breaks so wrapped code can split after punctuation, not only spaces. */
private val WrappedCodeLineBreak = LineBreak(
    strategy = LineBreak.Strategy.Simple,
    strictness = LineBreak.Strictness.Loose,
    wordBreak = LineBreak.WordBreak.Default,
)

@Composable
private fun rememberHighlightsBuilder(
    isDarkTheme: Boolean = isSystemInDarkTheme(),
): Highlights.Builder {
    return remember(isDarkTheme) {
        Highlights.Builder().theme(SyntaxThemes.default(darkMode = isDarkTheme))
    }
}

@Composable
private fun produceHighlightsState(
    code: String,
    language: String?,
    highlightsBuilder: Highlights.Builder,
    immediate: Boolean,
): State<AnnotatedString> {
    if (immediate) {
        val highlighted = remember(code) {
            buildHighlightedAnnotatedString(code, language, highlightsBuilder)
        }
        return rememberUpdatedState(highlighted)
    }

    return produceState(
        initialValue = AnnotatedString(text = code),
        key1 = code,
    ) {
        val job = launch(Dispatchers.Default) {
            value = buildHighlightedAnnotatedString(code, language, highlightsBuilder)
        }
        awaitDispose {
            job.cancel()
        }
    }
}

private fun buildHighlightedAnnotatedString(
    code: String,
    language: String?,
    highlightsBuilder: Highlights.Builder,
): AnnotatedString {
    val syntaxLanguage = language?.let { SyntaxLanguage.getByName(it) }
    val codeHighlights = highlightsBuilder
        .code(code)
        .let { if (syntaxLanguage != null) it.language(syntaxLanguage) else it }
        .build()
        .getHighlights()
    return buildAnnotatedString {
        append(code)
        codeHighlights.forEach {
            val style = when (it) {
                is ColorHighlight -> SpanStyle(color = Color(it.rgb).copy(alpha = 1f))
                is BoldHighlight -> SpanStyle(fontWeight = FontWeight.Bold)
            }
            addStyle(
                style = style,
                start = it.location.start,
                end = it.location.end,
            )
        }
    }
}
