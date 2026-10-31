package com.irio

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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
class VideoGenerationControlsTest {
    @Test fun optionListsFollowH3AndVariantConstraintsWithoutAdaptiveForTextToVideo() {
        assertEquals(listOf("768P", "2K"), videoGenerationResolutions("MiniMax-H3"))
        assertEquals(listOf("768P", "2K"), videoGenerationResolutions("minimax/minimax-h3"))
        listOf("MiniMax-H3-Highspeed", "MiniMax-H3-Lite", "MiniMax-H3-Max").forEach {
            assertEquals(listOf("480P", "768P"), videoGenerationResolutions(it))
        }
        assertEquals((4..15).toList(), videoGenerationDurations("MiniMax-H3").toList())
        assertEquals((5..15).toList(), videoGenerationDurations("minimax/MiniMax-H3-Max").toList())
        assertEquals(listOf(VideoGenerationChoice.RATIO), VideoGenerationChoice.entries)
        assertEquals(listOf("21:9", "16:9", "4:3", "1:1", "3:4", "9:16"),
            videoGenerationChoiceValues(VideoGenerationChoice.RATIO, "MiniMax-H3"))
        assertFalse(videoGenerationRatios.contains("adaptive"))
        assertEquals(352f, modelSwitcherCompactHeight(imageModel = false, videoModel = true).value, 0.01f)
    }

    @Test fun videoParametersReuseImageStyleAndPreserveTheOriginalSwitches() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(controller.get())
        val options = mutableStateOf(VideoGenerationOptions())
        var opened: VideoGenerationChoice? = null
        var search = false
        var autoRead = false
        try {
            view.setParentCompositionContext(recomposer)
            controller.get().setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme {
                        Column {
                            ModelSwitcherParameterControls(
                                imageModel = false, imageOptions = ImageGenerationOptions(), compactInteractive = true,
                                supportsOpenAiOptions = true, reasoningEffort = ReasoningEffort.OFF,
                                supportsThinking = false, reasoningLevels = listOf(ReasoningEffort.OFF),
                                webSearchAvailable = true, webSearchEnabled = false, autoRead = false,
                                onReasoningChange = {}, onWebSearchChange = { search = it }, onAutoReadChange = { autoRead = it },
                                onImageOptionsChange = {}, onAspectRatio = {}, videoModel = true,
                                videoOptions = options.value, selectedModel = "MiniMax-H3",
                                onVideoOptionsChange = { options.value = it }, onVideoChoice = { opened = it },
                            )
                        }
                    }
                }
            }
            var time = 0L
            val drawing = android.graphics.Canvas(android.graphics.Bitmap.createBitmap(264, 305, android.graphics.Bitmap.Config.ARGB_8888))
            fun frame() {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                Snapshot.sendApplyNotifications()
                runCurrent()
                time += 16_000_000L
                clock.sendFrame(time)
                runCurrent()
                view.measure(View.MeasureSpec.makeMeasureSpec(264, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(305, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, 264, 305)
                view.draw(drawing)
            }
            repeat(12) { frame() }
            fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
            fun nodes() = descend((view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
            fun text(label: String) = nodes().single {
                it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { value -> value.text == label }
            }
            fun click(label: String) {
                var node = text(label)
                while (!node.config.contains(SemanticsActions.OnClick)) node = node.parent!!
                assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
                repeat(20) { frame() }
            }
            listOf("分辨率", "视频时长", "宽高比", "网络搜索", "自动朗读").zipWithNext().forEach { (before, after) ->
                assertTrue("$before must appear before $after", text(tr(before)).boundsInRoot.bottom <= text(tr(after)).boundsInRoot.top)
            }
            assertTrue(text(tr("自动朗读")).boundsInRoot.bottom <= 305f)
            assertFalse(nodes().any { it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { value -> value.text == tr("背景") } })
            click("2K")
            assertEquals(VideoGenerationOptions(resolution = "2K"), options.value)
            val slider = nodes().single { it.config.contains(SemanticsActions.SetProgress) &&
                tr("视频时长") in it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() } }
            assertTrue(slider.config[SemanticsActions.SetProgress].action!!.invoke(10f))
            repeat(4) { frame() }
            assertEquals(10, options.value.duration)
            assertEquals(null, opened)
            fun tagged(tag: String) = nodes().single { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == tag }
            val compact = tagged("video-duration-slider").boundsInRoot
            val valueBefore = tagged("video-duration-seconds").boundsInRoot
            assertEquals(92f, compact.width, 0.01f)
            val down = android.os.SystemClock.uptimeMillis()
            fun touch(action: Int, step: Int, x: Float) {
                val event = android.view.MotionEvent.obtain(down, down + step * 20L, action, x, compact.center.y, 0)
                try { view.dispatchTouchEvent(event) } finally { event.recycle() }
                frame()
            }
            touch(android.view.MotionEvent.ACTION_DOWN, 0, compact.center.x)
            for (step in 1..6) touch(android.view.MotionEvent.ACTION_MOVE, step, compact.center.x + step * 8f)
            repeat(50) { frame() }
            val expanded = tagged("video-duration-slider").boundsInRoot
            assertTrue("Dragging must expand the first-level track: $compact -> $expanded", expanded.width > compact.width + 60f)
            assertTrue("The faded title must also leave the accessibility tree while expanded",
                tagged("video-duration-label").config.contains(SemanticsProperties.HideFromAccessibility))
            assertEquals("Seconds stay anchored while the track grows", valueBefore.right, tagged("video-duration-seconds").boundsInRoot.right, 0.01f)
            assertEquals("Preview must not persist until release", 10, options.value.duration)
            assertEquals(null, opened)
            touch(android.view.MotionEvent.ACTION_UP, 7, compact.center.x + 48f)
            repeat(50) { frame() }
            assertTrue(options.value.duration > 10)
            assertEquals(92f, tagged("video-duration-slider").boundsInRoot.width, 0.01f)
            assertFalse(tagged("video-duration-label").config.contains(SemanticsProperties.HideFromAccessibility))
            click(tr("宽高比"))
            assertEquals(VideoGenerationChoice.RATIO, opened)
            click(tr("网络搜索"))
            click(tr("自动朗读"))
            assertTrue(search)
            assertTrue(autoRead)
        } finally {
            view.disposeComposition()
            recomposer.close()
            runner.cancel()
            controller.pause().stop().destroy()
        }
    }
}
