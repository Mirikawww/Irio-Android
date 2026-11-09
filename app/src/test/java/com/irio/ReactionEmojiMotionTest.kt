package com.irio

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationInstance
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import com.composables.ui.components.BottomSheetDetent
import com.composables.ui.components.rememberBottomSheetState
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
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt
import android.os.SystemClock
import android.view.MotionEvent
import org.robolectric.shadows.ShadowDialog

@OptIn(ExperimentalCoroutinesApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReactionEmojiMotionTest {
    @Test fun distantTabClicksAnimateOnlyTheSourceAndDestinationWithinOnePageWidth() = runTest {
        withPicker {
            suspend fun jump(from: Int, to: Int) {
                val source = grids().single()
                val sourceLeft = tagged("reaction-emoji-page-$from").positionInRoot.x
                clickCategory(to)
                frames(1)
                val destination = grids().single { it !== source }
                repeat(80) {
                    val mounted = nodes().mapNotNull { node ->
                        node.config.getOrElse(SemanticsProperties.TestTag) { "" }
                            .removePrefix("reaction-emoji-page-").toIntOrNull()
                    }
                    assertTrue("Only the source and destination may mount, not intervening categories: $mounted",
                        mounted.all { it == from || it == to })
                    assertTrue("The destination must start at most one viewport away",
                        kotlin.math.abs(tagged("reaction-emoji-page-$to").positionInRoot.x - sourceLeft) <= destination.width + 1f)
                    frames(1)
                }
                assertTrue("The destination grid must survive settling", grids().single() === destination)
                assertTrue(tagged("reaction-emoji-tab-$to").config[SemanticsProperties.Selected])
                assertEquals(sourceLeft, tagged("reaction-emoji-page-$to").positionInRoot.x, 1f)
            }
            jump(0, 8)
            jump(8, 0)
        }
    }

    @Test
    fun tabClicksSlideTheRealNativePagesInBothDirections() = runTest {
        withPicker {
            val first = grids().single()
            val left = tagged("reaction-emoji-page-0").positionInRoot.x
            clickCategory(1)
            frames(6)

            assertTrue("The outgoing native page must slide left instead of replacing its contents",
                tagged("reaction-emoji-page-0").positionInRoot.x < left - 1)
            assertEquals("Outgoing native content must remain intact during the slide",
                ReactionEmojiCatalog.categories[0].emojis.first(), (first.getChildAt(0) as TextView).text.toString())
            assertEquals("Both native pages must coexist during the transition", 2, grids().size)
            frames(80)
            val second = grids().single()
            assertEquals(ReactionEmojiCatalog.categories[1].emojis.first(), (second.getChildAt(0) as TextView).text.toString())
            assertEquals(left, tagged("reaction-emoji-page-1").positionInRoot.x, 1f)
            clickCategory(0)
            frames(6)
            assertTrue("Going back must slide the outgoing page right",
                tagged("reaction-emoji-page-1").positionInRoot.x > left + 1)
            frames(80)
            assertEquals(1, grids().size)
            assertEquals(ReactionEmojiCatalog.categories[0].emojis.first(), (grids().single().getChildAt(0) as TextView).text.toString())
        }
    }

    @Test
    fun indicatorSlidesBetweenMeasuredCapsulesAndRetargetsWithoutJumping() = runTest {
        withPicker {
            fun indicator() = tagged("reaction-emoji-tab-indicator").positionInRoot.x
            val start = indicator()
            val target = tagged("reaction-emoji-tab-1").positionInRoot.x
            clickCategory(1)
            frames(3)
            val between = indicator()
            assertTrue("The active capsule must move continuously, not disappear/reappear", between > start && between < target)
            clickCategory(0)
            frames(1)
            assertTrue("Interrupted selection must retarget the same capsule", indicator() < target)
            frames(80)
            val active = tagged("reaction-emoji-tab-indicator")
            val first = tagged("reaction-emoji-tab-0")
            assertEquals(first.positionInRoot.x, active.positionInRoot.x, 1f)
            assertEquals(first.size.width, active.size.width)
            assertEquals(first.size.height, active.size.height)
        }
    }

    @Test
    fun heldTabRippleStaysInsideTheSameCapsuleOutline() = runTest {
        withPicker(tabsOnly = true) {
            val tab = tagged("reaction-emoji-tab-1").boundsInWindow
            assertTrue("The tab must be visible before pressing: $tab", tab.width > 1f && tab.height > 1f)
            val rootLocation = IntArray(2).also(root::getLocationInWindow)
            val left = (tab.left - rootLocation[0]).roundToInt()
            val top = (tab.top - rootLocation[1]).roundToInt()
            val width = tab.width.roundToInt()
            val height = tab.height.roundToInt()
            fun pixel(x: Int, y: Int): Int {
                val bitmap = android.graphics.Bitmap.createBitmap(600, 900, android.graphics.Bitmap.Config.ARGB_8888)
                root.draw(android.graphics.Canvas(bitmap))
                return bitmap.getPixel(x, y).also { bitmap.recycle() }
            }
            val corner = pixel(left + 1, top + 1)
            val inside = pixel(left + width / 2, top + height - 4)
            val downTime = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN,
                left + width / 2f, top + height / 2f, 0)
            try { assertTrue("Press must reach the real tabs", root.dispatchTouchEvent(down)) } finally { down.recycle() }
            frames(12)

            assertEquals("Bounded ripple must not paint the capsule corner", corner, pixel(left + 1, top + 1))
            assertNotEquals("Ripple must remain visible inside the capsule, not be disabled", inside, pixel(left + width / 2, top + height - 4))
            val cancel = MotionEvent.obtain(downTime, downTime + 200, MotionEvent.ACTION_CANCEL,
                left + width / 2f, top + height / 2f, 0)
            try { root.dispatchTouchEvent(cancel) } finally { cancel.recycle() }
        }
    }

    private suspend fun TestScope.withPicker(tabsOnly: Boolean = false, block: suspend Picker.() -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(controller.get())
        val selections = mutableListOf<String>()
        try {
            // Warm the resource, but exercise the sheet's real asynchronous catalog publication.
            ReactionEmojiCatalog.categories
            view.setParentCompositionContext(recomposer)
            view.setContent {
                AppTheme {
                    val state = rememberBottomSheetState(initialDetent = BottomSheetDetent.FullyExpanded)
                    if (tabsOnly) {

                        androidx.compose.foundation.layout.Box {
                            // Android RenderThread ripple animators have no animation handle in Robolectric.
                            // Exercise real press interactions and real capsule clipping with a deterministic ink.
                            ReactionEmojiCategoryTabs(ReactionEmojiCatalog.categories, 0, PressedInk, {})
                        }
                    } else ReactionEmojiSheet(state, onDismiss = {}, onSelect = { selections += it })
                }
            }
            controller.get().setContentView(view)
            controller.windowFocusChanged(true)
            val picker = Picker(this, clock, selections, view, tabsOnly)
            repeat(40) {
                picker.frames(1)
                if (picker.grids().firstOrNull()?.childCount == 0) Thread.sleep(5)
            }
            if (!tabsOnly) assertTrue("Real picker must load the catalog", picker.grids().single().childCount > 0)
            picker.block()
        } finally {
            view.disposeComposition()
            recomposer.close()
            runner.cancel()
            controller.pause().stop().destroy()
        }
    }

    private class Picker(val scope: TestScope, val clock: BroadcastFrameClock, val selections: List<String>, val host: View, val tabsOnly: Boolean) {
        var time = 0L
        val root: View get() = if (tabsOnly) host else ShadowDialog.getLatestDialog().window!!.decorView
        fun frames(count: Int) {
            repeat(count) {
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(16))
                Snapshot.sendApplyNotifications()
                scope.testScheduler.advanceTimeBy(16)
                scope.runCurrent()
                time += 16_000_000L
                clock.sendFrame(time)
                scope.runCurrent()
                host.requestLayout()
                host.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY))
                host.layout(0, 0, 600, 900)
                views(host).filterIsInstance<ViewRootForTest>().forEach { it.measureAndLayoutForTest() }
                root.requestLayout()
                root.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, 600, 900)
                views(root).filterIsInstance<ViewRootForTest>().forEach { it.measureAndLayoutForTest() }
                val bitmap = android.graphics.Bitmap.createBitmap(600, 900, android.graphics.Bitmap.Config.ARGB_8888)
                root.draw(android.graphics.Canvas(bitmap))
                bitmap.recycle()
            }
        }
        fun grids(): List<ReactionEmojiRecyclerView> = views(root).filterIsInstance<ReactionEmojiRecyclerView>()
        fun swipe(right: Boolean, cancel: Boolean = false) {
            val grid = grids().single()
            val gridOrigin = IntArray(2).also(grid::getLocationInWindow)
            val rootOrigin = IntArray(2).also(root::getLocationInWindow)
            val x = gridOrigin[0] - rootOrigin[0] + grid.width * if (right) 0.2f else 0.8f
            val y = gridOrigin[1] - rootOrigin[1] + grid.height / 2f
            val dx = grid.width * if (right) 0.6f else -0.6f
            val down = SystemClock.uptimeMillis()
            fun touch(action: Int, step: Int) {
                val event = MotionEvent.obtain(down, down + step * 20L, action, x + dx * step / 12, y, 0)
                try { root.dispatchTouchEvent(event) } finally { event.recycle() }
            }
            touch(MotionEvent.ACTION_DOWN, 0)
            for (step in 1..12) touch(MotionEvent.ACTION_MOVE, step)
            touch(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, 12)
        }
        fun nodes(): List<SemanticsNode> {
            fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
            return views(root).filterIsInstance<ViewRootForTest>().flatMap { descend(it.semanticsOwner.unmergedRootSemanticsNode) }
        }
        fun tagged(tag: String): SemanticsNode = nodes().single {
            it.config.getOrElse(SemanticsProperties.TestTag) { "" } == tag
        }
        fun clickCategory(index: Int) {
            val label = reactionCategoryLabel(ReactionEmojiCatalog.categories[index].name)
            var node: SemanticsNode? = nodes().single { it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { text -> text.text == label } }
            while (node != null && !node.config.contains(SemanticsActions.OnClick)) node = node.parent
            assertNotNull("Category must be clickable", node)
            assertTrue(node!!.config[SemanticsActions.OnClick].action!!.invoke())
        }
        private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    }

    @Suppress("DEPRECATION", "DEPRECATION_ERROR", "OVERRIDE_DEPRECATION")
    private object PressedInk : Indication {
        @Composable override fun rememberUpdatedInstance(interactionSource: InteractionSource): IndicationInstance {
            val pressed by interactionSource.collectIsPressedAsState()
            return remember(pressed) { object : IndicationInstance {
                override fun ContentDrawScope.drawIndication() {
                    drawContent()
                    if (pressed) drawRect(androidx.compose.ui.graphics.Color.Red)
                }
            } }
        }
    }
}
