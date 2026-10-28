package com.irio

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class IrioSnackbarSwipeTest {
    @Test fun retriggerBeforeLeavingTheScreenSpringsBackInsteadOfTeleporting() = runTest {
        for (direction in listOf(-1f, 1f)) withUi { ui ->
            ui.frames(40)
            ui.swipe(direction * 300f)
            val displaced = requireNotNull(ui.text(ui.message.value)).boundsInRoot
            assertTrue("Fixture must still be partially visible", displaced.width > 1f)
            ui.nonce.value++
            ui.frames()
            val returning = requireNotNull(ui.text(ui.message.value)).boundsInRoot
            assertTrue("Retrigger must preserve the current displacement, not flash at center",
                kotlin.math.abs(returning.center.x - 180f) > 20f)
            ui.frames(60)
            assertEquals(180f, requireNotNull(ui.text(ui.message.value)).boundsInRoot.center.x, 1f)
            assertTrue(ui.visible.value)
            assertEquals(0, ui.dismissals)
        }
    }

    @Test fun retriggerAfterLeavingTheScreenReplaysTheBottomEntranceBeforeOldDismissal() = runTest {
        for (direction in listOf(-1f, 1f)) withUi { ui ->
            ui.frames(40)
            val resting = requireNotNull(ui.text(ui.message.value)).boundsInRoot
            ui.swipe(direction * 300f)
            var offScreen = false
            repeat(50) {
                if (!offScreen) {
                    ui.frames()
                    val bounds = ui.surface()?.boundsInRoot
                    offScreen = bounds == null || bounds.width <= 1f || bounds.right <= 0f || bounds.left >= 360f
                }
            }
            assertTrue("Fixture must reach the off-screen portion of the swipe", offScreen)
            assertTrue("Retrigger before the old spring finishes and calls dismiss", ui.visible.value)
            ui.nonce.value++
            val arrivals = (0 until 60).map {
                ui.frames()
                ui.text(ui.message.value)?.boundsInRoot
            }.filterNotNull().filter { it.width > 1f }
            assertTrue("An off-screen notice must animate in from below, not snap fully visible",
                arrivals.any { it.width < resting.width - 5f && it.top > resting.top + 1f })
            arrivals.forEach { assertEquals("A new bottom entrance has no sideways motion", 180f, it.center.x, 1f) }
            assertTrue(ui.visible.value)
            assertEquals(0, ui.dismissals)
        }
    }

    @Test fun swipeDismissalNeverFlashesBackAtTheRestingPosition() = runTest {
        for (clearMessage in listOf(false, true)) for (direction in listOf(-1f, 1f)) withUi(clearMessage) { ui ->
            ui.frames(40)
            ui.swipe(direction * 300f)
            repeat(60) { frame ->
                ui.frames()
                if (!ui.visible.value) {
                    val bounds = ui.surface()?.boundsInRoot
                    assertTrue("Dismissed snackbar flashed in its host at frame $frame: $bounds",
                        bounds == null || bounds.width <= 1f || bounds.right <= 0f || bounds.left >= 360f)
                }
            }
            assertEquals("The swipe must reach the dismissal boundary", 1, ui.dismissals)
        }
    }

    @Test fun hidingAndShowingDuringSwipeInvalidatesThePreviousDismissalEvenWithoutANonce() = runTest {
        withUi { ui ->
            ui.frames(40)
            ui.swipe(300f)
            ui.visible.value = false
            ui.frames()
            ui.visible.value = true
            ui.frames(60)
            assertTrue("A new visible lifetime must not receive an old dismissal", ui.visible.value)
            assertEquals(0, ui.dismissals)
            assertEquals(180f, requireNotNull(ui.text(ui.message.value)).boundsInRoot.center.x, 1f)
        }
    }

    @Test fun aFullSwipeDismissesAndTheNextNoticeEntersWithoutHorizontalMovement() = runTest {
        for (direction in listOf(-1f, 1f)) withUi { ui ->
            ui.frames(40)
            ui.swipe(direction * 300f)
            ui.frames(60)
            assertFalse("The fixture swipe must dismiss the old notice", ui.visible.value)
            assertEquals(1, ui.dismissals)
            ui.visible.value = true
            ui.nonce.value++
            val entranceWidths = mutableListOf<Float>()
            repeat(40) { index ->
                ui.frames()
                ui.text(ui.message.value)?.boundsInRoot?.takeIf { it.width > 1f }?.let { bounds ->
                    entranceWidths += bounds.width
                    assertEquals("A fresh entrance must stay horizontally centered at frame $index", 180f, bounds.center.x, 1f)
                }
            }
            assertTrue("A settled dismissal must be followed by an animated entrance, not a flash",
                entranceWidths.any { it in 1f..250f })
            assertTrue(ui.visible.value)
            assertTrue(requireNotNull(ui.text(ui.message.value)).boundsInRoot.width > 100f)
        }
    }

    @Test fun repeatingTheSameNoticeDuringSwipeCancelsThePreviousDismissal() = runTest {
        withUi { ui ->
            ui.frames(40)
            ui.swipe(-300f)
            ui.nonce.value++
            ui.frames(60)
            assertTrue("Repeating a notice must cancel the old swipe's dismiss callback", ui.visible.value)
            assertEquals(0, ui.dismissals)
            val bounds = requireNotNull(ui.text(ui.message.value)).boundsInRoot
            assertTrue("A repeated notice must not remain off-screen", bounds.width > 100f)
            assertEquals(180f, bounds.center.x, 1f)
        }
    }

    @Test fun cancellingAPartialDragReturnsTheNoticeToItsRestingPosition() = runTest {
        withUi { ui ->
            ui.frames(40)
            ui.swipe(80f, endAction = MotionEvent.ACTION_CANCEL)
            ui.frames(60)
            val bounds = requireNotNull(ui.text(ui.message.value)).boundsInRoot
            assertEquals("Cancelled gestures must spring back instead of poisoning future notices", 180f, bounds.center.x, 1f)
        }
    }

    @Test fun aNewMessageDuringSwipeCannotBeDismissedByTheOldAnimation() = runTest {
        withUi { ui ->
            ui.frames(40)
            ui.swipe(300f)
            ui.message.value = "new notice"
            ui.nonce.value++
            ui.frames(60)
            assertTrue("The old swipe must not dismiss the replacement notice", ui.visible.value)
            val bounds = requireNotNull(ui.text("new notice")).boundsInRoot
            assertTrue("The replacement must be visible inside its host", bounds.width > 100f)
            assertEquals("The new notice must start from the centered position", 360f / 2, bounds.center.x, 1f)
        }
    }

    private fun TestScope.withUi(clearMessageOnDismiss: Boolean = false, block: (Ui) -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(controller.get())
        val ui = Ui(this, view, clock)
        try {
            view.setParentCompositionContext(recomposer)
            controller.get().setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme {
                        Box(Modifier.fillMaxSize()) {
                            IrioSnackbar(ui.visible.value, ui.message.value, 0.dp,
                                onDismiss = {
                                    ui.dismissals++
                                    ui.visible.value = false
                                    if (clearMessageOnDismiss) ui.message.value = ""
                                }, restartKey = ui.nonce.value)
                        }
                    }
                }
            }
            block(ui)
        } finally {
            view.disposeComposition(); recomposer.close(); runner.cancel()
            controller.pause().stop().destroy()
        }
    }

    private class Ui(val scope: TestScope, val view: ComposeView, val clock: BroadcastFrameClock) {
        val visible = mutableStateOf(true)
        val message = mutableStateOf("old notice")
        val nonce = mutableStateOf(0)
        var dismissals = 0
        private var time = 0L

        fun frames(count: Int = 1) = repeat(count) {
            Snapshot.sendApplyNotifications(); scope.advanceTimeBy(16); scope.runCurrent()
            time += 16_000_000L; clock.sendFrame(time); scope.runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
            view.requestLayout()
            view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 360, 640)
        }

        private fun nodes(): List<SemanticsNode> {
            fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
            fun nodes(view: View): List<SemanticsNode> =
                (if (view is ViewRootForTest) descend(view.semanticsOwner.unmergedRootSemanticsNode) else emptyList()) +
                    (if (view is ViewGroup) (0 until view.childCount).flatMap { nodes(view.getChildAt(it)) } else emptyList())
            return nodes(view)
        }

        fun text(value: String): SemanticsNode? = nodes().firstOrNull { node ->
            node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == value }
        }

        fun surface(): SemanticsNode? = nodes().firstOrNull { node ->
            node.config.getOrElse(SemanticsProperties.TestTag) { "" } == "irio-snackbar-surface"
        }

        fun swipe(distance: Float, endAction: Int = MotionEvent.ACTION_UP) {
            val bounds = requireNotNull(text(message.value)).boundsInRoot
            val down = SystemClock.uptimeMillis()
            fun event(action: Int, step: Int) {
                val motion = MotionEvent.obtain(down, down + step * 16L, action,
                    bounds.center.x + distance * step.coerceAtMost(8) / 8f, bounds.center.y, 0)
                try { view.dispatchTouchEvent(motion) } finally { motion.recycle() }
                frames()
            }
            event(MotionEvent.ACTION_DOWN, 0)
            (1..8).forEach { event(MotionEvent.ACTION_MOVE, it) }
            assertTrue("The fixture must exercise the actual drag handler",
                kotlin.math.abs(requireNotNull(text(message.value)).boundsInRoot.center.x - bounds.center.x) > 20f)
            event(endAction, 9)
        }
    }
}
