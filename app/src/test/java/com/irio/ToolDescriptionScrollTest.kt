package com.irio

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.TextRange
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ToolDescriptionScrollTest {
    @Test
    fun descriptionOwnsDragsWhileHeaderStillScrollsThePageAfterUpAndCancel() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val host = ComposeView(controller.get())
        val editorScroll = ScrollState(0)
        var time = 0L
        try {
            host.setParentCompositionContext(recomposer)
            controller.get().setContentView(host, ViewGroup.LayoutParams(360, 640))
            host.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme {
                        AndroidComposeRecyclerList((0 until 20).toList(), { it }, Modifier.fillMaxSize()) { id ->
                            val draft = remember { TextFieldState((1..60).joinToString("\n") { "Description line $it" }, TextRange.Zero) }
                            ToolSettingsRow(ToolSettingsEntry("tool_$id", "Tool $id", ""), true, id == 0, draft, {}, {}, {}, {}, {},
                                descriptionScrollState = if (id == 0) editorScroll else rememberScrollState())
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
                shadowOf(Looper.getMainLooper()).idle()
                host.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
                host.layout(0, 0, 360, 640)
            }
            fun views(view: View): List<View> = listOf(view) +
                if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
            fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
            repeat(80) { frame() }
            val list = views(host).filterIsInstance<RecyclerView>().single()
            val row = list.findViewHolderForAdapterPosition(0)!!.itemView as ComposeView
            val semantics = views(row).filterIsInstance<ViewRootForTest>().single().semanticsOwner
            fun scrollNode() = nodes(semantics.unmergedRootSemanticsNode).first {
                it.config.contains(SemanticsProperties.EditableText)
            }
            fun offset() = editorScroll.value
            assertTrue("Fixture must overflow the twelve-line editor", editorScroll.maxValue > 200)
            var pageScroll = 0
            list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) { pageScroll += dy }
            })
            fun gesture(x: Float, y: Float, distance: Float, end: Int = MotionEvent.ACTION_UP) {
                val down = SystemClock.uptimeMillis()
                fun touch(action: Int, step: Int) {
                    val event = MotionEvent.obtain(down, down + step * 20L, action, x, y + distance * step.coerceAtMost(12) / 12f, 0)
                    try { host.dispatchTouchEvent(event) } finally { event.recycle() }
                    frame()
                }
                touch(MotionEvent.ACTION_DOWN, 0)
                (1..12).forEach { touch(MotionEvent.ACTION_MOVE, it) }
                touch(end, 13)
            }
            val bounds = scrollNode().boundsInRoot
            val rowLocation = IntArray(2).also { row.getLocationInWindow(it) }
            val hostLocation = IntArray(2).also { host.getLocationInWindow(it) }
            val x = bounds.center.x + rowLocation[0] - hostLocation[0]
            val y = bounds.center.y + rowLocation[1] - hostLocation[1]
            gesture(x, y + 60, -100f)
            assertTrue("Dragging inside the description must scroll its text", offset() > 0f)
            assertEquals("RecyclerView must not steal the description drag", 0, pageScroll)
            val afterUp = offset()
            gesture(x, y - 60, 80f, MotionEvent.ACTION_CANCEL)
            assertTrue("Reverse dragging must reveal earlier description lines", offset() < afterUp)
            assertEquals("Reverse dragging must leave the page stationary", 0, pageScroll)
            gesture(150f, 55f, -45f)
            assertTrue("Header dragging must scroll the page after editor UP/CANCEL", pageScroll > 0)
        } finally {
            host.disposeComposition()
            recomposer.close()
            runner.cancel()
            controller.pause().stop().destroy()
        }
    }
}
