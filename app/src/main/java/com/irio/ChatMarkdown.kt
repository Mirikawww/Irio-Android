package com.irio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.ui.components.Text
import com.composables.ui.theme.colors
import com.composables.ui.theme.onBackgroundColor
import com.composeunstyled.ProvideTextStyle
import com.composeunstyled.theme.Theme
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.model.ImageWidth
import com.mikepenz.markdown.model.MarkdownState
import com.mikepenz.markdown.model.PlaceholderConfig
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdown
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.scilab.forge.jlatexmath.TeXConstants
import org.scilab.forge.jlatexmath.TeXFormula
import ru.noties.jlatexmath.awt.AndroidGraphics2D
import ru.noties.jlatexmath.awt.Color as TexColor

private class PreParsedMarkdownState(private val parsed: State.Success) : MarkdownState {
    override val state: StateFlow<State> = MutableStateFlow(parsed)
    override val links: StateFlow<Map<String, String>> = MutableStateFlow(emptyMap())

    override suspend fun parse(): State = parsed
}

/**
 * Process-wide LRU of parsed Markdown states keyed by segment content. Content-keyed (not
 * message-id-keyed) so scroll-off disposal, conversation switches, and app-restored sessions all
 * hit the same entry, and parsing is never repeated for identical text. The map holds
 * `MutableState<MarkdownState?>` slots: a miss returns an empty slot that a background coroutine
 * fills once — the scroll frame itself never waits on a parse. Null content (parse failure / still
 * parsing) falls back to inline parsing in `IrioMarkdown` as before.
 */
internal object FinishedMarkdownCache {
    private const val MAX_ENTRIES = 128
    private val map =
        object : LinkedHashMap<String, MutableState<MarkdownState?>>(MAX_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: Map.Entry<String, MutableState<MarkdownState?>>
            ): Boolean {
                return size > MAX_ENTRIES
            }
        }
    private val parsed = SingleFlightCache<String, State.Success>()

    fun slot(content: String): MutableState<MarkdownState?> =
        synchronized(map) { map.getOrPut(content) { mutableStateOf(null) } }

    fun request(
        content: String,
        scope: CoroutineScope,
        dispatcher: CoroutineDispatcher,
    ): MutableState<MarkdownState?> {
        val slot = slot(content)
        val result =
            parsed.request(content, scope, dispatcher) {
                parseMarkdown(content) as? State.Success ?: error("Markdown parse failed")
            }
        when {
            result.status == AsyncCacheStatus.READY && slot.value == null -> {
                slot.value = result.value?.let(::PreParsedMarkdownState)
            }
            result.status == AsyncCacheStatus.PENDING && result.completion != null -> {
                scope.launch {
                    val value = result.completion.await()
                    if (value != null) slot.value = PreParsedMarkdownState(value)
                }
            }
        }
        return slot
    }

    suspend fun prepare(content: String, scope: CoroutineScope) {
        val result =
            parsed.request(content, scope, Dispatchers.Default) {
                parseMarkdown(content) as? State.Success ?: error("Markdown parse failed")
            }
        result.completion?.await()
    }
}

internal object FinishedMarkdownSegmentsCache {
    private const val MAX_ENTRIES = 128
    private val map =
        object :
            LinkedHashMap<String, MutableState<List<MarkdownSegment>?>>(MAX_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: Map.Entry<String, MutableState<List<MarkdownSegment>?>>
            ): Boolean {
                return size > MAX_ENTRIES
            }
        }
    private val parsed = SingleFlightCache<String, List<MarkdownSegment>>()

    fun request(content: String, scope: CoroutineScope): MutableState<List<MarkdownSegment>?> {
        val slot = synchronized(map) { map.getOrPut(content) { mutableStateOf(null) } }
        val result =
            parsed.request(content, scope, Dispatchers.Default) { markdownSegments(content) }
        when {
            result.status == AsyncCacheStatus.READY && slot.value == null ->
                slot.value = result.value
            result.status == AsyncCacheStatus.PENDING && result.completion != null -> {
                scope.launch { result.completion.await()?.let { slot.value = it } }
            }
        }
        return slot
    }

    suspend fun prepare(content: String, scope: CoroutineScope): List<MarkdownSegment>? {
        val result =
            parsed.request(content, scope, Dispatchers.Default) { markdownSegments(content) }
        return result.value ?: result.completion?.await()
    }
}

internal data class TexCacheKey(
    val latex: String,
    val colorArgb: Int,
    val displayStyle: Boolean,
    val pxPerPt: Float,
    val texScale: Float,
)

/**
 * Process-wide LRU for JLatexMath rasters. Formula layout + raster costs far more than Markdown
 * parsing, and lazy-item disposal discards the per-item `remember` on every scroll-off. Survives
 * scrolling and conversation switches.
 */
internal object TexBitmapCache {
    private val cache = SingleFlightCache<TexCacheKey, ImageBitmap>(maxEntries = 64)
    // JLatexMath has a process-wide rendering lock. Dispatching dozens of
    // formulas onto Default makes every worker except one block on that lock,
    // starving Markdown parsing and transcript presentation during first open.
    // Keep the serialized renderer on its own lane instead.
    private val renderDispatcher = Dispatchers.Default.limitedParallelism(1)

    fun request(
        key: TexCacheKey,
        scope: CoroutineScope,
        render: () -> ImageBitmap?,
    ): AsyncCacheResult<ImageBitmap> =
        cache.request(key, scope, renderDispatcher) {
            render() ?: error("TeX rasterization failed")
        }

    fun get(key: TexCacheKey): ImageBitmap? = cache.peek(key)?.value

    suspend fun prepare(key: TexCacheKey, scope: CoroutineScope, render: () -> ImageBitmap?) {
        val result = request(key, scope, render)
        result.completion?.await()
    }
}

internal suspend fun prepareFinishedMessage(
    message: ChatMessage,
    contentArgb: Int,
    pxPerPt: Float,
    texScale: Float,
    scope: CoroutineScope,
) {
    if (
        message.summaryState != SummaryState.NONE ||
            message.fromUser ||
            message.isError ||
            message.isPending ||
            message.isStreaming ||
            message.text.isBlank()
    )
        return
    val segments = FinishedMarkdownSegmentsCache.prepare(message.text, scope) ?: return
    segments
        .filterIsInstance<MarkdownSegment.Text_>()
        .filter { it.markdown }
        .map { it.text }
        .distinct()
        .forEach { FinishedMarkdownCache.request(it, scope, Dispatchers.Default) }
    val formulas =
        segments.filterIsInstance<MarkdownSegment.TexBlock>().map { it.latex to true } +
            segments.filterIsInstance<MarkdownSegment.Text_>().flatMap { segment ->
                inlineTexContents(segment.text).map { it to false }
            }
    formulas.distinct().forEach { (latex, displayStyle) ->
        val key = TexCacheKey(latex, contentArgb, displayStyle, pxPerPt, texScale)
        TexBitmapCache.request(key, scope) {
            renderTexBitmap(latex, contentArgb, displayStyle, pxPerPt, texScale)
        }
    }
}

@Composable
internal fun AssistantMarkdown(
    text: String,
    streaming: Boolean,
    renderScope: CoroutineScope,
    pronunciations: Map<String, PronunciationRecording> = emptyMap(),
) {
    val segments = remember(text) { markdownSegments(text) }
    val markdownSlots =
        remember(text, segments, streaming) {
            if (streaming) {
                emptyMap()
            } else {
                segments
                    .filterIsInstance<MarkdownSegment.Text_>()
                    .filter { it.markdown }
                    .distinctBy { it.text }
                    .associate { segment ->
                        segment.text to
                            FinishedMarkdownCache.request(
                                segment.text,
                                renderScope,
                                Dispatchers.Default,
                            )
                    }
            }
        }
    observeIrioTheme()
    val contentColor = Theme[colors][onBackgroundColor]
    val context = LocalContext.current
    // texScale 只在进入组合时读一次：不再监听 ProviderStore 全局变化——
    // 否则模型切换/思考档位保存会让屏幕上每条消息都重组（切换卡顿的主要来源之一）。
    val texScale = remember { ProviderStore.loadTexScale(context) }
    val appFont = rememberAppFontFamily()
    val bodyStyle =
        TextStyle(fontSize = 17.sp, lineHeight = 26.sp, color = contentColor, fontFamily = appFont)
    val inlineCode = irioInlineCodeStyle()
    val codeBlock = irioCodeBlockStyle()
    val typography =
        markdownTypography(
            h1 =
                TextStyle(
                    fontSize = 24.sp,
                    lineHeight = 36.sp,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                    fontFamily = appFont,
                ),
            h2 =
                TextStyle(
                    fontSize = 22.sp,
                    lineHeight = 33.sp,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                    fontFamily = appFont,
                ),
            h3 =
                TextStyle(
                    fontSize = 20.sp,
                    lineHeight = 31.sp,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                    fontFamily = appFont,
                ),
            h4 =
                TextStyle(
                    fontSize = 18.sp,
                    lineHeight = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                    fontFamily = appFont,
                ),
            h5 =
                TextStyle(
                    fontSize = 17.sp,
                    lineHeight = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                    fontFamily = appFont,
                ),
            h6 =
                TextStyle(
                    fontSize = 17.sp,
                    lineHeight = 30.sp,
                    fontWeight = FontWeight.Medium,
                    color = contentColor,
                    fontFamily = appFont,
                ),
            text = bodyStyle,
            paragraph = bodyStyle,
            quote = bodyStyle,
            ordered = bodyStyle,
            bullet = bodyStyle,
            list = bodyStyle,
            table = bodyStyle,
            code = codeBlock,
            inlineCode = inlineCode,
        )
    val density = LocalDensity.current
    val texTransformer =
        remember(contentColor, texScale) {
            TexImageTransformer(contentColor.toArgb(), texScale, renderScope)
        }
    val imageTransformer = remember(texTransformer) { IrioImageTransformer(texTransformer) }
    ProvideTextStyle(bodyStyle) {
        Column {
            segments.forEach { segment ->
                key(segment.key) {
                    when (segment) {
                        is MarkdownSegment.TexBlock ->
                            TexBlock(
                                latex = segment.latex,
                                colorArgb = contentColor.toArgb(),
                                density = density,
                                texScale = texScale,
                                renderScope = renderScope,
                            )
                        is MarkdownSegment.Text_ -> {
                            val markdownState = markdownSlots[segment.text]?.value
                            IrioMarkdown(
                                content = segment.text,
                                state = markdownState,
                                typography = typography,
                                imageTransformer = imageTransformer,
                                glyphFade = streaming && ThemeStore.glyphFade,
                                pronunciations = pronunciations,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Block-level `$$…$$` / `\[…\]` formula rendered with JLatexMath. */
@Composable
private fun TexBlock(
    latex: String,
    colorArgb: Int,
    density: Density,
    texScale: Float,
    renderScope: CoroutineScope,
) {
    val pxPerPt = density.density
    val cacheKey = TexCacheKey(latex, colorArgb, displayStyle = true, pxPerPt, texScale)
    // Cache hit is drawn immediately; a miss renders off the main thread so a
    // formula entering the viewport never blocks the scroll frame.
    var bitmap by remember(cacheKey) { mutableStateOf(TexBitmapCache.get(cacheKey)) }
    if (bitmap == null) {
        LaunchedEffect(cacheKey) {
            val result =
                TexBitmapCache.request(cacheKey, renderScope) {
                    renderTexBitmap(
                        latex,
                        colorArgb,
                        displayStyle = true,
                        pxPerPt = pxPerPt,
                        texScale = texScale,
                    )
                }
            bitmap = result.value ?: result.completion?.await()
        }
    }
    val rendered = bitmap
    if (rendered != null) {
        // Bitmap holds device pixels (pxPerPt = density); show it 1:1 px.
        val widthDp = with(density) { rendered.width.toDp() }
        val heightDp = with(density) { rendered.height.toDp() }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Image(
                bitmap = rendered,
                contentDescription = null,
                modifier =
                    Modifier.padding(vertical = 4.dp).size(width = widthDp, height = heightDp),
            )
        }
    } else {
        // Parse failure: keep the raw LaTeX visible.
        Text(latex, fontSize = 17.sp, lineHeight = 26.sp, color = Theme[colors][onBackgroundColor])
    }
}

private class TexImageTransformer(
    private val contentArgb: Int,
    private val texScale: Float,
    private val renderScope: CoroutineScope,
) : ImageTransformer {

    @Composable
    override fun transform(link: String): ImageData? {
        if (!link.startsWith(TEX_LINK_PREFIX)) return null
        val latex = link.removePrefix(TEX_LINK_PREFIX)
        val density = LocalDensity.current
        val pxPerPt = density.density
        val cacheKey = TexCacheKey(latex, contentArgb, displayStyle = false, pxPerPt, texScale)
        // Same contract as TexBlock: cache hit is immediate, a miss renders on
        // IO and reports its real size via imageSizeChanged when ready.
        var bitmap by remember(cacheKey) { mutableStateOf(TexBitmapCache.get(cacheKey)) }
        if (bitmap == null) {
            LaunchedEffect(cacheKey) {
                val result =
                    TexBitmapCache.request(cacheKey, renderScope) {
                        renderTexBitmap(
                            latex,
                            contentArgb,
                            displayStyle = false,
                            pxPerPt = pxPerPt,
                            texScale = texScale,
                        )
                    }
                bitmap = result.value ?: result.completion?.await()
            }
        }
        val rendered = bitmap
        return ImageData(
            painter = rendered?.let(::BitmapPainter) ?: ColorPainter(Color.Transparent),
            modifier = Modifier,
            contentDescription = null,
            alignment = Alignment.CenterStart,
            contentScale = ContentScale.Fit,
            alpha = 1f,
            colorFilter = null,
        )
    }

    override fun placeholderConfig(
        link: String,
        density: Density,
        containerSize: Size,
        imageWidth: ImageWidth,
        imageSize: Size,
        imageSizeChanged: ((String, Size) -> Unit)?,
    ): PlaceholderConfig {
        val size =
            if (link.startsWith(TEX_LINK_PREFIX)) {
                // imageSize is in px (painter intrinsic); PlaceholderConfig.size is dp.
                with(density) { Size(imageSize.width.toDp().value, imageSize.height.toDp().value) }
            } else {
                super.placeholderConfig(
                        link,
                        density,
                        containerSize,
                        imageWidth,
                        imageSize,
                        imageSizeChanged,
                    )
                    .size
            }
        return PlaceholderConfig(size, PlaceholderVerticalAlign.Bottom)
    }
}

/**
 * CJK (and other non-TeX unicode) is painted by JavaFontRenderingBox from a 10px Android font and
 * never multiplied by [TeXFormula.PIXELS_PER_POINT]. Latin math metrics are. Wrap CJK runs in
 * \scalebox so they match density.
 */
private fun scaleCjkForDensity(latex: String, pxPerPt: Float): String {
    if (pxPerPt <= 1.01f || latex.isEmpty()) return latex
    val scale = formatTexScale(pxPerPt)
    val out = StringBuilder(latex.length + 16)
    var index = 0
    while (index < latex.length) {
        val code = latex.codePointAt(index)
        val width = Character.charCount(code)
        if (isTexCjkCode(code)) {
            val start = index
            index += width
            while (index < latex.length) {
                val next = latex.codePointAt(index)
                if (!isTexCjkCode(next)) break
                index += Character.charCount(next)
            }
            out.append("\\scalebox{").append(scale).append("}{")
            out.append(latex, start, index)
            out.append('}')
        } else {
            out.appendCodePoint(code)
            index += width
        }
    }
    return out.toString()
}

private fun formatTexScale(value: Float): String {
    val rounded = (value * 1000f).toInt() / 1000f
    return if (rounded == rounded.toInt().toFloat()) {
        rounded.toInt().toString()
    } else {
        rounded.toString()
    }
}

private fun isTexCjkCode(code: Int): Boolean {
    val block = Character.UnicodeBlock.of(code) ?: return false
    return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
        block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
        block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
        block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
        block == Character.UnicodeBlock.HIRAGANA ||
        block == Character.UnicodeBlock.KATAKANA ||
        block == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS ||
        block == Character.UnicodeBlock.HANGUL_SYLLABLES ||
        block == Character.UnicodeBlock.HANGUL_JAMO ||
        block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
}

/** Render LaTeX to an ImageBitmap; null on parse failure. */
private val texRenderLock = Any()

private fun renderTexBitmap(
    latex: String,
    colorArgb: Int,
    displayStyle: Boolean,
    pxPerPt: Float,
    texScale: Float,
): ImageBitmap? =
    synchronized(texRenderLock) {
        try {
            val formula = TeXFormula(scaleCjkForDensity(latex, pxPerPt))
            val style = if (displayStyle) TeXConstants.STYLE_DISPLAY else TeXConstants.STYLE_TEXT
            // Must be set BEFORE createTeXIcon: the icon layout is computed from it.
            TeXFormula.PIXELS_PER_POINT = pxPerPt
            val baseSize = if (displayStyle) 20f else 17f
            val icon = formula.createTeXIcon(style, baseSize * texScale)
            icon.setForeground(TexColor(colorArgb))
            val width = icon.iconWidth
            val height = icon.iconHeight
            if (width <= 0 || height <= 0) return null
            val bitmap =
                android.graphics.Bitmap.createBitmap(
                    width,
                    height,
                    android.graphics.Bitmap.Config.ARGB_8888,
                )
            val canvas = android.graphics.Canvas(bitmap)
            val graphics = AndroidGraphics2D()
            graphics.setCanvas(canvas)
            icon.paintIcon(null, graphics, 0, 0)
            bitmap.asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }
