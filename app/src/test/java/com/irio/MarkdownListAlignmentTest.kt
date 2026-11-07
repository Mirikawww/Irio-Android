package com.irio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkdownListAlignmentTest {
    @Test fun orderedListBodyStartsAtTheSameTextEdgeAsItsTitle() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(controller.get())
        val content = mutableStateOf("")
        var time = 0L
        try {
            view.setParentCompositionContext(recomposer)
            controller.get().setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme { AssistantMarkdown(content.value, streaming = true, renderScope = this@runTest) }
                }
            }
            val cases = listOf(
                "1. **标题**  \n   正文内容",
                "1. **标题**\\\n   正文内容",
                "1. ### 标题\n   正文内容",
                "10. **标题**  \n    正文内容",
                "100. **标题**  \n     正文内容",
                "1. 外层\n   1. **标题**  \n      正文内容",
                "- **标题**  \n  正文内容",
                "1. **标题**  \n   正文内容保留 `a  b` 和 [链接](https://example.test)",
            )
            for (source in cases) {
                content.value = source
                repeat(16) {
                    shadowOf(android.os.Looper.getMainLooper()).idle()
                    Snapshot.sendApplyNotifications(); runCurrent()
                    time += 16_000_000L; clock.sendFrame(time); runCurrent()
                    view.requestLayout()
                    view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
                    view.layout(0, 0, 360, 1000)
                    val bitmap = Bitmap.createBitmap(360, 1000, Bitmap.Config.ARGB_8888)
                    try { view.draw(Canvas(bitmap)) } finally { bitmap.recycle() }
                }
                fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
                val nodes = descend((view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
                val layouts = nodes.flatMap { node ->
                    val results = mutableListOf<TextLayoutResult>()
                    if (node.config.contains(SemanticsActions.GetTextLayoutResult)) {
                        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
                    }
                    results.map { node to it }
                }

                fun startOf(text: String): Float {
                    val (node, result) = layouts.single { it.second.layoutInput.text.text.contains(text) }
                    val index = result.layoutInput.text.text.indexOf(text)
                    return node.positionInRoot.x + result.getHorizontalPosition(index, true)
                }
                assertEquals("Ordered list title/body text edges must align: $source",
                    startOf("标题"), startOf("正文"), 0.5f)
                if (source.contains("a  b")) {
                    assertTrue("Do not trim meaningful spaces inside inline code",
                        layouts.any { it.second.layoutInput.text.text.contains("a  b") })
                    assertTrue("Keep interactive link annotations",
                        layouts.any { it.second.layoutInput.text.let { text -> text.getLinkAnnotations(0, text.length).isNotEmpty() } })
                }
            }
        } finally {
            view.disposeComposition(); recomposer.close(); runner.cancel()
            controller.pause().stop().destroy()
        }
    }
}
