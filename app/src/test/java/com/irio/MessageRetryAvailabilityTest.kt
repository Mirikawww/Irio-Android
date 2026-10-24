package com.irio

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class MessageRetryAvailabilityTest {
    @Test fun busyConversationRejectsEveryCompletedReplyIncludingOldErrors() {
        val replies = listOf(
            ChatMessage(1, "old reply", fromUser = false),
            ChatMessage(2, "error", fromUser = false, isError = true),
            ChatMessage(3, "", fromUser = false),
        )
        replies.forEach {
            assertTrue(canRetryMessage(it, conversationBusy = false))
            assertFalse(canRetryMessage(it, conversationBusy = true))
        }
        val reply = replies.first()
        listOf(reply.copy(fromUser = true), reply.copy(isPending = true), reply.copy(isStreaming = true),
            reply.copy(summaryState = SummaryState.SUCCESS)).forEach {
            assertFalse(canRetryMessage(it, conversationBusy = false))
        }
    }

    @Test fun callbackUpdatesDisableAllExistingRetryButtonsWithoutHidingThemAndRestoreAfterCompletion() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val list = MessageRecyclerView(controller.get())
        val retried = mutableListOf<Long>()
        val callbacks = AndroidMessageCallbacks(
            markdownScope = this,
            uploadTracker = AttachmentUploadTracker(), uploadConversationId = "test",
            onOpenProcess = { _, _ -> }, onOpenAttachment = {}, onCopy = {}, onQuote = {},
            onAddReaction = {}, onRemoveReaction = {}, reactionAnimations = ReactionBounceTracker(),
            onRetry = { retried += it.id }, canRetry = { canRetryMessage(it, false) },
            onPrevVersion = { null }, onNextVersion = { null }, isEditing = { false }, onEdit = { null },
            onPrevEditVersion = { null }, onNextEditVersion = { null },
            versionInfo = { VersionInfo(1, 1) }, editVersionInfo = { VersionInfo(1, 1) }, newUserMessageId = null,
        )
        list.updateCallbacks(callbacks)
        val holders = List(2) { list.adapter!!.createViewHolder(list, 0) }
        val items = holders.map { it.itemView as ComposeView }
        val host = ComposeView(controller.get())
        fun bind(index: Int, id: Long) {
            val holder = holders[index]
            holder.javaClass.getDeclaredMethod("bind", ChatMessage::class.java).apply { isAccessible = true }
                .invoke(holder, ChatMessage(id, "fixture error", fromUser = false, isError = true))
        }
        var frameTime = 0L
        fun frames() {
            repeat(6) {
                shadowOf(Looper.getMainLooper()).idle()
                Snapshot.sendApplyNotifications()
                runCurrent()
                frameTime += 16_000_000L
                clock.sendFrame(frameTime)
                runCurrent()
                host.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.AT_MOST))
                host.layout(0, 0, host.measuredWidth, host.measuredHeight)
            }
        }
        fun retry(index: Int): SemanticsNode {
            val root = (items[index].getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode
            fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
            return descend(root).single {
                it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.contains(tr("重试"))
            }
        }
        fun tap(index: Int) {
            val center = retry(index).boundsInRoot.center
            val now = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(now, now + if (action == MotionEvent.ACTION_UP) 16 else 0,
                    action, center.x, center.y, 0)
                try { items[index].dispatchTouchEvent(event) } finally { event.recycle() }
            }
            shadowOf(Looper.getMainLooper()).idle()
        }
        try {
            bind(0, 1)
            bind(1, 2)
            host.setParentCompositionContext(recomposer)
            host.setContent {
                AppTheme {
                    androidx.compose.ui.viewinterop.AndroidView(factory = { context ->
                        LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            items.forEach { addView(it) }
                        }
                    })
                }
            }
            controller.get().setContentView(host)
            frames()
            items.indices.forEach { assertFalse(retry(it).config.contains(SemanticsProperties.Disabled)) }
            tap(0)
            assertEquals(listOf(1L), retried)
            retried.clear()

            // Only callbacks change: old message holders must still recompose immediately.
            list.updateCallbacks(callbacks.copy(retryEnabled = false))
            frames()
            items.indices.forEach {
                assertTrue("Every historical reply must expose disabled retry semantics", retry(it).config.contains(SemanticsProperties.Disabled))
                tap(it)
            }
            assertTrue("Disabled retry icons must ignore real pointer taps", retried.isEmpty())

            bind(0, 3)
            frames()
            assertTrue("Recycled rows must inherit the busy state", retry(0).config.contains(SemanticsProperties.Disabled))
            tap(0)
            assertTrue(retried.isEmpty())

            list.updateCallbacks(callbacks)
            frames()
            items.indices.forEach {
                assertFalse(retry(it).config.contains(SemanticsProperties.Disabled))
                tap(it)
            }
            assertEquals(listOf(3L, 2L), retried)
        } finally {
            items.forEach { it.disposeComposition() }
            host.disposeComposition()
            recomposer.close()
            runner.cancel()
            controller.pause().stop().destroy()
        }
    }
}
