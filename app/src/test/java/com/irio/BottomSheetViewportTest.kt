package com.irio

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density

import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
class BottomSheetViewportTest {
    @Test fun searchListReachesTheSheetBottomAndKeepsItsTrailingInsetScrollable() = runTest {
        val body = JSONObject().put("results", JSONArray().apply {
            repeat(30) { put(JSONObject().put("url", "https://example.com/$it")
                .put("title", "Result $it").put("content", "Result description")) }
        }).toString()
        withSheet(content = {
            val state = rememberBottomSheetState(initialDetent = BottomSheetDetent.FullyExpanded)
            ProcessEventSheet(ProcessEvent(1L, ProcessEventKind.SEARCH, content = body), state, {})
        }) { root, frames ->
            val list = descendants(root).filterIsInstance<RecyclerView>().single()
            fun assertViewport(nav: Int) {
                frames()
                val location = IntArray(2).also(list::getLocationInWindow)
                val rootLocation = IntArray(2).also(root::getLocationInWindow)
                assertEquals("The search viewport must not leave a fixed blank footer", root.height,
                    location[1] - rootLocation[1] + list.height)
                assertFalse("Rows must be able to draw through the trailing padding while scrolling", list.clipToPadding)
                assertTrue("Navigation clearance belongs to scrollable content", list.paddingBottom >= nav + 8)
                list.scrollToPosition(list.adapter!!.itemCount - 1)
                repeat(8) { frames() }
                list.scrollBy(0, 100_000)
                frames()
                val tail = (list.layoutManager as LinearLayoutManager).findViewByPosition(list.adapter!!.itemCount - 1)!!
                assertTrue("The final result must remain above navigation at the end", tail.bottom <= list.height - nav)
            }
            assertViewport(0)
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 24))
                .setVisible(WindowInsetsCompat.Type.navigationBars(), true).build()
            ViewCompat.dispatchApplyWindowInsets(root, insets)
            assertViewport(24)
        }
    }

    @Test fun emojiGridViewportReachesTheSheetBottom() = runTest {
        withSheet(content = {
            val state = rememberBottomSheetState(initialDetent = BottomSheetDetent.FullyExpanded)
            ReactionEmojiSheet(state, {}, {})
        }) { root, frames ->
            repeat(12) { frames() }
            val grid = descendants(root).filterIsInstance<ReactionEmojiRecyclerView>().single()
            val location = IntArray(2).also(grid::getLocationInWindow)
            val rootLocation = IntArray(2).also(root::getLocationInWindow)
            assertEquals("The emoji viewport must not stop before the sheet bottom", root.height,
                location[1] - rootLocation[1] + grid.height)
            assertFalse("Emoji content must draw in the scrolling trailing inset", grid.clipToPadding)
            ViewCompat.dispatchApplyWindowInsets(root, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 24))
                .setVisible(WindowInsetsCompat.Type.navigationBars(), true).build())
            frames()
            assertEquals("Emoji navigation clearance must be inside the grid", 40, grid.paddingBottom)
            val updatedLocation = IntArray(2).also(grid::getLocationInWindow)
            assertEquals(root.height, updatedLocation[1] - rootLocation[1] + grid.height)
        }
    }

    @Test fun longThinkingAndTerminalViewportsReachTheBottom() = runTest {
        for (terminal in listOf(false, true)) {
            withSheet(content = {
                val state = rememberBottomSheetState(initialDetent = BottomSheetDetent.FullyExpanded)
                val event = ProcessEvent(1L, if (terminal) ProcessEventKind.TOOL else ProcessEventKind.THINKING,
                    content = (1..60).joinToString("\n\n") { "A long output paragraph $it" },
                    toolName = if (terminal) LinuxShellTool.NAME else "")
                ProcessEventSheet(event, state, {})
            }) { root, frames ->
                frames()
                fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
                val scroll = descendants(root).filterIsInstance<ViewRootForTest>().flatMap {
                    descend(it.semanticsOwner.unmergedRootSemanticsNode)
                }.single { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
                assertEquals("terminal=$terminal must not have a fixed blank footer", root.height.toFloat(),
                    scroll.boundsInRoot.bottom, 1f)
            }
        }
    }

    private fun kotlinx.coroutines.test.TestScope.withSheet(
        content: @Composable () -> Unit,
        check: (View, () -> Unit) -> Unit,
    ) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val host = ComposeView(activity.get())
        var time = 0L
        try {
            host.setParentCompositionContext(recomposer)
            activity.get().setContentView(host)
            host.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) { AppTheme { content() } }
            }
            repeat(3) { runCurrent(); time += 16_000_000L; clock.sendFrame(time); runCurrent() }
            val root = ShadowDialog.getLatestDialog().window!!.decorView
            fun frames() = repeat(4) {
                Snapshot.sendApplyNotifications()
                runCurrent(); time += 16_000_000L; clock.sendFrame(time); runCurrent()
                shadowOf(Looper.getMainLooper()).idle()
                root.requestLayout()
                root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, 400, 800)
            }
            repeat(8) { frames() }
            check(root, ::frames)
        } finally {
            host.disposeComposition(); recomposer.close(); runner.cancel()
            activity.pause().stop().destroy()
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
