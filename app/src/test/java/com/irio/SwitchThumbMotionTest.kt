package com.irio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.composables.ui.theme.colors
import com.composables.ui.theme.switchThumbColor
import com.composeunstyled.theme.Theme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
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
import kotlin.math.abs

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SwitchThumbMotionTest {
    @Test fun thumbBrieflyStretchesInBothDirectionsAndSettlesAfterRapidReversals() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(controller.get())
        val checked = mutableStateOf(false)
        val enabled = mutableStateOf(true)
        val direction = mutableStateOf(LayoutDirection.Ltr)
        var callbacks = 0
        var thumbColor = 0
        var time = 0L
        try {
            view.setParentCompositionContext(recomposer)
            controller.get().setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(2f), LocalLayoutDirection provides direction.value) {
                    AppTheme {
                        thumbColor = Theme[colors][switchThumbColor].toArgb()
                        Box(Modifier.fillMaxSize().background(Color(0xFF0C1830)).padding(24.dp)) {
                            IrioSwitch(checked = checked.value, enabled = enabled.value,
                                accessibilityLabel = "Motion fixture", onCheckedChange = { callbacks++; checked.value = it })
                        }
                    }
                }
            }
            fun frame() {
                Snapshot.sendApplyNotifications(); advanceTimeBy(16); runCurrent()
                time += 16_000_000L; clock.sendFrame(time); runCurrent()
                shadowOf(Looper.getMainLooper()).idle()
                view.requestLayout()
                view.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, 400, 200)
                (view.getChildAt(0) as ViewRootForTest).measureAndLayoutForTest()
            }
            fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
            fun switch() = descend((view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
                .single { it.config.contains(SemanticsProperties.ToggleableState) }
            fun thumb(): Rect {
                val track = switch().boundsInRoot
                val bitmap = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
                try {
                    view.draw(Canvas(bitmap))
                    val points = buildList {
                        for (y in track.top.toInt() until track.bottom.toInt()) {
                            for (x in track.left.toInt() until track.right.toInt()) {
                                if (bitmap.getPixel(x, y) == thumbColor) add(x to y)
                            }
                        }
                    }
                    assertTrue("The real switch thumb must be painted", points.isNotEmpty())
                    return Rect(points.minOf { it.first }.toFloat(), points.minOf { it.second }.toFloat(),
                        points.maxOf { it.first } + 1f, points.maxOf { it.second } + 1f)
                } finally { bitmap.recycle() }
            }
            fun click() { assertTrue(switch().config[SemanticsActions.OnClick].action!!.invoke()) }
            repeat(40) { frame() }
            for (layout in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
                direction.value = layout
                repeat(40) { frame() }
                val off = thumb()
                assertEquals("Initial/resting thumb remains circular", off.height, off.width, 1f)
                val track = switch().boundsInRoot
                assertEquals(88f, track.width, .1f)
                assertEquals(48f, track.height, .1f)
                for (target in listOf(true, false)) {
                    val before = thumb()
                    val previousCallbacks = callbacks
                    click()
                    val samples = (0 until 40).map { frame(); thumb() }
                    val peak = samples.maxOf { it.width }
                    assertTrue("The painted thumb must stretch during $layout/$target: $peak", peak >= before.width + 4f)
                    assertTrue("Stretch stays subtle", peak <= before.width + 10f)
                    // Exact-color bounds omit antialiased edge pixels. A capsule can fill
                    // one extra pixel at each vertical edge compared with the resting circle.
                    assertTrue("Thumb height stays fixed within raster edge coverage", samples.all {
                        abs(it.height - before.height) <= 2f && abs(it.center.y - before.center.y) <= .5f
                    })
                    assertTrue("The thumb stays within the original track", samples.all {
                        it.left >= track.left + 3f && it.right <= track.right - 3f
                    })
                    assertEquals("Switch layout cannot grow with its thumb", track, switch().boundsInRoot)
                    assertEquals("One state change invokes one callback", previousCallbacks + 1, callbacks)
                    val after = thumb()
                    assertEquals("Thumb returns to a circle", before.width, after.width, 1f)
                    val movesRight = target == (layout == LayoutDirection.Ltr)
                    assertTrue("Thumb must move in the layout's toggle direction",
                        if (movesRight) after.center.x > before.center.x else after.center.x < before.center.x)
                    assertEquals(if (target) ToggleableState.On else ToggleableState.Off,
                        switch().config[SemanticsProperties.ToggleableState])
                }
                repeat(4) { click(); repeat(3) { frame() } }
                repeat(40) { frame() }
                assertFalse(checked.value)
                assertEquals("Interrupted toggles settle to the requested endpoint", off, thumb())
                enabled.value = false
                repeat(40) { frame() }
                val previousCallbacks = callbacks
                val point = switch().boundsInRoot.center
                val down = SystemClock.uptimeMillis()
                for ((index, action) in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).withIndex()) {
                    val event = MotionEvent.obtain(down, down + index * 20L, action, point.x, point.y, 0)
                    try { view.dispatchTouchEvent(event) } finally { event.recycle() }
                    frame()
                }
                repeat(40) { frame() }
                assertEquals("Disabled switch cannot toggle", previousCallbacks, callbacks)
                assertFalse(checked.value)
                enabled.value = true
            }
        } finally {
            view.disposeComposition(); recomposer.close(); runner.cancel()
            controller.pause().stop().destroy()
        }
    }
}
