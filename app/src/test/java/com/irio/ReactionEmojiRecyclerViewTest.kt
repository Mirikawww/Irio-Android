package com.irio

import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ReactionEmojiRecyclerViewTest {
    @Test
    fun catalogUsesARecyclingNativeGridWithAdaptiveColumns() = withGrid { grid ->
        val category = ReactionEmojiCatalog.categories.first()
        grid.update(category.name, category.emojis, android.graphics.Color.BLACK) {}
        layout(grid, 400)
        assertTrue(grid.layoutManager is GridLayoutManager)
        assertEquals(8, (grid.layoutManager as GridLayoutManager).spanCount)
        assertEquals(category.emojis.size, grid.adapter!!.itemCount)
        assertTrue(grid.adapter!!.hasStableIds())
        assertEquals(null, grid.itemAnimator)
        assertTrue(grid.isNestedScrollingEnabled)
        assertTrue("Only visible/prefetched cells should exist", grid.childCount in 1 until category.emojis.size)
        assertTrue((0 until grid.childCount).all { grid.getChildAt(it) is TextView })
        layout(grid, 240)
        assertEquals(5, (grid.layoutManager as GridLayoutManager).spanCount)
    }

    @Test
    fun unchangedCategoryKeepsScrollAndUsesTheLatestSelectionCallback() = withGrid { grid ->
        val category = ReactionEmojiCatalog.categories[1]
        val selected = mutableListOf<String>()
        grid.update(category.name, category.emojis, android.graphics.Color.BLACK) { selected += "old:$it" }
        layout(grid, 400)
        val manager = grid.layoutManager as GridLayoutManager
        manager.scrollToPositionWithOffset(80, 0)
        layout(grid, 400)
        val before = manager.findFirstVisibleItemPosition()
        assertTrue(before > 0)
        grid.update(category.name, category.emojis, android.graphics.Color.BLACK) { selected += "new:$it" }
        layout(grid, 400)
        assertEquals(before, manager.findFirstVisibleItemPosition())
        val cell = grid.getChildAt(0) as TextView
        cell.performClick()
        assertEquals(listOf("new:${cell.text}"), selected)
    }

    @Test
    fun switchingCategoryResetsToTopAndLatestRapidSwitchWins() = withGrid { grid ->
        val categories = ReactionEmojiCatalog.categories
        grid.update(categories[1].name, categories[1].emojis, android.graphics.Color.BLACK) {}
        layout(grid, 400)
        (grid.layoutManager as GridLayoutManager).scrollToPositionWithOffset(80, 0)
        layout(grid, 400)
        grid.update(categories[2].name, categories[2].emojis, android.graphics.Color.BLACK) {}
        grid.update(categories[4].name, categories[4].emojis, android.graphics.Color.BLACK) {}
        grid.update(categories[8].name, categories[8].emojis, android.graphics.Color.BLACK) {}
        awaitItems(grid, categories[8].emojis.size)
        assertEquals(0, (grid.layoutManager as GridLayoutManager).findFirstVisibleItemPosition())
        assertEquals(categories[8].emojis.first(), (grid.getChildAt(0) as TextView).text.toString())
    }

    @Test
    fun stableIdsAreUniqueAcrossTheWholeCatalogAndRetainedOnReturn() = withGrid { grid ->
        val emojis = ReactionEmojiCatalog.categories.flatMap { it.emojis }
        grid.update("all", emojis, android.graphics.Color.BLACK) {}
        val ids = emojis.indices.map { grid.adapter!!.getItemId(it) }
        assertEquals(ids.size, ids.toSet().size)
        val firstId = ids.first()
        grid.update("other", listOf("👍"), android.graphics.Color.BLACK) {}
        awaitItems(grid, 1)
        grid.update("return", listOf(emojis.first()), android.graphics.Color.BLACK) {}
        awaitItems(grid, 1, emojis.first())
        assertEquals(firstId, grid.adapter!!.getItemId(0))
    }

    @Test
    fun themeUpdatesDoNotRecreateCellsOrLeaveStaleTextColor() = withGrid { grid ->
        val category = ReactionEmojiCatalog.categories.first()
        grid.update(category.name, category.emojis, android.graphics.Color.BLACK) {}
        layout(grid, 400)
        assertEquals(android.graphics.Color.BLACK, (grid.getChildAt(0) as TextView).currentTextColor)
        grid.update(category.name, category.emojis, android.graphics.Color.WHITE) {}
        layout(grid, 400)
        assertEquals(android.graphics.Color.WHITE, (grid.getChildAt(0) as TextView).currentTextColor)
        assertEquals(category.emojis.first(), (grid.getChildAt(0) as TextView).contentDescription)
    }

    @Test
    fun releaseClearsAdapterAndSelectionCallback() = withGrid { grid ->
        var clicks = 0
        grid.update("one", listOf("❤️"), android.graphics.Color.BLACK) { clicks++ }
        layout(grid, 400)
        val cell = grid.getChildAt(0)
        grid.release()
        cell.performClick()
        assertEquals(0, clicks)
        assertEquals(null, grid.adapter)
    }

    private fun withGrid(test: (ReactionEmojiRecyclerView) -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val grid = ReactionEmojiRecyclerView(controller.get())
        try {
            controller.get().setContentView(grid)
            test(grid)
        } finally {
            grid.release()
            controller.pause().stop().destroy()
        }
    }

    private fun layout(grid: RecyclerView, widthDp: Int) {
        val density = grid.resources.displayMetrics.density
        val width = (widthDp * density).roundToInt()
        val height = (240 * density).roundToInt()
        if (grid.layoutParams.width != width || grid.layoutParams.height != height) {
            grid.layoutParams = grid.layoutParams.apply {
                this.width = width
                this.height = height
            }
        }
        // Keep automatic Activity traversals on the same viewport as our manual measurements.
        shadowOf(Looper.getMainLooper()).idle()
        grid.requestLayout()
        grid.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        grid.layout(0, 0, grid.measuredWidth, grid.measuredHeight)
    }

    private fun awaitItems(grid: RecyclerView, count: Int, firstEmoji: String? = null) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (System.nanoTime() < deadline) {
            layout(grid, 400)
            if (grid.adapter!!.itemCount == count &&
                (firstEmoji == null || (grid.getChildAt(0) as? TextView)?.text?.toString() == firstEmoji)
            ) return
            Thread.sleep(10)
        }
        throw AssertionError("Grid did not commit the expected category")
    }
}
