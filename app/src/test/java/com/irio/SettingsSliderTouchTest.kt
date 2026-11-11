package com.irio

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.recyclerview.widget.RecyclerView
import com.composables.ui.components.Text
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class SettingsSliderTouchTest {
    @Test fun verticalScrollStartingOnTheTrackNeverChangesTheValue() = runTest {
        withUi { f ->
            val bounds = f.slider().boundsInRoot
            val x = bounds.left + bounds.width * .8f
            val y = bounds.center.y
            f.touch(MotionEvent.ACTION_DOWN, x, y)
            assertEquals("Touch down must not change the slider", 25, f.value.intValue)
            for (step in 1..10) {
                f.touch(MotionEvent.ACTION_MOVE, x + step % 2, y - step * 15f)
                assertEquals("Vertical scrolling must not preview or save a new value", 25, f.value.intValue)
            }
            f.touch(MotionEvent.ACTION_UP, x, y - 150f)
            assertTrue("The real RecyclerView must receive the scroll", f.recycler().computeVerticalScrollOffset() > 0)
            assertTrue(f.changes.isEmpty())
            assertEquals(0, f.finished)
        }
    }

    @Test fun tappingChangesTheValueOnlyAfterReleaseAndFinishesOnce() = runTest {
        withUi { f ->
            val bounds = f.slider().boundsInRoot
            val x = bounds.left + bounds.width * .8f
            f.touch(MotionEvent.ACTION_DOWN, x, bounds.center.y)
            assertEquals(25, f.value.intValue)
            assertTrue(f.changes.isEmpty())
            assertEquals(0, f.finished)
            f.touch(MotionEvent.ACTION_UP, x, bounds.center.y)
            assertTrue(f.value.intValue in 79..82)
            assertEquals(listOf(f.value.intValue), f.changes)
            assertEquals(1, f.finished)
        }
    }

    @Test fun horizontalDraggingPreviewsContinuouslyAndFinishesOnlyOnRelease() = runTest {
        withUi { f ->
            val bounds = f.slider().boundsInRoot
            val y = bounds.center.y
            val start = bounds.left + bounds.width * .25f
            f.touch(MotionEvent.ACTION_DOWN, start, y)
            assertEquals(25, f.value.intValue)
            for (step in 1..8) {
                f.touch(MotionEvent.ACTION_MOVE, start + step * 20f, y)
                assertEquals("Dragging may preview, but must not finish yet", 0, f.finished)
            }
            assertTrue(f.value.intValue > 65)
            assertTrue("Updates must continue throughout the drag", f.changes.size >= 5)
            assertTrue(f.changes.zipWithNext().all { (a, b) -> b > a })
            val preview = f.value.intValue
            f.touch(MotionEvent.ACTION_UP, start + 160f, y)
            assertEquals(preview, f.value.intValue)
            assertEquals(1, f.finished)
            assertEquals(0, f.recycler().computeVerticalScrollOffset())
        }
    }

    @Test fun cancelledPressDoesNotChangeTheValueOrPoisonTheNextTap() = runTest {
        withUi { f ->
            val bounds = f.slider().boundsInRoot
            f.touch(MotionEvent.ACTION_DOWN, bounds.right - 12f, bounds.center.y)
            f.touch(MotionEvent.ACTION_CANCEL, bounds.right - 12f, bounds.center.y)
            assertEquals(25, f.value.intValue)
            assertTrue(f.changes.isEmpty())
            assertEquals(0, f.finished)
            f.touch(MotionEvent.ACTION_DOWN, bounds.left + 8f, bounds.center.y)
            assertEquals(25, f.value.intValue)
            f.touch(MotionEvent.ACTION_UP, bounds.left + 8f, bounds.center.y)
            assertEquals(0, f.value.intValue)
            assertEquals(1, f.finished)
        }
    }

    @Test fun accessibilityProgressKeepsTheConfiguredStepAndCompletionCallback() = runTest {
        withUi(step = 5) { f ->
            assertTrue(f.slider().config[SemanticsActions.SetProgress].action!!.invoke(63f))
            f.frames()
            assertEquals(65, f.value.intValue)
            assertEquals(1, f.finished)
            assertFalse(f.slider().config[SemanticsActions.SetProgress].action!!.invoke(65f))
            assertEquals(1, f.finished)
        }
    }

    @Test fun keyboardAdjustmentStillWorksAfterFocusingWithATap() = runTest {
        withUi(step = 5) { f ->
            val bounds = f.slider().boundsInRoot
            f.touch(MotionEvent.ACTION_DOWN, bounds.center.x, bounds.center.y)
            f.touch(MotionEvent.ACTION_UP, bounds.center.x, bounds.center.y)
            assertEquals(50, f.value.intValue)
            val initialFinished = f.finished
            assertTrue(f.view.dispatchKeyEvent(android.view.KeyEvent(
                android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_RIGHT)))
            f.frames()
            assertEquals(55, f.value.intValue)
            assertEquals(initialFinished, f.finished)
            assertTrue(f.view.dispatchKeyEvent(android.view.KeyEvent(
                android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_RIGHT)))
            f.frames()
            assertEquals(initialFinished + 1, f.finished)
        }
    }

    private fun TestScope.withUi(step: Int = 1, block: (Fixture) -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(controller.get())
        val f = Fixture(this, view, clock)
        try {
            view.setParentCompositionContext(recomposer)
            controller.get().setContentView(view)
            view.setContent {
                AppTheme {
                    CompositionLocalProvider(LocalDensity provides Density(1f)) {
                        AndroidComposeRecyclerList(
                            items = (0..20).toList(), key = { it }, modifier = Modifier.fillMaxSize(),
                        ) { item ->
                            if (item == 0) {
                                SettingsSlider("Touch fixture", f.value.intValue, 0..100,
                                    onValueChange = { f.changes += it; f.value.intValue = it },
                                    onValueChangeFinished = { f.finished++ }, step = step)
                            } else {
                                Text("Row $item", modifier = Modifier.height(80.dp))
                            }
                        }
                    }
                }
            }
            f.frames(30)
            block(f)
        } finally {
            view.disposeComposition(); recomposer.close(); runner.cancel()
            controller.pause().stop().destroy()
        }
    }

    private class Fixture(val scope: TestScope, val view: ComposeView, val clock: BroadcastFrameClock) {
        val value = mutableIntStateOf(25)
        val changes = mutableListOf<Int>()
        var finished = 0
        private var time = 0L
        private var downTime = 0L
        private var eventTime = SystemClock.uptimeMillis()
        fun frames(count: Int = 3) = repeat(count) {
            shadowOf(Looper.getMainLooper()).idle()
            Snapshot.sendApplyNotifications(); scope.runCurrent()
            time += 16_000_000L; clock.sendFrame(time); scope.runCurrent()
            view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 360, 600)
        }
        fun touch(action: Int, x: Float, y: Float) {
            eventTime += 20L
            if (action == MotionEvent.ACTION_DOWN) downTime = eventTime
            val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
            try { view.dispatchTouchEvent(event) } finally { event.recycle() }
            frames()
        }
        private fun views(v: View = view): List<View> = listOf(v) +
            if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
        private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
        fun slider() = views().filterIsInstance<ViewRootForTest>().flatMap {
            descendants(it.semanticsOwner.unmergedRootSemanticsNode)
        }.single { it.config.contains(SemanticsActions.SetProgress) }
        fun recycler() = views().filterIsInstance<RecyclerView>().single()
    }
}
