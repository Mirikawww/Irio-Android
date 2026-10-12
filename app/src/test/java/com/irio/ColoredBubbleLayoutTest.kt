package com.irio

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
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
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ColoredBubbleLayoutTest {
    @Test fun longAndMultilineBubblesWrapAtNarrowWidthsAndLargeFontSizes() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(controller.get())
        val content = mutableStateOf("💗 你好")
        val scale = mutableStateOf(1f)
        try {
            view.setParentCompositionContext(recomposer)
            controller.get().setContentView(view)
            view.setContent {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f, scale.value),
                ) {
                    AppTheme { ColoredBubbleView(ProcessEvent(1, ProcessEventKind.BUBBLE, content = content.value, bubbleColor = "pink"), {}) }
                }
            }
            fun measure() {
                view.requestLayout()
                view.measure(View.MeasureSpec.makeMeasureSpec(280, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(3000, View.MeasureSpec.AT_MOST))
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            }
            suspend fun settle() {
                Snapshot.sendApplyNotifications()
                repeat(3) { runCurrent(); clock.sendFrame(System.nanoTime()); runCurrent(); measure() }
            }
            fun textNode(node: SemanticsNode): SemanticsNode? {
                if (node.config.contains(SemanticsProperties.Text)) return node
                return node.children.firstNotNullOfOrNull { textNode(it) }
            }
            fun renderedText() = textNode((view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)!!
            for (fontScale in listOf(1f, 1.5f, 2f)) {
                scale.value = fontScale
                content.value = "💗 你好"
                settle()
                val shortHeight = renderedText().size.height
                assertTrue(shortHeight > 0)
                content.value = "💙 咕噜咕噜～蓝色泡泡也来凑热闹！\n" + "这是一颗会自然换行的长气泡。".repeat(8)
                settle()
                val longNode = renderedText()
                assertEquals(content.value, longNode.config[SemanticsProperties.Text].single().text)
                assertTrue("Text must fit the bubble width", longNode.size.width <= 252)
                assertTrue("fontScale=$fontScale short=$shortHeight long=${longNode.size.height}", longNode.size.height > shortHeight * 2)
            }
        } finally {
            view.disposeComposition()
            recomposer.close()
            runner.cancel()
            controller.pause().stop().destroy()
        }
    }
}
