package com.irio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.recyclerview.widget.RecyclerView
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class ComposeRecyclerExpansionTest {
    @Test
    fun cachedHolderReturningWithTheSameKeyMustNotResumeAStaleIntermediateHeight() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val host = ComposeView(controller.get())
        val expanded = androidx.compose.runtime.mutableStateOf(false)
        var time = 0L
        try {
            host.setParentCompositionContext(recomposer)
            controller.get().setContentView(host, ViewGroup.LayoutParams(360, 640))
            host.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme {
                        AndroidComposeRecyclerList(items = listOf(0), key = { it }, modifier = Modifier.fillMaxSize()) {
                            val draft = remember { TextFieldState("Description") }
                            ToolSettingsRow(ToolSettingsEntry("test", "Test", "Description"), true, expanded.value, draft, {}, {}, {}, {}, {})
                        }
                    }
                }
            }
            fun frame() {
                Snapshot.sendApplyNotifications()
                runCurrent()
                time += 16_000_000L
                clock.sendFrame(time)
                runCurrent()
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                host.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
                host.layout(0, 0, 360, 640)
            }
            fun recycler(view: View): RecyclerView? = if (view is RecyclerView) view else
                if (view is ViewGroup) (0 until view.childCount).firstNotNullOfOrNull { recycler(view.getChildAt(it)) } else null
            repeat(8) { frame() }
            val list = recycler(host)!!
            val holder = list.findViewHolderForAdapterPosition(0)!!
            val row = holder.itemView
            val compact = row.height
            expanded.value = true
            repeat(80) { frame() }
            val full = row.height
            assertTrue("Expansion must update native row height even when drawing is skipped", full > compact + 100)
            expanded.value = false
            repeat(4) { frame() }
            val intermediate = row.height
            assertTrue(intermediate in (compact + 1) until full)
            // Detach without recycling, just as the local item-view cache does.
            list.removeView(row)
            expanded.value = true
            repeat(80) { frame() }
            list.addView(row)
            (list.adapter as RecyclerView.Adapter<RecyclerView.ViewHolder>).bindViewHolder(holder, 0)
            row.requestLayout()
            row.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.AT_MOST))
            assertEquals("Same-key cache return must reflect the current expansion target before it is placed", full, row.measuredHeight)
        } finally {
            host.disposeComposition()
            recomposer.close()
            runner.cancel()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun expansionFinishesInsideRecyclerViewWithoutForcingRowMeasurements() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val host = ComposeView(controller.get())
        val expanded = mutableStateMapOf<Int, Boolean>()
        val bitmap = Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888)
        var time = 0L
        try {
            host.setParentCompositionContext(recomposer)
            controller.get().setContentView(host, ViewGroup.LayoutParams(360, 640))
            host.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme {
                        AndroidComposeRecyclerList(items = (0 until 20).toList(), key = { it }, modifier = Modifier.fillMaxSize()) { id ->
                            val entry = ToolSettingsEntry("tool_$id", "Tool $id", "Description $id")
                            val draft = remember { TextFieldState(entry.defaultDescription) }
                            ToolSettingsRow(entry, true, expanded[id] == true, draft, { expanded[id] = it }, {}, {}, {}, {})
                        }
                    }
                }
            }
            fun frame() {
                Snapshot.sendApplyNotifications()
                runCurrent()
                time += 16_000_000L
                clock.sendFrame(time)
                runCurrent()
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                // Normal traversal only: never forceLayout/requestLayout on the holder.
                if (host.isLayoutRequested) {
                    host.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
                    host.layout(0, 0, 360, 640)
                }
                host.draw(Canvas(bitmap))
            }
            fun recycler(view: View): RecyclerView? = if (view is RecyclerView) view else
                if (view is ViewGroup) (0 until view.childCount).firstNotNullOfOrNull { recycler(view.getChildAt(it)) } else null
            repeat(8) { frame() }
            val list = recycler(host)!!
            fun row() = list.findViewHolderForAdapterPosition(0)!!.itemView
            val compact = row().height
            expanded[0] = true
            val heights = List(80) { frame(); row().height }
            assertTrue("Expansion must make progress inside the actual RecyclerView", heights.max() > compact + 100)
            val full = row().height
            assertTrue("Expansion must animate through intermediate heights", heights.any { it > compact && it < full })
            val before = row().height
            row().forceLayout()
            list.requestLayout()
            frame()
            assertEquals("Animation must reach its final height without a forced RecyclerView measure", row().height, before)
            expanded[0] = false
            repeat(80) { frame() }
            assertEquals("Collapse must finish without an external relayout", compact, row().height)

            // Expand near the viewport edge, then move the animating row through the
            // detached cache/pool before its spring settles.
            repeat(4) {
                expanded[0] = true
                repeat(3) { frame() }
                list.scrollToPosition(15)
                repeat(8) { frame() }
                expanded[0] = false
                repeat(3) { frame() }
                expanded[0] = true
                list.scrollToPosition(0)
                repeat(80) { frame() }
                assertEquals("Returning an interrupted row must finish expanded, not freeze halfway", full, row().height)
                expanded[0] = false
                repeat(80) { frame() }
                assertEquals("A reused row must still collapse fully", compact, row().height)
            }
        } finally {
            bitmap.recycle()
            host.disposeComposition()
            recomposer.close()
            runner.cancel()
            controller.pause().stop().destroy()
        }
    }
}
