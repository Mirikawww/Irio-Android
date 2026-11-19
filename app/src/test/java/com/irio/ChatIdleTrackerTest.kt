package com.irio

import android.content.Context
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import java.time.Duration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ChatIdleTrackerTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun resetPreferences() {
        context.getSharedPreferences("chat_idle", Context.MODE_PRIVATE).edit().clear().commit()
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 1)
    }

    @Test
    fun foregroundTimeoutFiresOnceUntilAnotherInteraction() {
        val tracker = ChatIdleTracker(context)
        advance(ChatIdleTimeoutMs - 1)
        assertFalse(tracker.resetPending)
        advance(1)
        assertTrue(tracker.resetPending)
        tracker.consumeReset()
        tracker.checkTimeout()
        assertFalse(tracker.resetPending)

        tracker.recordInteraction()
        advance(ChatIdleTimeoutMs)
        assertTrue(tracker.resetPending)
    }

    @Test
    fun userInteractionMovesDeadlineAndCheckpointDoesNot() {
        val tracker = ChatIdleTracker(context)
        advance(45 * 60_000)
        tracker.recordInteraction()
        advance(35 * 60_000)
        tracker.checkpoint()
        // Time before/after going to the background counts from the same interaction.
        advance(ChatIdleTimeoutMs - 35 * 60_000 - 1)
        tracker.checkTimeout()
        assertFalse(tracker.resetPending)
        advance(1)
        assertTrue(tracker.resetPending)
    }

    @Test
    fun freshProcessRecoversTheRemainingTimeFromLastInteraction() {
        val previousProcess = ChatIdleTracker(context)
        advance(30 * 60_000)
        previousProcess.recordInteraction()
        advance(20 * 60_000)
        previousProcess.checkpoint()
        advance(99 * 60_000)

        val restored = ChatIdleTracker(context)
        assertFalse(restored.resetPending)
        advance(60_000)
        assertTrue(restored.resetPending)
        assertTrue(ChatIdleTracker(context).resetPending)
    }

    @Test
    fun explicitConversationOpenTakesPriorityOverTimeout() {
        val tracker = ChatIdleTracker(context)
        advance(ChatIdleTimeoutMs)
        assertTrue(tracker.resetPending)
        tracker.openExplicitConversation()
        assertFalse(tracker.resetPending)
        assertFalse(ChatIdleTracker(context).isIdle)
    }

    @Test
    fun changedBootUsesWallClockAge() {
        val priorProcess = ChatIdleTracker(context)
        val prefs = context.getSharedPreferences("chat_idle", Context.MODE_PRIVATE)
        prefs.edit().putInt("boot_count", 0)
            .putLong("wall_time", System.currentTimeMillis() - ChatIdleTimeoutMs).commit()
        assertFalse(priorProcess.isIdle)
        assertTrue(ChatIdleTracker(context).resetPending)
    }

    @Test
    fun resumeReschedulesUsingTimeElapsedDuringDeepSleep() {
        val tracker = ChatIdleTracker(context)
        ShadowSystemClock.simulateDeepSleep(Duration.ofHours(1))
        tracker.checkTimeout()
        assertFalse(tracker.resetPending)
        advance(60 * 60_000)
        assertTrue(tracker.resetPending)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun idleResetStartsOneBlankChatAndKeepsThePreviousDraft() = runTest {
        val tracker = ChatIdleTracker(context)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(activity.get())
        try {
            lateinit var state: ChatScreenState
            view.setParentCompositionContext(recomposer)
            activity.get().setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalChatIdleTracker provides tracker) {
                    AppTheme {
                        state = rememberChatScreenState()
                        RestoreChatConversations(state, restoreLastOpened = false)
                    }
                }
            }
            var frameTime = 0L
            fun settle() = repeat(3) {
                shadowOf(Looper.getMainLooper()).idle()
                Snapshot.sendApplyNotifications()
                runCurrent()
                frameTime += 16_000_000L
                clock.sendFrame(frameTime)
                runCurrent()
            }
            settle()
            val previousId = state.currentConversation.id
            state.messages = listOf(ChatMessage(1, "Previous message", true))
            state.currentConversation = state.currentConversation.copy(messages = state.messages)
            state.message.edit { replace(0, length, "Unsent draft") }
            settle()

            ShadowSystemClock.simulateDeepSleep(Duration.ofHours(2))
            tracker.checkTimeout()
            settle()
            val blankId = state.currentConversation.id
            assertNotEquals(previousId, blankId)
            assertTrue(state.messages.isEmpty())
            assertTrue(state.message.text.isEmpty())
            assertEquals("Unsent draft", state.composerDrafts[previousId]?.text)
            tracker.checkTimeout()
            settle()
            assertEquals(blankId, state.currentConversation.id)
        } finally {
            view.disposeComposition()
            recomposer.close()
            runner.cancel()
            activity.pause().stop().destroy()
        }
    }

    private fun advance(millis: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
    }
}
