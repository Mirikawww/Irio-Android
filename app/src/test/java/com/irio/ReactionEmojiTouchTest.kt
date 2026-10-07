package com.irio

import android.os.SystemClock
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.recyclerview.widget.GridLayoutManager
import com.composables.ui.components.BottomSheet
import com.composables.ui.components.BottomSheetDetent
import com.composables.ui.components.rememberBottomSheetState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ReactionEmojiTouchTest {
    @Test
    fun fingerSwipeInsideTheRealSheetScrollsTheNativeEmojiGrid() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(controller.get())
        val selected = mutableListOf<String>()
        var dismissals = 0
        try {
            view.setParentCompositionContext(recomposer)
            view.setContent {
                AppTheme {
                    val state = rememberBottomSheetState(initialDetent = BottomSheetDetent.FullyExpanded)
                    BottomSheet(state = state, onDismissRequest = { dismissals++ }) {
                        ReactionEmojiGrid(
                            category = ReactionEmojiCatalog.categories[1],
                            color = Color.Black,
                            modifier = Modifier.size(400.dp, 240.dp),
                            onSelect = { selected += it },
                        )
                    }
                }
            }
            controller.get().setContentView(view)
            view.layoutParams = view.layoutParams.apply { width = 600; height = 800 }
            runCurrent()
            clock.sendFrame(0L)
            runCurrent()
            clock.sendFrame(16_000_000L)
            runCurrent()
            val touchRoot = ShadowDialog.getLatestDialog().window!!.decorView
            fun layout() {
                touchRoot.requestLayout()
                touchRoot.measure(
                    View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                )
                touchRoot.layout(0, 0, 600, 800)
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                shadowOf(Looper.getMainLooper()).idle()
            }
            layout()
            runCurrent()
            clock.sendFrame(32_000_000L)
            runCurrent()
            layout()
            clock.sendFrame(48_000_000L)
            runCurrent()
            layout()
            val grid = findGrid(touchRoot)!!
            assertTrue("The fixture needs content taller than its viewport", grid.canScrollVertically(1))
            val gridLocation = IntArray(2).also(grid::getLocationInWindow)
            val rootLocation = IntArray(2).also(touchRoot::getLocationInWindow)
            var x = gridLocation[0] - rootLocation[0] + grid.width / 2f
            val top = (gridLocation[1] - rootLocation[1]).toFloat()
            val startY = top + grid.height * 0.85f
            val endY = top + grid.height * 0.15f
            val downTime = SystemClock.uptimeMillis()
            fun touch(action: Int, step: Int, y: Float) {
                val event = MotionEvent.obtain(downTime, downTime + step * 20L, action, x, y, 0)
                try {
                    touchRoot.dispatchTouchEvent(event)
                } finally {
                    event.recycle()
                }
            }
            touch(MotionEvent.ACTION_DOWN, 0, startY)
            for (step in 1..12) {
                touch(MotionEvent.ACTION_MOVE, step, startY + (endY - startY) * step / 12f)
                runCurrent()
                clock.sendFrame(48_000_000L + step * 20_000_000L)
                runCurrent()
                layout()
            }
            touch(MotionEvent.ACTION_UP, 13, endY)
            assertTrue(
                "A real finger drag through Compose Sheet must move the RecyclerView, not just allow programmatic scrolling",
                (grid.layoutManager as GridLayoutManager).findFirstVisibleItemPosition() > 0,
            )
            assertTrue("Dragging must not accidentally select an emoji", selected.isEmpty())
            grid.stopScroll()
            val cell = grid.getChildAt((grid.layoutManager as GridLayoutManager).spanCount) as TextView
            val cellLocation = IntArray(2).also(cell::getLocationInWindow)
            x = cellLocation[0] - rootLocation[0] + cell.width / 2f
            val tapY = cellLocation[1] - rootLocation[1] + cell.height / 2f
            touch(MotionEvent.ACTION_DOWN, 20, tapY)
            touch(MotionEvent.ACTION_CANCEL, 21, tapY)
            assertTrue("A cancelled gesture must not select an emoji", selected.isEmpty())
            touch(MotionEvent.ACTION_DOWN, 30, tapY)
            touch(MotionEvent.ACTION_UP, 31, tapY)
            // TextView posts performClick to the main looper after releasing the pressed state.
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Taps must still reach native emoji cells after a swipe/cancel", listOf(cell.text.toString()), selected)
            assertEquals("Grid gestures must not dismiss the modal sheet", 0, dismissals)
        } finally {
            view.disposeComposition()
            recomposer.close()
            runner.cancel()
            controller.pause().stop().destroy()
        }
    }

    private fun findGrid(view: View): ReactionEmojiRecyclerView? {
        if (view is ReactionEmojiRecyclerView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findGrid(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }
}
