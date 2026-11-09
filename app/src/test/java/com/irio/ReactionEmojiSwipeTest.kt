package com.irio

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.GridLayoutManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ReactionEmojiSwipeTest {
    @Test fun leftAndRightSwipesNavigateOnceWithoutSelectingOrScrollingVertically() = withGrid { grid, parent ->
        val steps = mutableListOf<Int>()
        var selections = 0
        grid.onCategorySwipe = { steps += it }
        val category = ReactionEmojiCatalog.categories[1]
        grid.update(category.name, category.emojis, android.graphics.Color.BLACK) { selections++ }
        layout(grid)
        swipe(grid, -180f, 10f)
        swipe(grid, 180f, -10f)
        assertEquals(listOf(1, -1), steps)
        assertEquals(0, selections)
        assertEquals(0, (grid.layoutManager as GridLayoutManager).findFirstVisibleItemPosition())
        assertTrue("DOWN must retain native ownership before sheet slop", parent.claims.contains(true))
        assertEquals("Gesture completion must release parent interception", false, parent.claims.last())
    }

    @Test fun rtlUsesTheOppositePhysicalDirectionAndTheLatestCallback() = withGrid { grid, _ ->
        val steps = mutableListOf<Int>()
        grid.layoutDirection = View.LAYOUT_DIRECTION_RTL
        grid.onCategorySwipe = { fail("Stale callback") }
        grid.onCategorySwipe = { steps += it }
        swipe(grid, -180f, 0f)
        swipe(grid, 180f, 0f)
        assertEquals(listOf(-1, 1), steps)
    }

    @Test fun verticalIntentLocksNativeScrollingEvenWhenTheFingerLaterMovesSideways() = withGrid { grid, _ ->
        val steps = mutableListOf<Int>()
        grid.onCategorySwipe = { steps += it }
        val down = SystemClock.uptimeMillis()
        touch(grid, down, 0, MotionEvent.ACTION_DOWN, 240f, 210f)
        touch(grid, down, 1, MotionEvent.ACTION_MOVE, 240f, 170f)
        for (step in 2..12) touch(grid, down, step, MotionEvent.ACTION_MOVE, 240f - step * 12, 170f - step * 10)
        touch(grid, down, 13, MotionEvent.ACTION_UP, 96f, 50f)
        layout(grid)
        assertTrue(steps.isEmpty())
        assertTrue("Vertical native RecyclerView scroll must survive the category gesture detector",
            (grid.layoutManager as GridLayoutManager).findFirstVisibleItemPosition() > 0)
    }

    @Test fun shortDragCancelAndMultitouchNeverSwitchCategory() = withGrid { grid, parent ->
        val steps = mutableListOf<Int>()
        grid.onCategorySwipe = { steps += it }
        val slop = ViewConfiguration.get(grid.context).scaledTouchSlop.toFloat()
        swipe(grid, slop * 1.5f, 0f)
        val down = SystemClock.uptimeMillis()
        touch(grid, down, 0, MotionEvent.ACTION_DOWN, 240f, 120f)
        touch(grid, down, 1, MotionEvent.ACTION_MOVE, 120f, 120f)
        touch(grid, down, 2, MotionEvent.ACTION_CANCEL, 60f, 120f)
        touch(grid, down, 3, MotionEvent.ACTION_DOWN, 240f, 120f)
        touch(grid, down, 4, MotionEvent.ACTION_MOVE, 120f, 120f)
        val pointers = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coordinates = arrayOf(MotionEvent.PointerCoords().apply { x = 120f; y = 120f; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = 140f; y = 120f; pressure = 1f; size = 1f })
        val multi = MotionEvent.obtain(down, down + 100, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2, pointers, coordinates, 0, 0, 1f, 1f, 0, 0, 0, 0)
        try { grid.dispatchTouchEvent(multi) } finally { multi.recycle() }
        touch(grid, down, 6, MotionEvent.ACTION_UP, 40f, 120f)
        assertTrue(steps.isEmpty())
        assertEquals(false, parent.claims.last())
        // A new ordinary click must still reach the real native holder after cancellation.
        var clicks = 0
        val category = ReactionEmojiCatalog.categories[1]
        grid.update(category.name, category.emojis, android.graphics.Color.BLACK) { clicks++ }
        layout(grid)
        val cell = grid.getChildAt(0)
        val x = cell.left + cell.width / 2f
        val y = cell.top + cell.height / 2f
        touch(grid, down, 20, MotionEvent.ACTION_DOWN, x, y)
        touch(grid, down, 21, MotionEvent.ACTION_UP, x, y)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, clicks)
    }

    @Test fun releaseClearsSwipeCallback() = withGrid { grid, _ ->
        var calls = 0
        grid.onCategorySwipe = { calls++ }
        grid.release()
        swipe(grid, -180f, 0f)
        assertEquals(0, calls)
    }

    private fun withGrid(block: (ReactionEmojiRecyclerView, TouchParent) -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val parent = TouchParent(controller.get())
        val grid = ReactionEmojiRecyclerView(controller.get())
        try {
            parent.addView(grid, FrameLayout.LayoutParams(400, 240))
            controller.get().setContentView(parent)
            val category = ReactionEmojiCatalog.categories[1]
            grid.update(category.name, category.emojis, android.graphics.Color.BLACK) {}
            layout(grid)
            block(grid, parent)
        } finally {
            grid.release()
            controller.pause().stop().destroy()
        }
    }

    private class TouchParent(context: android.content.Context) : FrameLayout(context) {
        val claims = mutableListOf<Boolean>()
        override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
            claims += disallowIntercept
            super.requestDisallowInterceptTouchEvent(disallowIntercept)
        }
    }

    private fun layout(grid: ReactionEmojiRecyclerView) {
        shadowOf(Looper.getMainLooper()).idle()
        grid.requestLayout()
        grid.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY))
        grid.layout(0, 0, 400, 240)
    }

    private fun swipe(grid: ReactionEmojiRecyclerView, dx: Float, dy: Float) {
        val down = SystemClock.uptimeMillis()
        val x = if (dx > 0) 70f else 300f
        touch(grid, down, 0, MotionEvent.ACTION_DOWN, x, 120f)
        for (step in 1..12) touch(grid, down, step, MotionEvent.ACTION_MOVE, x + dx * step / 12, 120f + dy * step / 12)
        touch(grid, down, 13, MotionEvent.ACTION_UP, x + dx, 120f + dy)
    }

    private fun touch(grid: ReactionEmojiRecyclerView, down: Long, step: Int, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, down + step * 20L, action, x, y, 0)
        try { grid.dispatchTouchEvent(event) } finally { event.recycle() }
    }
}
