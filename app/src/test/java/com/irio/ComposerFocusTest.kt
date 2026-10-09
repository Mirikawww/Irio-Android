package com.irio

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ComposerFocusTest {
    @Test
    fun backgroundKeyboardHidePreservesFocusAfterReturning() = runTest {
        withComposer {
            imeVisible.value = true
            frame()
            owner.registry.currentState = Lifecycle.State.CREATED
            imeVisible.value = false
            frame()
            settle()
            assertEquals("Background IME disappearance must not clear composer focus", 0, focus.clearCount)
            owner.registry.currentState = Lifecycle.State.RESUMED
            frame()
            settle()
            assertEquals("Returning before the keyboard appears must retain focus", 0, focus.clearCount)
            imeVisible.value = true
            frame()
            imeVisible.value = false
            frame()
            settle()
            assertEquals("A later foreground keyboard dismissal must still clear focus", 1, focus.clearCount)
        }
    }

    @Test
    fun windowFocusLossIsNotAKeyboardDismissal() = runTest {
        withComposer {
            imeVisible.value = true
            frame()
            window.focused.value = false
            imeVisible.value = false
            frame()
            settle()
            assertEquals("A window losing focus must not clear composer focus", 0, focus.clearCount)
            window.focused.value = true
            frame()
            settle()
            assertEquals("A hidden keyboard on window return is not a dismissal", 0, focus.clearCount)
        }
    }

    @Test
    fun leavingDuringTheDismissalDelayDoesNotClearFocusWithoutAnotherFrame() = runTest {
        withComposer {
            imeVisible.value = true
            frame()
            imeVisible.value = false
            frame()
            advanceTimeBy(24)
            owner.registry.currentState = Lifecycle.State.CREATED
            // No Compose frame before the delayed callback: it must check live lifecycle state.
            settle()
            assertEquals(0, focus.clearCount)
        }
    }

    @Test
    fun firstFrameAndTransientZeroInsetsDoNotClearFocus() = runTest {
        withComposer {
            settle()
            assertEquals(0, focus.clearCount)
            imeVisible.value = true
            frame()
            imeVisible.value = false
            frame()
            advanceTimeBy(24)
            imeVisible.value = true
            frame()
            settle()
            assertEquals(0, focus.clearCount)
            imeVisible.value = false
            frame()
            settle()
            assertEquals(1, focus.clearCount)
        }
    }

    private suspend fun TestScope.withComposer(block: suspend Harness.() -> Unit) {
        val harness = Harness(this)
        try {
            harness.start()
            harness.block()
        } finally {
            harness.close()
            runCurrent()
        }
    }

    private class Harness(val scope: TestScope) {
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(scope.coroutineContext + clock)
        val runner = scope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodesApplier(), recomposer)
        val imeVisible = mutableStateOf(false)
        val owner = TestOwner()
        val window = TestWindow()
        val focus = TestFocusManager()
        var frameTime = 0L

        fun start() {
            composition.setContent {
                CompositionLocalProvider(
                    LocalFocusManager provides focus,
                    LocalWindowInfo provides window,
                    LocalLifecycleOwner provides owner,
                ) {
                    DismissComposerFocusWhenImeHides(imeVisible.value)
                }
            }
            scope.runCurrent()
        }

        fun frame() {
            Snapshot.sendApplyNotifications()
            scope.runCurrent()
            frameTime += 16_000_000L
            clock.sendFrame(frameTime)
            scope.runCurrent()
        }

        fun settle() {
            scope.advanceTimeBy(100)
            scope.runCurrent()
        }

        fun close() {
            composition.dispose()
            recomposer.close()
            runner.cancel()
        }
    }

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    private class TestWindow : WindowInfo {
        val focused = mutableStateOf(true)
        override val isWindowFocused: Boolean get() = focused.value
    }

    private class TestFocusManager : FocusManager {
        var clearCount = 0
        override fun clearFocus(force: Boolean) { clearCount++ }
        override fun moveFocus(focusDirection: FocusDirection): Boolean = false
    }

    private class NoNodesApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = error("No UI nodes expected")
        override fun insertBottomUp(index: Int, instance: Unit) = error("No UI nodes expected")
        override fun remove(index: Int, count: Int) = error("No UI nodes expected")
        override fun move(from: Int, to: Int, count: Int) = error("No UI nodes expected")
        override fun onClear() = Unit
    }
}
