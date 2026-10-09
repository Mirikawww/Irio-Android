package com.irio

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class MessageRecyclingTest {
    @Test
    fun rebindingHidesTheOldLongBubbleAndReactionUntilTheNewMessageIsLaidOut() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val list = MessageRecyclerView(controller.get())
        list.updateCallbacks(AndroidMessageCallbacks(
            markdownScope = this,
            onOpenProcess = { _, _ -> }, onOpenAttachment = {}, onCopy = {}, onQuote = {},
            onAddReaction = {}, onRemoveReaction = {}, reactionAnimations = ReactionBounceTracker(),
            onRetry = {}, canRetry = { false }, onPrevVersion = { null }, onNextVersion = { null },
            isEditing = { false }, onEdit = { null }, onPrevEditVersion = { null }, onNextEditVersion = { null },
            versionInfo = { VersionInfo(1, 1) }, editVersionInfo = { VersionInfo(1, 1) }, newUserMessageId = null,
        ))
        val holder = list.adapter!!.createViewHolder(list, 0)
        val item = holder.itemView as ComposeView
        val host = ComposeView(controller.get())
        fun bind(message: ChatMessage) {
            holder.javaClass.getDeclaredMethod("bind", ChatMessage::class.java).apply { isAccessible = true }
                .invoke(holder, message)
        }
        fun readyId(): Long = holder.javaClass.getDeclaredMethod("getReadyMessageId").apply { isAccessible = true }
            .invoke(holder) as Long
        fun measure() {
            host.requestLayout()
            host.measure(
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.AT_MOST),
            )
            host.layout(0, 0, host.measuredWidth, host.measuredHeight)
        }
        fun texts(): List<String> {
            val root = (item.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode
            fun visit(node: SemanticsNode): List<String> =
                (node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text }) + node.children.flatMap(::visit)
            return visit(root)
        }
        try {
            bind(ChatMessage(1L, "previous message is much longer than the new one", true, modelReaction = "❤️"))
            host.setParentCompositionContext(recomposer)
            host.setContent {
                AppTheme {
                    androidx.compose.ui.viewinterop.AndroidView(factory = { item })
                }
            }
            controller.get().setContentView(host)
            runCurrent()
            clock.sendFrame(0L)
            runCurrent()
            measure()
            assertEquals(View.VISIBLE, item.visibility)
            assertTrue(texts().contains("❤️"))
            val oldTexts = texts()
            bind(ChatMessage(2L, "new", true))
            // RecyclerView can draw the rebound holder before Compose gets its next frame.
            assertEquals(oldTexts, texts())
            assertEquals("Old pixels must be hidden immediately, not on the next Compose frame", View.INVISIBLE, item.visibility)
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            runCurrent()
            clock.sendFrame(16_000_000L)
            runCurrent()
            measure()
            assertEquals(View.VISIBLE, item.visibility)
            assertEquals(listOf("new"), texts())
            assertEquals(2L, readyId())

            val height = item.height
            bind(ChatMessage(3L, "two", true))
            assertEquals(View.INVISIBLE, item.visibility)
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            runCurrent()
            clock.sendFrame(32_000_000L)
            runCurrent()
            measure()
            assertEquals("Equal-sized recycled rows must also become ready", height, item.height)
            assertEquals(View.VISIBLE, item.visibility)
            assertEquals(3L, readyId())
            assertEquals(listOf("two"), texts())

            bind(ChatMessage(3L, "updated", true))
            assertEquals("Updates of the same message must not blank the row", View.VISIBLE, item.visibility)
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            runCurrent()
            clock.sendFrame(48_000_000L)
            runCurrent()
            measure()
            assertEquals(listOf("updated"), texts())

            bind(ChatMessage(4L, "intermediate", true, modelReaction = "👍"))
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            runCurrent()
            clock.sendFrame(64_000_000L)
            runCurrent()
            bind(ChatMessage(5L, "latest", true))
            measure()
            assertEquals("An outdated layout callback must not reveal the intermediate message", View.INVISIBLE, item.visibility)
            assertEquals(Long.MIN_VALUE, readyId())
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            runCurrent()
            clock.sendFrame(80_000_000L)
            runCurrent()
            measure()
            assertEquals(View.VISIBLE, item.visibility)
            assertEquals(5L, readyId())
            assertEquals(listOf("latest"), texts())

            // A retained row can get only a draw invalidation when the replacement has
            // the same constraints. Do not force a layout: production must recover via
            // the normal draw traversal, including Compose's deferred layout callbacks.
            bind(ChatMessage(6L, "latest", true))
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            runCurrent()
            clock.sendFrame(96_000_000L)
            runCurrent()
            val bitmap = android.graphics.Bitmap.createBitmap(600, 800, android.graphics.Bitmap.Config.ARGB_8888)
            try {
                repeat(3) { host.draw(android.graphics.Canvas(bitmap)) }
                assertEquals("Draw-only rebinds must not leave a measured row permanently blank", 6L, readyId())
                assertEquals(View.VISIBLE, item.visibility)
            } finally {
                bitmap.recycle()
            }

            // The reveal must not depend on a layout callback or on being drawn. An INVISIBLE
            // child is skipped by RecyclerView's draw traversal, so Compose never measures it
            // and the callback can never fire — regressing this made rows vanish until restart.
            bind(ChatMessage(7L, "latest", true))
            assertEquals("Rebinding must still hide the previous row", View.INVISIBLE, item.visibility)
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            runCurrent()
            clock.sendFrame(112_000_000L)
            runCurrent()
            assertEquals(
                "A composition apply alone must reveal the row, with no draw and no measure",
                View.VISIBLE,
                item.visibility,
            )
            assertEquals(
                "Measurement still waits for a real layout traversal; the reveal must not fake it",
                Long.MIN_VALUE,
                readyId(),
            )

            holder.javaClass.getDeclaredMethod("clear").apply { isAccessible = true }.invoke(holder)
            assertEquals("Recycling must hide the retained composition immediately", View.INVISIBLE, item.visibility)
            assertEquals(Long.MIN_VALUE, readyId())
        } finally {
            item.disposeComposition()
            host.disposeComposition()
            recomposer.close()
            runner.cancel()
            controller.pause().stop().destroy()
        }
    }
}
