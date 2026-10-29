package com.irio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.time.Duration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MessageSendScrollTest {
    @Test fun growingTailAndChangingComposerInsetKeepTheOriginalDeadline() = runTest {
        withTranscript {
            var lastMove = 0L
            var firstMove = 0L
            var changed = false
            list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) return
                    if (firstMove == 0L) firstMove = SystemClock.uptimeMillis()
                    lastMove = SystemClock.uptimeMillis()
                }
            })
            controller.revealTail()
            repeat(85) {
                if (!changed && firstMove != 0L && SystemClock.uptimeMillis() - firstMove >= 80) {
                    changed = true
                    val tail = messages.last().copy(text = (1..14).joinToString("\n") { "Growing line $it" })
                    list.updateMessages(messages.dropLast(1) + tail) { controller.flushScrollToBottom() }
                    list.setTranscriptPadding(48, 180)
                    controller.requestScrollToBottom()
                }
                frame()
            }
            assertTrue("Exercise a real content and inset change during motion", changed)
            assertEquals("Remeasure the moving target rather than stopping short", true, controller.isPinnedToBottom(1))
            assertTrue("Retargeting must not restart the clock", lastMove - firstMove <= SendScrollDurationMillis + 32)
            assertEquals("Release temporary offscreen layout", 0, list.sendPrefetchPixels)
        }
    }

    @Test fun sendingDuringTheOpenPinWindowDoesNotJumpOrHideTheTranscript() = runTest {
        withTranscript {
            controller.openAtBottom()
            repeat(35) { frame() }
            assertEquals(true, controller.isPinnedToBottom(1))
            val positions = mutableListOf<Pair<Long, Int>>()
            list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy != 0) positions += SystemClock.uptimeMillis() to dy
                }
            })
            val appended = messages + listOf(
                ChatMessage(25L, (1..18).joinToString("\n") { "New message line $it" }, true),
                ChatMessage(26L, "", false, isPending = true),
            )
            list.updateMessages(appended) { controller.flushScrollToBottom() }
            controller.revealTail()
            repeat(65) {
                frame()
                assertEquals("Sending must not hide or fade the transcript", 1f, list.alpha, 0f)
            }
            assertEquals(true, controller.isPinnedToBottom(1))
            assertTrue("The appended bubble must animate", positions.size >= 8)
            assertTrue("An old opening alignment must not pull the list back", positions.all { it.second > 0 })
            val distance = positions.sumOf { it.second }
            assertTrue("No pre-animation jump", positions.first().second < distance / 20)
            assertTrue("No completion jump", positions.last().second < distance / 20)
            assertTrue(positions.last().first - positions.first().first <= SendScrollDurationMillis + 32)
        }
    }

    @Test fun distantVariableHeightTailArrivesWithoutAPreparatoryJump() = runTest {
        withTranscript {
            val steps = mutableListOf<Pair<Long, Int>>()
            list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy != 0) steps += SystemClock.uptimeMillis() to dy
                }
            })
            controller.revealTail()
            repeat(85) { frame() }
            assertEquals("The offscreen tail must be reached, not abandoned after tiny probes", true,
                controller.isPinnedToBottom(1))
            assertTrue("Sending must actually animate", steps.size >= 8)
            assertTrue("Sending must not move backwards", steps.all { it.second > 0 })
            val duration = steps.last().first - steps.first().first
            assertTrue("Visible motion must finish in one fixed duration, actual=$duration", duration <= SendScrollDurationMillis + 32)
            val distance = steps.sumOf { it.second }
            assertTrue("No jump before accelerating", steps.first().second < distance / 20)
            assertTrue("No final jump to repair a bad target", steps.last().second < distance / 20)
            val start = steps.first().first
            val early = steps.filter { it.first - start < 64 }.sumOf { it.second }
            val middle = steps.filter { it.first - start in 112..176 }.sumOf { it.second }
            val late = steps.filter { it.first - start > 224 }.sumOf { it.second }
            assertTrue("Actual list movement must accelerate then decelerate", early < middle && late < middle)
            assertEquals(1f, list.alpha, 0f)
            assertEquals(View.VISIBLE, list.visibility)
        }
    }

    private fun TestScope.withTranscript(check: Harness.() -> Unit) {
        org.robolectric.shadows.ShadowChoreographer.setPaused(true)
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val host = ComposeView(activity.get())
        val list = MessageRecyclerView(activity.get())
        val controller = MessageRecyclerController()
        val bitmap = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
        list.updateCallbacks(AndroidMessageCallbacks(
            markdownScope = this, uploadTracker = AttachmentUploadTracker(), uploadConversationId = "scroll-test",
            onOpenProcess = { _, _ -> }, onOpenAttachment = {}, onCopy = {}, onQuote = {},
            onAddReaction = {}, onRemoveReaction = {}, reactionAnimations = ReactionBounceTracker(),
            onRetry = {}, canRetry = { false }, onPrevVersion = { null }, onNextVersion = { null },
            isEditing = { false }, onEdit = { null }, onPrevEditVersion = { null }, onNextEditVersion = { null },
            versionInfo = { VersionInfo(1, 1) }, editVersionInfo = { VersionInfo(1, 1) }, newUserMessageId = null,
        ))
        list.setTranscriptPadding(48, 120)
        controller.attach(list)
        val messages = (1L..24L).map { id ->
            ChatMessage(id, (1..(id % 5 + 1)).joinToString("\n") { "Message $id line $it" }, true)
        }
        list.updateMessages(messages)
        try {
            host.setParentCompositionContext(recomposer)
            host.setContent { AppTheme { AndroidView(factory = { list }) } }
            activity.get().setContentView(host)
            val harness = Harness(this, host, list, controller, clock, Canvas(bitmap), messages)
            repeat(25) { harness.frame() }
            assertEquals(0, (list.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition())
            assertEquals(false, controller.isPinnedToBottom(1))
            harness.check()
        } finally {
            host.disposeComposition()
            recomposer.close()
            runner.cancel()
            bitmap.recycle()
            activity.pause().stop().destroy()
        }
    }

    private class Harness(
        val scope: TestScope,
        val host: ComposeView,
        val list: MessageRecyclerView,
        val controller: MessageRecyclerController,
        val clock: BroadcastFrameClock,
        val canvas: Canvas,
        val messages: List<ChatMessage>,
    ) {
        private var time = 0L
        fun frame() {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            Snapshot.sendApplyNotifications()
            scope.runCurrent()
            time += 16_000_000L
            clock.sendFrame(time)
            scope.runCurrent()
            host.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            host.layout(0, 0, 600, 800)
            host.draw(canvas)
        }
    }
}
