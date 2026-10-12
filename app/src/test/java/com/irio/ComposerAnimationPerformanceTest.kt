package com.irio

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableFloatStateOf
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
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import kotlin.math.roundToInt
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ComposerAnimationPerformanceTest {
    @Test
    fun animationFramesDoNotRecomposeTheEditor() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(controller.get())
        val progress = mutableFloatStateOf(0.1f)
        val text = TextFieldState("wrapped text ".repeat(100))
        var textWidth = 0
        var frameTime = 0L
        try {
            view.setParentCompositionContext(recomposer)
            controller.get().setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme {
                        ComposerField(
                            state = text,
                            placeholder = "",
                            modifier = Modifier.fillMaxWidth().height(400.dp),
                            expanded = true,
                            expansion = progress,
                            onTextLayout = { textWidth = it.layoutInput.constraints.maxWidth },
                            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = Int.MAX_VALUE),
                            leading = { Box(Modifier.size(32.dp)) },
                            trailing = { Box(Modifier.size(32.dp)) },
                            expandedFooter = { Box(Modifier.fillMaxWidth().height(56.dp)) },
                        )
                    }
                }
            }
            fun settle() {
                repeat(4) {
                    Snapshot.sendApplyNotifications()
                    runCurrent()
                    frameTime += 16_000_000L
                    clock.sendFrame(frameTime)
                    runCurrent()
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                    view.requestLayout()
                    view.measure(
                        View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.AT_MOST),
                    )
                    view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                }
            }
            settle()
            val before = recomposer.changeCount
            val samples = (2..10).map { it / 10f } + (9 downTo 1).map { it / 10f }
            samples.forEach { sample ->
                progress.floatValue = sample
                settle()
                // Initially expanded fields use the same 16dp minimum button widths as before.
                val padding = (34f + (20f - 34f) * sample).roundToInt()
                assertEquals("Animated width must keep the original text reflow", 360 - 2 * padding, textWidth)
            }
            val recompositions = recomposer.changeCount - before
            println("Composer animation probe: frames=${samples.size}, recompositions=$recompositions")
            assertTrue("Only layout-boundary changes may recompose the editor: $recompositions", recompositions <= 4)
        } finally {
            view.disposeComposition()
            recomposer.close()
            runner.cancel()
            controller.pause().stop().destroy()
        }
    }
}
