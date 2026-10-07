package com.irio

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CopyConfirmationTest {
    @Test
    fun successfulCopyShowsConfirmationForTwoSeconds() = runTest {
        val state = CopyConfirmationState(backgroundScope)
        var copies = 0
        assertFalse(state.confirmed)
        state.copy { copies++ }
        assertEquals(1, copies)
        assertTrue(state.confirmed)
        runCurrent()
        advanceTimeBy(1_999)
        runCurrent()
        assertTrue(state.confirmed)
        advanceTimeBy(1)
        runCurrent()
        assertFalse(state.confirmed)
    }

    @Test
    fun clickingAgainCopiesAgainAndRestartsTheFullDuration() = runTest {
        val state = CopyConfirmationState(backgroundScope)
        var copies = 0
        state.copy { copies++ }
        runCurrent()
        advanceTimeBy(1_500)
        state.copy { copies++ }
        runCurrent()
        assertEquals(2, copies)
        advanceTimeBy(500)
        runCurrent()
        assertTrue("The previous timer must not clear the new confirmation", state.confirmed)
        advanceTimeBy(1_499)
        runCurrent()
        assertTrue(state.confirmed)
        advanceTimeBy(1)
        runCurrent()
        assertFalse(state.confirmed)
    }

    @Test
    fun rapidClicksNeverSuppressCopying() = runTest {
        val state = CopyConfirmationState(backgroundScope)
        var copies = 0
        repeat(20) { state.copy { copies++ } }
        assertEquals(20, copies)
        assertTrue(state.confirmed)
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertFalse(state.confirmed)
    }

    @Test
    fun confirmationCanStartAgainAfterItExpires() = runTest {
        val state = CopyConfirmationState(backgroundScope)
        state.copy { }
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertFalse(state.confirmed)
        state.copy { }
        assertTrue(state.confirmed)
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertFalse(state.confirmed)
    }

    @Test
    fun differentMessagesHaveIndependentConfirmationTimers() = runTest {
        val first = CopyConfirmationState(backgroundScope)
        val second = CopyConfirmationState(backgroundScope)
        first.copy { }
        runCurrent()
        assertFalse(second.confirmed)
        advanceTimeBy(1_000)
        second.copy { }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(first.confirmed)
        assertTrue(second.confirmed)
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(second.confirmed)
    }

    @Test
    fun disposingARowClearsItsConfirmationAndTimer() = runTest {
        val state = CopyConfirmationState(backgroundScope)
        state.copy { }
        runCurrent()
        state.dispose()
        assertFalse(state.confirmed)
        // If the row state is reused, the disposed timer cannot clear a later copy.
        advanceTimeBy(1_000)
        state.copy { }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(state.confirmed)
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(state.confirmed)
    }

    @Test
    fun failedCopyDoesNotShowASuccessConfirmation() = runTest {
        val state = CopyConfirmationState(backgroundScope)
        try {
            state.copy { error("Clipboard unavailable") }
            throw AssertionError("The clipboard failure must propagate")
        } catch (expected: IllegalStateException) {
            assertEquals("Clipboard unavailable", expected.message)
        }
        assertFalse(state.confirmed)
    }
}
