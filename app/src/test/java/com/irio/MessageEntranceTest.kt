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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class MessageEntranceTest {
    @Test
    fun missingAnimationFramesCannotLeaveASentMessageInvisible() = runTest {
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodesApplier(), recomposer)
        lateinit var alpha: State<Float>
        try {
            composition.setContent { alpha = rememberMessageEntranceAlpha(1L, animate = true) }
            runCurrent()
            assertEquals(0f, alpha.value, 0f)
            // A prefetched/detached ComposeView can stop receiving animation frames.
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals("A missing frame must not hide a clickable message forever", 1f, alpha.value, 0f)
        } finally {
            composition.dispose()
            runCurrent()
            recomposer.close()
            runner.cancel()
        }
    }

    @Test
    fun cancellationRestoresTheMessageOpacity() = runTest {
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodesApplier(), recomposer)
        lateinit var alpha: State<Float>
        try {
            composition.setContent { alpha = rememberMessageEntranceAlpha(2L, animate = true) }
            runCurrent()
            assertEquals(0f, alpha.value, 0f)
            composition.dispose()
            runCurrent()
            assertEquals("A cancelled entrance must settle to visible", 1f, alpha.value, 0f)
        } finally {
            composition.dispose()
            runCurrent()
            recomposer.close()
            runner.cancel()
        }
    }

    @Test
    fun normalEntranceStillFadesInWithoutMoving() = runTest {
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodesApplier(), recomposer)
        lateinit var alpha: State<Float>
        try {
            composition.setContent { alpha = rememberMessageEntranceAlpha(3L, animate = true) }
            runCurrent()
            clock.sendFrame(0L)
            runCurrent()
            clock.sendFrame(75_000_000L)
            runCurrent()
            assertTrue("The ordinary entrance must retain its fade", alpha.value > 0f && alpha.value < 1f)
            clock.sendFrame(180_000_000L)
            runCurrent()
            assertEquals(1f, alpha.value, 0f)
        } finally {
            composition.dispose()
            runCurrent()
            recomposer.close()
            runner.cancel()
        }
    }

    @Test
    fun missingFramesAfterAPartialFadeStillSettleToVisible() = runTest {
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodesApplier(), recomposer)
        lateinit var alpha: State<Float>
        try {
            composition.setContent { alpha = rememberMessageEntranceAlpha(5L, animate = true) }
            runCurrent()
            clock.sendFrame(0L)
            runCurrent()
            clock.sendFrame(75_000_000L)
            runCurrent()
            assertTrue(alpha.value > 0f && alpha.value < 1f)
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(1f, alpha.value, 0f)
        } finally {
            composition.dispose()
            runCurrent()
            recomposer.close()
            runner.cancel()
        }
    }

    @Test
    fun changingTheNewMessageMarkerCannotReplayOrRetainAnInterruptedFade() = runTest {
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodesApplier(), recomposer)
        val animate = mutableStateOf(true)
        lateinit var alpha: State<Float>
        try {
            composition.setContent { alpha = rememberMessageEntranceAlpha(6L, animate.value) }
            runCurrent()
            assertEquals(0f, alpha.value, 0f)
            animate.value = false
            runCurrent()
            clock.sendFrame(0L)
            runCurrent()
            assertEquals(1f, alpha.value, 0f)
            animate.value = true
            runCurrent()
            clock.sendFrame(16_000_000L)
            runCurrent()
            assertEquals("A bound message must not become transparent again", 1f, alpha.value, 0f)
        } finally {
            composition.dispose()
            runCurrent()
            recomposer.close()
            runner.cancel()
        }
    }

    @Test
    fun historyIsVisibleWithoutAnimationFrames() = runTest {
        val recomposer = Recomposer(coroutineContext + BroadcastFrameClock())
        val composition = Composition(NoNodesApplier(), recomposer)
        try {
            composition.setContent {
                assertEquals(1f, rememberMessageEntranceAlpha(4L, animate = false).value, 0f)
            }
        } finally {
            composition.dispose()
            recomposer.close()
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
