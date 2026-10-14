package com.irio

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 紧凑态输入框的换行动画（NATIVE 图形模式才有真实文本度量，换行才会发生）：
 * 每换一行只长一行高，动画慢一点新行和光标就会被 clipToBounds 裁在框外，
 * 快速打字时显慢显卡。动画必须少数几帧内基本落定，并且只失效布局、不逐帧重组编辑器。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerWrapAnimationTest {
    @Test
    fun wrappingToANewLineSettlesWithinAFewFramesWithoutRecomposing() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(controller.get())
        val state = TextFieldState("wrapped text ")
        var lines = 0
        var frameTime = 0L
        try {
            view.setParentCompositionContext(recomposer)
            controller.get().setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme {
                        ComposerField(
                            state = state,
                            placeholder = "",
                            modifier = Modifier.fillMaxWidth(),
                            onTextLayout = { lines = it.lineCount },
                            leading = { Box(Modifier.size(16.dp)) },
                            trailing = { Box(Modifier.size(16.dp)) },
                        )
                    }
                }
            }
            fun frame() {
                Snapshot.sendApplyNotifications()
                runCurrent()
                frameTime += 16_000_000L
                clock.sendFrame(frameTime)
                runCurrent()
                view.requestLayout()
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.AT_MOST),
                )
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            }
            repeat(6) { frame() }
            val startHeight = view.measuredHeight
            state.edit { replace(0, length, "wrapped text ".repeat(35)) }
            val samples = (1..16).map {
                frame()
                Triple(view.measuredHeight, frameTime, recomposer.changeCount)
            }
            val finalHeight = samples.last().first
            assertTrue("Wrapping must grow the field: start=$startHeight final=$finalHeight", finalHeight > startHeight)
            assertTrue("The wrap must actually reflow into multiple lines: lines=$lines", lines >= 4)
            val heights = samples.map { (height, _, _) -> height }
            val launchIndex = heights.indexOfFirst { it != startHeight }
            assertTrue(
                "The wrap must animate instead of jumping: heights=$heights",
                launchIndex >= 0 && heights.any { it > startHeight && it < finalHeight },
            )
            val launchTime = samples[launchIndex].second
            val settledIndex = samples.indexOfFirst { (height, time, _) ->
                time - launchTime <= 96_000_000L &&
                    height >= startHeight + 0.95f * (finalHeight - startHeight)
            }
            assertTrue(
                "The wrap animation must be 95% settled within ~6 frames of starting " +
                    "(slow growth clips the new line and cursor under clipToBounds): heights=$heights",
                settledIndex >= 0,
            )
            val settledAfterMs = (samples[settledIndex].second - launchTime) / 1_000_000L
            repeat(4) { frame() }
            assertEquals(finalHeight, view.measuredHeight)
            assertEquals(
                "The wrap animation must only invalidate layout, not recompose the editor per frame",
                1,
                samples.drop(3).map { (_, _, changes) -> changes }.distinct().size,
            )
            println("Composer wrap probe: start=$startHeight final=$finalHeight settled95%=${settledAfterMs}ms heights=$heights")
        } finally {
            view.disposeComposition()
            recomposer.close()
            runner.cancel()
            controller.pause().stop().destroy()
        }
    }
}
