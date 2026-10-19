package com.irio

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CommonsImageLayoutTest {
    @Test fun stripUsesOneSquareAndOneRectangleFromTheBatch() {
        val frames = imageStripFrames(listOf(1000 to 1000, 800 to 600, 900 to 1600, 1200 to 800))
        assertEquals(192f, frames[0].width.value, 0.01f)
        assertEquals(192f, frames[0].height.value, 0.01f)
        assertEquals(frames[1], frames[2])
        assertEquals(frames[1], frames[3])
        assertEquals(288f, frames[1].width.value, 0.01f)
        assertEquals(192f, frames[1].height.value, 0.01f)
    }

    @Test fun imagesSitInOneHorizontalStripWithoutLicenseText() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(controller.get())
        try {
            view.setParentCompositionContext(recomposer)
            controller.get().setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                    AppTheme {
                        ProcessEventRow(ProcessEvent(1, ProcessEventKind.TOOL, toolName = "show_images", content = """{
                            "images":[
                              {"id":"img:1","title":"Panda","width":800,"height":600,"preview_url":"https://cdn.example/panda.png"},
                              {"id":"img:2","title":"Fox","width":800,"height":600,"preview_url":"https://cdn.example/fox.png"}
                            ]}"""), onOpen = {})
                    }
                }
            }
            repeat(4) {
                runCurrent(); clock.sendFrame(System.nanoTime()); runCurrent()
                view.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.AT_MOST))
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            }
            fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
            val nodes = descendants((view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
            val images = nodes.filter {
                val description = it.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }?.toString().orEmpty()
                description.contains("Panda") || description.contains("Fox")
            }
                .sortedBy { it.positionInRoot.x }
            assertEquals(2, images.size)
            assertEquals(256, images[0].size.width)
            assertEquals(192, images[0].size.height)
            assertEquals(images[0].size, images[1].size)
            assertTrue(images[1].positionInRoot.x > images[0].positionInRoot.x)
            assertEquals(images[0].positionInRoot.y, images[1].positionInRoot.y)
            val texts = nodes.flatMap { it.config.getOrElseNullable(SemanticsProperties.Text) { null }.orEmpty() }.joinToString { it.text }
            assertFalse(texts.contains("CC BY"))
            assertFalse(texts.contains("来源"))
            assertFalse(texts.contains("调用工具"))
            assertFalse(texts.contains("已调用工具"))
        } finally {
            view.disposeComposition(); recomposer.close(); runner.cancel()
            controller.pause().stop().destroy()
        }
    }
}
