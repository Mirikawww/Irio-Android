package com.irio

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ReactionBounceTest {
    @Test
    fun anAddedReactionScalesOnceAndRemainsStillAfterward() = runTest {
        val tracker = ReactionBounceTracker()
        tracker.record(1L, "❤️")
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodesApplier(), recomposer)
        lateinit var bounce: State<Float>
        try {
            composition.setContent { bounce = rememberReactionBounce(1L, "❤️", tracker) }
            runCurrent()
            clock.sendFrame(0L)
            runCurrent()
            clock.sendFrame(120_000_000L)
            runCurrent()
            assertEquals(1.16f, bounce.value, 0.001f)
            clock.sendFrame(300_000_000L)
            runCurrent()
            assertEquals(1f, bounce.value, 0f)
            clock.sendFrame(900_000_000L)
            runCurrent()
            assertEquals("The bounce must not repeat", 1f, bounce.value, 0f)
        } finally {
            composition.dispose()
            runCurrent()
            recomposer.close()
            runner.cancel()
        }
    }

    @Test
    fun eachChangeIsClaimedOnlyOnceAndMessagesAreIndependent() {
        val tracker = ReactionBounceTracker()
        assertEquals(null, tracker.eventFor(1L))
        tracker.record(1L, "❤️")
        tracker.record(2L, "👍")
        val first = tracker.eventFor(1L)!!
        val second = tracker.eventFor(2L)!!
        assertTrue(tracker.claim(1L, first))
        assertFalse(tracker.claim(1L, first))
        assertTrue(tracker.claim(2L, second))
        tracker.record(1L, "🥰")
        assertFalse(tracker.claim(1L, first))
        val replacement = tracker.eventFor(1L)!!
        assertTrue(tracker.claim(1L, replacement))
        tracker.record(1L, "")
        assertEquals(null, tracker.eventFor(1L))
        assertFalse(tracker.claim(1L, replacement))
        tracker.record(1L, "🥰")
        assertTrue(tracker.claim(1L, tracker.eventFor(1L)!!))
    }

    @Test
    fun historyAndRecycledRowsDoNotReplayTheBounce() = runTest {
        val tracker = ReactionBounceTracker()
        tracker.record(1L, "❤️")
        assertTrue(tracker.claim(1L, tracker.eventFor(1L)!!))
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodesApplier(), recomposer)
        lateinit var bounce: State<Float>
        val id = mutableStateOf(1L)
        try {
            composition.setContent { bounce = rememberReactionBounce(id.value, "❤️", tracker) }
            runCurrent()
            clock.sendFrame(0L)
            runCurrent()
            clock.sendFrame(120_000_000L)
            runCurrent()
            assertEquals(1f, bounce.value, 0f)
            id.value = 2L // Stored history has no newly recorded reaction event.
            runCurrent()
            clock.sendFrame(240_000_000L)
            runCurrent()
            assertEquals(1f, bounce.value, 0f)
            id.value = 1L
            runCurrent()
            clock.sendFrame(360_000_000L)
            runCurrent()
            assertEquals(1f, bounce.value, 0f)
        } finally {
            composition.dispose()
            runCurrent()
            recomposer.close()
            runner.cancel()
        }
    }

    @Test
    fun removalDuringScalingSettlesImmediatelyWithoutAnotherBounce() = runTest {
        val tracker = ReactionBounceTracker()
        tracker.record(1L, "❤️")
        val emoji = mutableStateOf("❤️")
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodesApplier(), recomposer)
        lateinit var bounce: State<Float>
        try {
            composition.setContent { bounce = rememberReactionBounce(1L, emoji.value, tracker) }
            runCurrent()
            clock.sendFrame(0L)
            runCurrent()
            clock.sendFrame(120_000_000L)
            runCurrent()
            assertTrue(bounce.value > 1f)
            tracker.record(1L, "")
            emoji.value = ""
            runCurrent()
            clock.sendFrame(136_000_000L)
            runCurrent()
            assertEquals(1f, bounce.value, 0f)
        } finally {
            composition.dispose()
            runCurrent()
            recomposer.close()
            runner.cancel()
        }
    }

    @Test
    fun missingFramesAndDisposalNeverLeaveTheReactionEnlarged() = runTest {
        val tracker = ReactionBounceTracker()
        tracker.record(1L, "❤️")
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodesApplier(), recomposer)
        lateinit var bounce: State<Float>
        try {
            composition.setContent { bounce = rememberReactionBounce(1L, "❤️", tracker) }
            runCurrent()
            clock.sendFrame(0L)
            runCurrent()
            clock.sendFrame(120_000_000L)
            runCurrent()
            assertTrue(bounce.value > 1f)
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(1f, bounce.value, 0f)
            tracker.record(1L, "👍")
            composition.setContent { bounce = rememberReactionBounce(1L, "👍", tracker) }
            runCurrent()
            clock.sendFrame(1_000_000_000L)
            runCurrent()
            clock.sendFrame(1_120_000_000L)
            runCurrent()
            assertTrue(bounce.value > 1f)
            composition.dispose()
            runCurrent()
            assertEquals(1f, bounce.value, 0f)
        } finally {
            composition.dispose()
            runCurrent()
            recomposer.close()
            runner.cancel()
        }
    }

    private class NoNodesApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = error("No UI nodes expected")
        override fun insertBottomUp(index: Int, instance: Unit) = error("No UI nodes expected")
        override fun remove(index: Int, count: Int) = error("No UI nodes expected")
        override fun move(from: Int, to: Int, count: Int) = error("No UI nodes expected")
        override fun onClear() = Unit
    }
}
