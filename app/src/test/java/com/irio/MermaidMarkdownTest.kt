package com.irio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.TextLayoutResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MermaidMarkdownTest {
    @Test fun realMarkdownFenceRendersNativeCanvasAndKeepsSourceAndStreamingFallbacks() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(controller.get())
        ThemeStore.ensureLoaded(controller.get())
        val originalWrap = ThemeStore.codeBlockWrap
        val originalLineNumbers = ThemeStore.codeBlockLineNumbers
        val source = "flowchart LR\n  A[开始] --> B[结束]"
        val text = mutableStateOf("Before\n\n```mermaid\n$source\n```\n\nAfter")
        val processEvent = mutableStateOf<ProcessEvent?>(null)
        var frameTime = 0L
        fun frames(count: Int = 3) {
            repeat(count) {
                shadowOf(android.os.Looper.getMainLooper()).idle()
                Snapshot.sendApplyNotifications()
                runCurrent()
                frameTime += 16_000_000L
                clock.sendFrame(frameTime)
                runCurrent()
                view.requestLayout()
                view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, 360, 1000)
            }
        }
        fun nodes(): List<SemanticsNode> {
            fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
            return descend((view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
        }
        fun tagged(tag: String) = nodes().filter { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == tag }
        fun hasText(value: String) = nodes().any {
            it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { entry -> entry.text.contains(value) }
        }
        suspend fun await(predicate: () -> Boolean) {
            repeat(100) {
                frames()
                if (predicate()) return
                withContext(Dispatchers.IO) { Thread.sleep(10) }
            }
            fail("The native Mermaid render did not complete; text=" + nodes().flatMap {
                it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { value -> value.text }
            })
        }
        fun click(label: String) {
            val node = nodes().single { node ->
                node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.contains(label) ||
                    node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == label }
            }
            var target: SemanticsNode? = node
            while (target != null && !target.config.contains(SemanticsActions.OnClick)) target = target.parent
            assertNotNull("The $label control must remain clickable", target)
            assertTrue(target!!.config[SemanticsActions.OnClick].action!!.invoke())
        }
        try {
            view.setParentCompositionContext(recomposer)
            controller.get().setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme { Column {
                        val event = processEvent.value
                        if (event != null) ProcessEventRow(event, onOpen = {})
                        else AssistantMarkdown(text.value, streaming = true, renderScope = this@runTest)
                    } }
                }
            }
            await { nodes().any { it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
                .contains(tr("Mermaid 图表")) && it.boundsInRoot.width > 0f } }
            assertEquals("A fenced Mermaid block embedded between paragraphs must render once", 1, tagged("mermaid-block").size)
            assertTrue(hasText("Before"))
            assertTrue(hasText("After"))
            val diagram = tagged("mermaid-diagram").single().boundsInRoot
            assertTrue("The diagram needs a readable nonzero viewport: $diagram", diagram.width > 250f && diagram.height >= 111.5f)
            assertTrue("Source and copy controls must be icons with accessible labels", listOf(tr("源代码"), tr("复制")).all { label ->
                nodes().any { it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.contains(label) }
            })
            val block = tagged("mermaid-block").single().boundsInRoot
            assertEquals("No side frame may inset the diagram", block.left, diagram.left, 0.5f)
            assertEquals(block.right, diagram.right, 0.5f)
            assertTrue("The diagram toolbar must stay compact", tagged("mermaid-header").single().boundsInRoot.height <= 32f)
            assertEquals("The diagram must join the header without a gap",
                tagged("mermaid-header").single().boundsInRoot.bottom, diagram.top, 0.5f)
            val retainedDiagramId = tagged("mermaid-diagram").single().id
            val bitmap = Bitmap.createBitmap(360, 1000, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                val bottom = diagram.bottom.toInt() - 1
                assertEquals("The diagram's bottom-left background corner must be clipped", 0,
                    android.graphics.Color.alpha(bitmap.getPixel(diagram.left.toInt(), bottom)))
                assertEquals("The diagram's bottom-right background corner must be clipped", 0,
                    android.graphics.Color.alpha(bitmap.getPixel(diagram.right.toInt() - 1, bottom)))
                assertTrue("The middle of the diagram background must still be painted",
                    android.graphics.Color.alpha(bitmap.getPixel(diagram.center.x.toInt(), bottom)) > 0)
                val painted = mutableSetOf<Int>()
                for (y in diagram.top.toInt() + 2 until bottom - 1) {
                    for (x in diagram.left.toInt() + 2 until diagram.right.toInt() - 2) {
                        painted += bitmap.getPixel(x, y)
                    }
                }
                assertTrue("The real canvas must paint nodes, edges and labels, not only a placeholder background",
                    painted.size > 16)
            } finally { bitmap.recycle() }
            click(tr("复制"))
            frames()
            assertEquals(source, (controller.get().getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager).primaryClip!!.getItemAt(0).text.toString())
            click(tr("源代码"))
            frames()
            assertTrue(hasText("flowchart LR"))
            val sourceCapture = Bitmap.createBitmap(360, 1000, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(sourceCapture))
                val header = tagged("mermaid-header").single().boundsInRoot
                val seam = header.bottom.toInt() + 1
                assertTrue("Source background must meet the header at a square top-left corner, without a gap",
                    android.graphics.Color.alpha(sourceCapture.getPixel(header.left.toInt(), seam)) > 0)
                assertTrue("Source background must meet the header at a square top-right corner",
                    android.graphics.Color.alpha(sourceCapture.getPixel(header.right.toInt() - 1, seam)) > 0)
                val bottom = tagged("mermaid-block").single().boundsInRoot.bottom.toInt() - 9
                assertEquals("Source and diagram must share the same rounded bottom-left corner", 0,
                    android.graphics.Color.alpha(sourceCapture.getPixel(header.left.toInt(), bottom)))
                assertEquals("Source and diagram must share the same rounded bottom-right corner", 0,
                    android.graphics.Color.alpha(sourceCapture.getPixel(header.right.toInt() - 1, bottom)))
            } finally { sourceCapture.recycle() }
            // The unmerged test tree retains unplaced nodes and their last coordinates.
            // Accessibility must stop at the clearing boundary, not traverse the cached canvas.
            val hiddenDiagram = nodes().single {
                it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.contains(tr("Mermaid 图表"))
            }
            assertTrue("The retained diagram must be hidden behind an accessibility-clearing boundary",
                generateSequence(hiddenDiagram) { it.parent }.any { it.config.isClearingSemantics })
            click(tr("图表"))
            frames()
            assertEquals("Switching from source must retain the same native diagram and cached scene",
                retainedDiagramId, tagged("mermaid-diagram").single().id)
            assertFalse("A cached diagram must never show a new render placeholder on source toggles", hasText(tr("图表渲染中")))
            await { nodes().any { it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
                .contains(tr("Mermaid 图表")) } }
            repeat(2) {
                click(tr("源代码"))
                frames()
                click(tr("图表"))
                frames()
                assertEquals(retainedDiagramId, tagged("mermaid-diagram").single().id)
                assertFalse(hasText(tr("图表渲染中")))
            }

            text.value = "```mermaid\nflowchart-elk LR\n  A --> B\n```"
            await { hasText(tr("图表无法渲染，已显示源代码")) }
            assertTrue(hasText("flowchart-elk"))
            text.value = "```mermaid\nflowchart LR\n  A -->"
            frames(8)
            assertTrue("An incomplete streamed fence must stay readable as code", tagged("mermaid-block").isEmpty())
            assertTrue(hasText("flowchart LR"))
            text.value += " B\n```"
            await { nodes().any { it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
                .contains(tr("Mermaid 图表")) && it.boundsInRoot.width > 0f } }
            assertFalse("Closing a valid streamed diagram must recover from the previous error", hasText(tr("图表无法渲染，已显示源代码")))
            text.value = "~~~mermaid\nflowchart LR\n\n  A --> B\n~~~\n\n```mermaid\nsequenceDiagram\n  Alice->>Bob: Hello\n```"
            await { nodes().count { it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
                .contains(tr("Mermaid 图表")) && it.boundsInRoot.width > 0f } == 2 }
            assertEquals("Separate fences need independent render state", 2, tagged("mermaid-block").size)
            text.value = "```kotlin\nval example = 42\n```"
            frames(8)
            assertTrue("Ordinary source code must not become a diagram", tagged("mermaid-block").isEmpty())
            assertTrue(hasText("val example"))
            val tracker = ProcessTracker()
            val result = ToolRegistry(listOf(MermaidToolProvider())).execute(
                ChatToolCall("render", MermaidTool.NAME, org.json.JSONObject().put("source", source).toString()),
                ToolExecutionContext(ProviderConfig("p", "P", "https://example.test", ""), "model",
                    onUpdate = { update ->
                        if (update.displayedMermaid.isNotEmpty()) tracker.onDisplayedMermaid(update.displayedMermaid)
                    }))
            assertEquals(ToolOutcome.SUCCESS, result.outcome)
            processEvent.value = tracker.snapshot().single()
            await { nodes().any { it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
                .contains(tr("Mermaid 图表")) && it.boundsInRoot.width > 0f } }
            click(tr("源代码"))
            frames()
            assertTrue("Agent-tool diagrams must preserve the raw source too", hasText(source))
            val longSource = "flowchart LR\n  A[\"" + "A long Mermaid label with several words ".repeat(6) + "\"] --> B"
            processEvent.value = processEvent.value!!.copy(content = longSource)
            await { nodes().any { it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
                .contains(tr("Mermaid 图表")) && it.boundsInRoot.width > 0f } }
            click(tr("源代码"))
            ThemeStore.saveCodeBlockWrap(controller.get(), false)
            ThemeStore.saveCodeBlockLineNumbers(controller.get(), false)
            frames(8)
            fun sourceLayout(): TextLayoutResult {
                val node = nodes().single { it.config.getOrElse(SemanticsProperties.Text) { emptyList() }
                    .any { value -> value.text == longSource } }
                val results = mutableListOf<TextLayoutResult>()
                node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
                return results.single()
            }
            assertEquals("No-wrap source must retain its two original lines", 2, sourceLayout().lineCount)
            assertFalse("The line-number gutter must be optional", hasText("1") && hasText("2"))
            ThemeStore.saveCodeBlockWrap(controller.get(), true)
            ThemeStore.saveCodeBlockLineNumbers(controller.get(), true)
            frames(8)
            assertTrue("The wrap preference must update the already-open source", sourceLayout().layoutInput.softWrap)
            assertTrue("Long Mermaid lines must really wrap", sourceLayout().lineCount > 2)
            assertTrue("The native line-number gutter must use source lines", hasText("1") && hasText("2"))
        } finally {
            view.disposeComposition(); recomposer.close(); runner.cancel()
            ThemeStore.saveCodeBlockWrap(controller.get(), originalWrap)
            ThemeStore.saveCodeBlockLineNumbers(controller.get(), originalLineNumbers)
            controller.pause().stop().destroy()
        }
    }
}
