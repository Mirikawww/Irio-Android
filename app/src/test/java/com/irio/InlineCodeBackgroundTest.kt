package com.irio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.composables.ui.theme.colors
import com.composables.ui.theme.onBackgroundColor
import com.composeunstyled.theme.Theme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InlineCodeBackgroundTest {
    @Test fun backgroundsFollowOnlyTheCodeGlyphsOnEachSoftWrappedLine() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val context = controller.get()
        ThemeStore.ensureLoaded(context)
        val originalTheme = ThemeStore.preference
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(context)
        val source = mutableStateOf("")
        val density = mutableStateOf(Density(1f))
        var background = 0
        var time = 0L
        var wrappedSpans = 0
        var boundaryEnds = 0
        var orphanSpacingLines = 0
        try {
            ThemeStore.save(context, ThemePreference.LIGHT)
            view.setParentCompositionContext(recomposer)
            context.setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides density.value) {
                    AppTheme {
                        background = Theme[colors][onBackgroundColor].copy(alpha = 0.12f)
                            .compositeOver(Color.White).toArgb()
                        Box(Modifier.background(Color.White).padding(12.dp)) {
                            key(source.value) {
                                AssistantMarkdown(source.value, streaming = false, renderScope = this@runTest)
                            }
                        }
                    }
                }
            }
            fun settle(width: Int) = repeat(6) {
                shadowOf(android.os.Looper.getMainLooper()).idle()
                Snapshot.sendApplyNotifications()
                runCurrent()
                time += 16_000_000L
                clock.sendFrame(time)
                runCurrent()
                view.requestLayout()
                view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, width, 600)
                (view.getChildAt(0) as ViewRootForTest).measureAndLayoutForTest()
            }
            fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
            val cases = listOf(
                "前面的正文和 `软换行测试行内代码` 后面的正文继续",
                "前缀 `abcdefghijklmno` 后面的正文继续",
                "正文正文正文 `字` 后面的正文继续",
                "`first` 正文 `second_long_identifier` 后面的正文继续",
                "正文正文正文`字`后面的正文继续",
                "`软换行测试行内代码`后面的正文继续",
                "前缀 `a  b` 后缀",
                "正文 `abc אבג xyz` 末尾",
                "正文 `👩‍💻界🙂identifier` 末尾",
            )
            for (markdown in cases) {
                FinishedMarkdownCache.prepare(markdown, this)
                FinishedMarkdownSegmentsCache.prepare(markdown, this)
                source.value = markdown
                for (fontScale in listOf(1f, 1.5f)) {
                    density.value = Density(1f, fontScale)
                    for (width in listOf(132, 144, 156, 168, 192, 228)) {
                        settle(width)
                        val root = (view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode
                        val layouts = descend(root).flatMap { node ->
                            val results = mutableListOf<TextLayoutResult>()
                            if (node.config.contains(SemanticsActions.GetTextLayoutResult)) {
                                node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
                            }
                            results.map { node to it }
                        }
                        assertTrue("The real Markdown paragraph must be rendered", layouts.isNotEmpty())
                        val bitmap = Bitmap.createBitmap(width, 600, Bitmap.Config.ARGB_8888)
                        try {
                            view.draw(Canvas(bitmap))
                            for ((node, layout) in layouts) {
                                val text = layout.layoutInput.text
                                val annotations = text.getStringAnnotations("irio_inline_code", 0, text.length)
                                assertTrue("The actual inline-code painter must decorate the paragraph", annotations.isNotEmpty())
                                val origin = node.positionInRoot
                                val pad = 4f * fontScale
                                val font = 14f * fontScale
                                val expectedByLine = mutableMapOf<Int, MutableList<Pair<Float, Float>>>()
                                for (annotation in annotations) {
                                    val glyphsByLine = (annotation.start until annotation.end)
                                        .groupBy { layout.getLineForOffset(it) }
                                    if (glyphsByLine.size > 1) wrappedSpans++
                                    if (annotation.end < text.length &&
                                        layout.getLineForOffset(annotation.end - 1) != layout.getLineForOffset(annotation.end)) boundaryEnds++
                                    for ((line, offsets) in glyphsByLine) {
                                        if (offsets.none { !text[it].isWhitespace() }) {
                                            orphanSpacingLines++
                                            continue
                                        }
                                        val boxes = offsets.map(layout::getBoundingBox)
                                        val left = origin.x + boxes.minOf { it.left } - pad
                                        val right = origin.x + boxes.maxOf { it.right } + pad
                                        expectedByLine.getOrPut(line) { mutableListOf() }.add(left to right)
                                    }
                                }
                                for (line in 0 until layout.lineCount) {
                                    // Sample the flat strip above code glyphs, below the rounded top edge.
                                    val y = (origin.y + layout.getLineBaseline(line) - font - pad / 2f).roundToInt()
                                    if (y !in 0 until bitmap.height) continue
                                    val expected = expectedByLine[line].orEmpty()
                                    val painted = (0 until width).filter { x -> bitmap.getPixel(x, y) == background }
                                    for ((left, right) in expected) {
                                        val interior = ceil(left + 3f).toInt() until floor(right - 3f).toInt()
                                        assertTrue("Missing code background after soft wrap: $markdown width=$width scale=$fontScale line=$line expected=$expected painted=$painted",
                                            interior.filter { it in 0 until width }.all { x ->
                                                // Glyph ink may overlap the sampling strip on some fallback fonts.
                                                bitmap.getPixel(x, y) != android.graphics.Color.WHITE
                                            })
                                    }
                                    assertTrue("Code background leaked outside its visual line: $markdown width=$width scale=$fontScale line=$line expected=$expected painted=$painted",
                                        painted.all { x -> expected.any { (left, right) -> x >= left - 1f && x <= right + 1f } })
                                }
                            }
                        } finally { bitmap.recycle() }
                    }
                }
            }
            assertTrue("The fixture must actually exercise a multi-line code span", wrappedSpans > 0)
            assertTrue("The fixture must exercise an exclusive span end at a soft-wrap boundary", boundaryEnds > 0)
            assertTrue("The fixture must wrap code-edge spacing separately from its glyphs", orphanSpacingLines > 0)
        } finally {
            view.disposeComposition()
            recomposer.close()
            runner.cancel()
            ThemeStore.save(context, originalTheme)
            controller.pause().stop().destroy()
        }
    }
}
