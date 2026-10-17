package com.irio

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ComposeRecyclerBindingTest {
    private data class Row(val id: Int, val height: Int)

    @Test
    fun reboundHolderMeasuresTheNewItemBeforeTheNextComposeFrame() = runTest {
        withList { list, frame ->
            val adapter = list.adapter as RecyclerView.Adapter<RecyclerView.ViewHolder>
            val holder = adapter.createViewHolder(list, 0)
            list.addView(holder.itemView)
            adapter.bindViewHolder(holder, 0)
            frame()
            measure(holder.itemView)
            assertEquals(160, holder.itemView.measuredHeight)
            assertEquals(listOf("row-0 remembered-0"), texts(holder.itemView))

            adapter.bindViewHolder(holder, 1)
            // RecyclerView binds and measures synchronously during a fling. There is no
            // guarantee that Compose receives another frame before it places this row.
            measure(holder.itemView)
            assertEquals("A recycled row must not reserve the previous tool's height", 64, holder.itemView.measuredHeight)
            assertEquals("The first traversal must contain the newly bound tool, not old pixels", listOf("row-1 remembered-1"), texts(holder.itemView))

            // A fast fling can reassign a holder many times without a Compose frame.
            for (position in listOf(58, 23, 44, 5, 36, 17, 52, 1, 0)) {
                adapter.bindViewHolder(holder, position)
                measure(holder.itemView)
                assertEquals("Wrong height immediately after binding position $position", if (position % 2 == 0) 160 else 64, holder.itemView.measuredHeight)
                assertEquals(listOf("row-$position remembered-$position"), texts(holder.itemView))
                assertEquals(View.VISIBLE, holder.itemView.visibility)
            }

            // Pool recycling and detached prefetch must not retain an empty composition.
            adapter.onViewRecycled(holder)
            list.removeView(holder.itemView)
            adapter.bindViewHolder(holder, 1)
            list.addView(holder.itemView)
            measure(holder.itemView)
            assertEquals(64, holder.itemView.measuredHeight)
            assertEquals(listOf("row-1 remembered-1"), texts(holder.itemView))
            adapter.bindViewHolder(holder, 1)
            measure(holder.itemView)
            assertEquals("Rebinding the same item must remain visible", View.VISIBLE, holder.itemView.visibility)
        }
    }

    @Test
    fun expandedToolRecycledIntoCollapsedToolHasNoStaleEditorOrHeight() = runTest {
        withList(toolRows = true) { list, frame ->
            // Exercise real RecyclerView attachment and recycling. Manually adding an
            // adapter-created ViewHolder as a child bypasses RecyclerView's holder map
            // and crashes its next layout pass when the Compose row requests layout.
            list.scrollToPosition(0)
            repeat(60) { frame() }
            val expandedHolder = list.findViewHolderForAdapterPosition(0)!!
            val expandedHeight = expandedHolder.itemView.height
            assertTrue(texts(expandedHolder.itemView).contains(tr("工具描述")))

            for (position in listOf(1, 2, 31, 44, 5)) {
                list.scrollToPosition(position)
                list.requestLayout()
                list.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY))
                list.layout(0, 0, 360, 480)
                repeat(4) { frame() }
                val holder = list.findViewHolderForAdapterPosition(position)!!
                val firstHeight = holder.itemView.height
                val firstTexts = texts(holder.itemView)
                assertEquals("Tool editor visibility must match the newly bound identity", position % 2 == 0, firstTexts.contains(tr("工具描述")))
                assertTrue(firstTexts.contains("tool_$position"))
                if (position % 2 != 0) assertTrue("Collapsed tools must not retain an expanded editor's height", firstHeight < expandedHeight)
                repeat(3) { frame() }
                assertEquals("A later Compose frame must not shift a newly placed tool", firstHeight, holder.itemView.height)
            }
        }
    }

    @Test
    fun rapidToolScrollsKeepVisibleRowsBoundAndPositionsStableWithoutExtraFrames() = runTest {
        withList(toolRows = true) { list, frame ->
            for (position in listOf(20, 43, 8, 54, 1, 36, 0)) {
                list.scrollToPosition(position)
                list.requestLayout()
                list.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY))
                list.layout(0, 0, 360, 480)
                val before = (0 until list.childCount).map { index ->
                    val child = list.getChildAt(index)
                    val boundPosition = list.getChildAdapterPosition(child)
                    assertTrue("Every visible row must show its bound tool before another Compose frame", texts(child).contains("tool_$boundPosition"))
                    assertTrue("Visible tools must not collapse into zero-height rows", child.height >= 64)
                    assertEquals(View.VISIBLE, child.visibility)
                    Triple(boundPosition, child.top, child.height)
                }
                assertTrue("Scrolling must leave visible tools", before.isNotEmpty())
                before.zipWithNext().forEach { (previous, next) ->
                    assertEquals("Tools must remain contiguous without overlap or blank gaps", previous.second + previous.third, next.second)
                }
                frame()
                val after = (0 until list.childCount).map { index ->
                    val child = list.getChildAt(index)
                    Triple(list.getChildAdapterPosition(child), child.top, child.height)
                }
                assertEquals("A deferred composition must not relocate rows after a fast scroll", before, after)
            }
        }
    }

    private fun TestScope.withList(toolRows: Boolean = false, check: (RecyclerView, () -> Unit) -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val host = ComposeView(controller.get())
        var time = 0L
        try {
            host.setParentCompositionContext(recomposer)
            controller.get().setContentView(host)
            host.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme {
                        AndroidComposeRecyclerList(
                            items = List(60) { Row(it, if (it % 2 == 0) 160 else 64) },
                            key = { it.id },
                        ) { row ->
                            if (toolRows) {
                                val entry = ToolSettingsEntry("tool_${row.id}", "Tool ${row.id}", "Description ${row.id}")
                                val draft = remember { androidx.compose.foundation.text.input.TextFieldState(entry.defaultDescription) }
                                ToolSettingsRow(entry, true, row.id % 2 == 0, draft, {}, {}, {}, {}, {})
                            } else {
                                val rememberedId = remember { row.id }
                                Box(Modifier.fillMaxWidth().height(row.height.dp)) {
                                    Text("row-${row.id} remembered-$rememberedId")
                                }
                            }
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
                host.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY))
                host.layout(0, 0, 360, 480)
            }
            repeat(5) { frame() }
            fun recycler(view: View): RecyclerView? = if (view is RecyclerView) view else
                if (view is ViewGroup) (0 until view.childCount).firstNotNullOfOrNull { recycler(view.getChildAt(it)) } else null
            check(recycler(host)!!, ::frame)
        } finally {
            host.disposeComposition()
            recomposer.close()
            runner.cancel()
            controller.pause().stop().destroy()
        }
    }

    private fun measure(view: View) {
        view.requestLayout()
        view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun texts(view: View): List<String> {
        fun descend(node: SemanticsNode): List<String> = node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text } + node.children.flatMap(::descend)
        if (view is ViewRootForTest) return descend(view.semanticsOwner.unmergedRootSemanticsNode)
        return if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else emptyList()
    }
}
