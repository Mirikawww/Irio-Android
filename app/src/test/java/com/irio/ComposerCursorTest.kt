package com.irio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.composables.ui.theme.colors
import com.composables.ui.theme.onFieldColor
import com.composeunstyled.theme.Theme
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.LocalHazeBlurStyle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
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
class ComposerCursorTest {
    @Test
    fun cursorRemainsVisibleAfterExpandingAndCollapsingFocusedComposer() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(controller.get())
        val expanded = mutableStateOf(false)
        val state = TextFieldState()
        val focusRequester = FocusRequester()
        val windowInfo = object : WindowInfo {
            override val isWindowFocused = true
        }
        // The native Skia bundled with Robolectric cannot compile Haze progressive AGSL.
        // This test checks cursor pixels; the real footer source/effect is checked separately.
        val cursorTestBlurStyle = HazeBlurStyle { blurEnabled(false) }
        var cursorColor = 0
        var frameTime = 0L
        try {
            view.setParentCompositionContext(recomposer)
            controller.get().setContentView(view)
            view.setContent {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f),
                    LocalWindowInfo provides windowInfo,
                    LocalHazeBlurStyle provides cursorTestBlurStyle,
                ) {
                    AppTheme {
                        cursorColor = Theme[colors][onFieldColor].toArgb()
                        ComposerField(
                            state = state,
                            placeholder = "",
                            focusRequester = focusRequester,
                            modifier = Modifier.fillMaxWidth().then(if (expanded.value) Modifier.height(400.dp) else Modifier),
                            expanded = expanded.value,
                            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = if (expanded.value) Int.MAX_VALUE else 4),
                            leading = { Box(Modifier.size(32.dp)) },
                            trailing = { Box(Modifier.size(32.dp)) },
                            expandedFooter = {
                                Column(Modifier.fillMaxWidth()) {
                                    Box(Modifier.fillMaxWidth().height(36.dp))
                                    Box(Modifier.fillMaxWidth().height(56.dp))
                                }
                            },
                        )
                    }
                }
            }
            fun measure() {
                view.requestLayout()
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.AT_MOST),
                )
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            }
            fun settle() {
                repeat(4) {
                    Snapshot.sendApplyNotifications()
                    runCurrent()
                    frameTime += 16_000_000L
                    clock.sendFrame(frameTime)
                    runCurrent()
                    measure()
                }
            }
            settle()
            focusRequester.requestFocus()
            settle()
            fun assertCursorVisible(label: String) {
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                try {
                    view.draw(Canvas(bitmap))
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    val cursorPixels = pixels.indices.count { index ->
                        val x = index % bitmap.width
                        val y = index / bitmap.width
                        x in 15 until bitmap.width - 15 && y in 5 until bitmap.height - 5 && pixels[index] == cursorColor
                    }
                    assertTrue("$label: the focused empty composer must draw its cursor (pixels=$cursorPixels)", cursorPixels > 0)
                } finally {
                    bitmap.recycle()
                }
            }
            assertCursorVisible("Compact")
            expanded.value = true
            settle()
            assertCursorVisible("Expanded")
            expanded.value = false
            settle()
            assertCursorVisible("Collapsed")
            expanded.value = true
            settle()
            assertCursorVisible("Expanded again")
        } finally {
            view.disposeComposition()
            recomposer.close()
            runner.cancel()
            controller.pause().stop().destroy()
        }
    }
}
