package com.irio

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.unit.dp
import androidx.recyclerview.widget.RecyclerView
import com.composables.ui.components.BottomSheet
import com.composables.ui.components.BottomSheetDetent
import com.composables.ui.components.rememberBottomSheetState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchResultsTouchTest {
    @Test
    fun fingerSwipesScrollSearchResultsInsteadOfDraggingTheSheet() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val host = ComposeView(controller.get())
        val opened = mutableListOf<String>()
        var dismissals = 0
        var frameTime = 0L
        val body = JSONObject().put("results", JSONArray().apply {
            repeat(30) { index -> put(JSONObject()
                .put("url", "https://example.com/article/$index")
                .put("title", "Search result $index")
                .put("content", "A search result description with enough words to fill two lines of text.")
                .put("favicon", "javascript:invalid")) }
        }).toString()
        try {
            host.setParentCompositionContext(recomposer)
            host.setContent {
                CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                    override fun openUri(uri: String) { opened += uri }
                }) {
                    AppTheme {
                        val state = rememberBottomSheetState(initialDetent = BottomSheetDetent.FullyExpanded)
                        BottomSheet(state = state, onDismissRequest = { dismissals++ }) {
                            SearchResultsSheetContent(body, Modifier.size(400.dp, 240.dp))
                        }
                    }
                }
            }
            controller.get().setContentView(host)
            host.layoutParams = host.layoutParams.apply { width = 600; height = 800 }
            repeat(2) {
                runCurrent()
                clock.sendFrame(frameTime)
                frameTime += 16_000_000L
                runCurrent()
            }
            val root = ShadowDialog.getLatestDialog().window!!.decorView
            fun frame() {
                Snapshot.sendApplyNotifications()
                runCurrent()
                clock.sendFrame(frameTime)
                frameTime += 20_000_000L
                runCurrent()
                root.requestLayout()
                root.measure(
                    View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                )
                root.layout(0, 0, 600, 800)
                shadowOf(Looper.getMainLooper()).idle()
            }
            repeat(8) { frame() }
            val list = findList(root)!!
            assertEquals(30, list.adapter!!.itemCount)
            assertTrue("Fixture must have scrollable content", list.canScrollVertically(1))
            val rootLocation = IntArray(2).also(root::getLocationInWindow)
            fun location() = IntArray(2).also(list::getLocationInWindow)
            val initialTop = location()[1]
            val downTime = SystemClock.uptimeMillis()
            var eventTime = downTime
            fun touch(action: Int, x: Float, y: Float) {
                val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
                eventTime += 20L
                try { root.dispatchTouchEvent(event) } finally { event.recycle() }
            }
            fun swipe(up: Boolean) {
                val position = location()
                val x = position[0] - rootLocation[0] + list.width / 2f
                val top = position[1] - rootLocation[1]
                val start = top + list.height * if (up) 0.85f else 0.15f
                val end = top + list.height * if (up) 0.15f else 0.85f
                touch(MotionEvent.ACTION_DOWN, x, start)
                repeat(12) { index ->
                    touch(MotionEvent.ACTION_MOVE, x, start + (end - start) * (index + 1) / 12f)
                    frame()
                }
                touch(MotionEvent.ACTION_UP, x, end)
                list.stopScroll()
                frame()
            }
            swipe(up = true)
            assertTrue("A finger swipe must scroll search results, not be cancelled by BottomSheet",
                list.computeVerticalScrollOffset() > 40)
            assertEquals("Scrolling inside results must not move the sheet", initialTop, location()[1])
            swipe(up = true)
            val middleOffset = list.computeVerticalScrollOffset()
            swipe(up = false)
            assertTrue("Downward drags away from the top must return toward earlier results",
                list.computeVerticalScrollOffset() < middleOffset - 40)
            assertEquals(initialTop, location()[1])
            assertTrue("Dragging must not open search links", opened.isEmpty())
            assertNull("Dragging must not launch the browser", shadowOf(controller.get()).nextStartedActivity)
            assertEquals("List gestures must not dismiss the sheet", 0, dismissals)

            // Cancelling a drag must release ownership without opening a result, and
            // a fresh tap must still reach the clickable Compose row inside RecyclerView.
            list.scrollToPosition(0)
            repeat(4) { frame() }
            val first = list.getChildAt(0)
            val firstPosition = IntArray(2).also(first::getLocationInWindow)
            val tapX = firstPosition[0] - rootLocation[0] + first.width / 2f
            val tapY = firstPosition[1] - rootLocation[1] + first.height / 2f
            touch(MotionEvent.ACTION_DOWN, tapX, tapY)
            touch(MotionEvent.ACTION_CANCEL, tapX, tapY)
            frame()
            assertTrue("Cancelled gestures must not open results", opened.isEmpty())
            assertNull("Cancelled gestures must not launch the browser", shadowOf(controller.get()).nextStartedActivity)
            touch(MotionEvent.ACTION_DOWN, tapX, tapY)
            touch(MotionEvent.ACTION_UP, tapX, tapY)
            frame()
            val launchedUrl = shadowOf(controller.get()).nextStartedActivity?.dataString
            assertEquals("A fresh tap must still open the result after swiping and cancellation",
                listOf("https://example.com/article/0"), opened + listOfNotNull(launchedUrl))

            // Native gesture ownership must not disable nested scrolling: dragging
            // downward at the first result still needs to move the sheet itself.
            assertFalse(list.canScrollVertically(-1))
            val topPosition = location()
            val dragX = topPosition[0] - rootLocation[0] + list.width / 2f
            val dragY = topPosition[1] - rootLocation[1] + list.height * 0.2f
            touch(MotionEvent.ACTION_DOWN, dragX, dragY)
            repeat(6) { index ->
                touch(MotionEvent.ACTION_MOVE, dragX, dragY + (index + 1) * 10f)
                frame()
            }
            assertTrue("Dragging downward at the top must still hand off to the sheet",
                location()[1] > initialTop + 20)
            touch(MotionEvent.ACTION_CANCEL, dragX, dragY + 60f)
            frame()
        } finally {
            host.disposeComposition()
            recomposer.close()
            runner.cancel()
            controller.pause().stop().destroy()
        }
    }

    private fun findList(view: View): RecyclerView? = when (view) {
        is RecyclerView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findList(view.getChildAt(it)) }
        else -> null
    }
}
