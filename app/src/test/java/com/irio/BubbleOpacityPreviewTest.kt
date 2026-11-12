package com.irio

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
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
import org.robolectric.shadows.ShadowWindowManagerImpl

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, qualifiers = "w400dp-h1000dp-mdpi")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class BubbleOpacityPreviewTest {
    @Test fun resizingForPercentageNeverPausesThumbFollowingDuringContinuousDrag() = runTest {
        withUi { f ->
            val slider = f.slider().boundsInRoot
            val y = slider.center.y
            f.touch(MotionEvent.ACTION_DOWN, slider.left + slider.width * .5f, y)
            f.touch(MotionEvent.ACTION_MOVE, slider.left + slider.width * .6f, y)
            f.frames(20)
            val heights = mutableSetOf<Int>()
            val headerStates = mutableSetOf<Boolean>()
            for (fraction in listOf(.6f, .7f, .8f, .9f, .98f, .8f, .65f, .55f, .45f, .3f,
                .45f, .6f, .75f, .9f, .98f, .7f, .5f, .3f)) {
                f.touch(MotionEvent.ACTION_MOVE, slider.left + slider.width * fraction, y, frameCount = 1)
                val root = f.popupRoot()
                val params = root.layoutParams as WindowManager.LayoutParams
                heights += root.measuredHeight
                headerStates += f.descend(requireNotNull(f.preview())).any { f.hasText(it, "${ThemeStore.bubbleOpacity}%") }
                val thumbX = slider.left + 8f + (slider.width - 16f) * ThemeStore.bubbleOpacity / 100f
                val expectedX = (thumbX.toInt() - root.measuredWidth / 2).coerceIn(12, 388 - root.measuredWidth)
                assertEquals("Popup must track the thumb within a few frames even while its height changes", expectedX.toFloat(), params.x.toFloat(), 2f)
            }
            assertEquals("Exercise percentage appearing and disappearing", setOf(false, true), headerStates)
            assertEquals("Repeating the percentage must not resize the native Popup and interrupt positioning", 1, heights.size)
            f.touch(MotionEvent.ACTION_UP, slider.left + slider.width * .3f, y)
            f.frames(20)
            assertNull(f.preview())
        }
    }

    @Test fun percentageIsShownInThePreviewOnlyWhenItCoversTheOriginalValue() = runTest {
        withUi { f ->
            f.tap(.98f)
            f.frames(20)
            fun popupHasValue() = f.descend(requireNotNull(f.preview())).any {
                f.hasText(it, "${ThemeStore.bubbleOpacity}%")
            }
            assertTrue("The preview must repeat the percentage it covers", popupHasValue())
            f.tap(.1f)
            f.frames(20)
            assertFalse("Do not repeat the value when the original remains visible", popupHasValue())
        }
    }

    @Test fun popupAnimatesInAndOutAndContainsNoVisiblePercentage() = runTest {
        withUi(fullScreen = false) { f ->
            f.tap(.7f)
            val popup = requireNotNull(f.preview())
            assertTrue("Only the sample message belongs in the preview", f.descend(popup).none {
                it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { text -> "%" in text.text }
            })
            val entering = popup.boundsInRoot.width
            f.frames(20)
            val settled = requireNotNull(f.preview()).boundsInRoot.width
            val sample = f.nodes().single {
                it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "bubble-opacity-preview-message"
            }.boundsInRoot
            assertEquals("Preview must hug the bubble without an empty left column", sample.width + 28f, settled, 1f)
            assertTrue("Entrance must render intermediate scale frames", entering < settled - .1f)
            val outside = MotionEvent.obtain(0, 0, MotionEvent.ACTION_OUTSIDE, -1f, -1f, 0)
            try { f.popupRoot().dispatchTouchEvent(outside) } finally { outside.recycle() }
            f.frames(5)
            val exiting = requireNotNull(f.preview()) { "Keep the popup mounted during exit" }.boundsInRoot.width
            assertTrue("Exit must shrink before disposal: $exiting / $settled", exiting < settled - .1f)
            f.frames(20)
            assertNull("Remove the window after exit finishes", f.preview())
        }
    }

    @Test fun popupChoosesTheRoomierSideAndClampsToTheSafeWindow() {
        val provider = SliderPreviewPositionProvider(12, 36, 12, 48, 12)
        val window = androidx.compose.ui.unit.IntSize(400, 800)
        val popup = androidx.compose.ui.unit.IntSize(224, 120)
        val upperThumb = androidx.compose.ui.unit.IntRect(30, 70, 46, 86)
        val lowerThumb = androidx.compose.ui.unit.IntRect(354, 650, 370, 666)
        val below = provider.calculatePosition(upperThumb, window, androidx.compose.ui.unit.LayoutDirection.Ltr, popup)
        val above = provider.calculatePosition(lowerThumb, window, androidx.compose.ui.unit.LayoutDirection.Ltr, popup)
        assertEquals(12, below.x)
        assertEquals(98, below.y)
        assertEquals(164, above.x)
        assertEquals(518, above.y)
        val shortWindow = androidx.compose.ui.unit.IntSize(250, 200)
        val clamped = provider.calculatePosition(lowerThumb, shortWindow, androidx.compose.ui.unit.LayoutDirection.Rtl, popup)
        assertEquals(14, clamped.x)
        assertEquals(36, clamped.y)
    }

    @Test fun repeatedPhysicalTapsKeepWorkingThroughTheRealAppearancePage() = runTest {
        withUi { f ->
            f.tap(.7f)
            f.frames(120)
            assertNotNull(f.preview())
            f.tap(.4f)
            assertTrue("A settled real settings page must accept another tap", ThemeStore.bubbleOpacity in 38..42)
            assertNotNull(f.preview())
        }
    }

    @Test fun realAppearanceSliderShowsLivePreviewWhileDraggingAndHidesOnRelease() = runTest {
        withUi { f ->
            assertNull(f.preview())
            val slider = f.slider().boundsInRoot
            f.touch(MotionEvent.ACTION_DOWN, slider.left + slider.width * .25f, slider.center.y)
            assertNull("A press must not open a preview before a horizontal gesture is confirmed", f.preview())
            for (i in 1..6) {
                f.touch(MotionEvent.ACTION_MOVE, slider.left + slider.width * .25f + i * 16f, slider.center.y)
                if (i >= 3) {
                    assertNotNull("Dragging opacity must show the bubble preview", f.preview())
                    val message = f.nodes().single { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "bubble-opacity-preview-message" }
                    assertEquals("The actual preview bubble must receive the live slider value",
                        "${ThemeStore.bubbleOpacity}%", message.config[SemanticsProperties.StateDescription])
                }
            }
            val value = ThemeStore.bubbleOpacity
            assertTrue(value > 40)
            f.touch(MotionEvent.ACTION_UP, slider.left + slider.width * .25f + 96f, slider.center.y)
            f.frames(20)
            assertNull("Drag preview must close on release", f.preview())
            assertEquals(value, ThemeStore.bubbleOpacity)
        }
    }

    @Test fun confirmedTapsShowForThreeSecondsAndRepeatedTapsRestartTheTimer() = runTest {
        withUi(fullScreen = false) { f ->
            f.tap(.7f)
            assertNotNull("A track tap must show the bubble preview", f.preview())
            advanceTimeBy(2700); f.frames(1)
            assertNotNull(f.preview())
            f.tap(.4f)
            assertTrue("The repeated tap must still reach the original slider: ${ThemeStore.bubbleOpacity}", ThemeStore.bubbleOpacity in 38..42)
            assertNotNull("The repeated tap must reopen its preview", f.preview())
            advanceTimeBy(700); f.frames(1)
            assertNotNull("An old timeout must not close the latest tap preview", f.preview())
            advanceTimeBy(2400); f.frames(1)
            f.frames(20)
            assertNull("The latest tap preview must expire after three seconds", f.preview())
        }
    }

    @Test fun previewCanBeDismissedFromOutsideAndOptOutDisablesIt() = runTest {
        withUi { f ->
            f.tap(.7f)
            assertNotNull(f.preview())
            val window = f.popupRoot()
            val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_OUTSIDE, 0f, 0f, 0)
            try { window.dispatchTouchEvent(event) } finally { event.recycle() }
            f.frames()
            f.frames(20)
            assertNull("Outside touches must close a timed preview immediately", f.preview())
            ThemeStore.saveBetterSettingsDisplay(f.view.context, false)
            f.frames()
            f.tap(.3f)
            assertNull("The experiment is opt-in", f.preview())
        }
    }

    @Test fun verticalGesturesDoNotOpenThePreviewAndCancelledDragsCloseIt() = runTest {
        withUi(fullScreen = false) { f ->
            val bounds = f.slider().boundsInRoot
            f.touch(MotionEvent.ACTION_DOWN, bounds.center.x, bounds.center.y)
            f.touch(MotionEvent.ACTION_MOVE, bounds.center.x, bounds.center.y + 50f)
            f.touch(MotionEvent.ACTION_CANCEL, bounds.center.x, bounds.center.y + 50f)
            assertNull(f.preview())
            assertEquals(25, ThemeStore.bubbleOpacity)
            f.touch(MotionEvent.ACTION_DOWN, bounds.center.x, bounds.center.y)
            f.touch(MotionEvent.ACTION_MOVE, bounds.center.x + 50f, bounds.center.y)
            assertNotNull(f.preview())
            f.touch(MotionEvent.ACTION_CANCEL, bounds.center.x + 50f, bounds.center.y)
            f.frames(20)
            assertNull("Cancelled drag must not leave a floating preview behind", f.preview())
        }
    }

    private fun TestScope.withUi(fullScreen: Boolean = true, block: (Fixture) -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val context = controller.get()
        ThemeStore.ensureLoaded(context)
        val oldEnabled = ThemeStore.betterSettingsDisplay
        val oldOpacity = ThemeStore.bubbleOpacity
        val oldRadius = ThemeStore.inputCornerRadius
        val oldLanguage = ThemeStore.language
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(context)
        val f = Fixture(this, view, clock)
        try {
            ThemeStore.saveBetterSettingsDisplay(context, true)
            ThemeStore.saveBubbleOpacity(context, 25)
            ThemeStore.saveInputCornerRadius(context, 16)
            ThemeStore.saveLanguage(context, AppLanguage.ZH_CN)
            view.setParentCompositionContext(recomposer)
            context.setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme {
                        if (fullScreen) AppearanceSettingsScreen(onBack = {})
                        else BubbleOpacitySetting()
                    }
                }
            }
            f.frames(30)
            block(f)
        } finally {
            view.disposeComposition(); recomposer.close(); runner.cancel()
            ThemeStore.saveBetterSettingsDisplay(context, oldEnabled)
            ThemeStore.saveBubbleOpacity(context, oldOpacity)
            ThemeStore.saveInputCornerRadius(context, oldRadius)
            ThemeStore.saveLanguage(context, oldLanguage)
            controller.pause().stop().destroy()
        }
    }

    private class Fixture(val scope: TestScope, val view: ComposeView, val clock: BroadcastFrameClock) {
        private var time = 0L
        private var down = 0L
        private var eventTime = SystemClock.uptimeMillis()
        fun roots(): List<View> {
            val windows = shadowOf(view.context.getSystemService(WindowManager::class.java)) as ShadowWindowManagerImpl
            return (listOf(view) + windows.views).distinct()
        }
        fun frames(count: Int = 3) = repeat(count) {
            Snapshot.sendApplyNotifications(); scope.advanceTimeBy(16); scope.runCurrent()
            time = scope.testScheduler.currentTime * 1_000_000L
            clock.sendFrame(time); scope.runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
            roots().forEach { root ->
                root.requestLayout()
                val mode = if (root === view) View.MeasureSpec.EXACTLY else View.MeasureSpec.AT_MOST
                root.measure(View.MeasureSpec.makeMeasureSpec(400, mode), View.MeasureSpec.makeMeasureSpec(1000, mode))
                root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                descendants(root).filterIsInstance<ViewRootForTest>().forEach { it.measureAndLayoutForTest() }
            }
        }
        fun descendants(v: View): List<View> = listOf(v) + if (v is ViewGroup)
            (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()
        fun descend(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::descend)
        fun nodes() = roots().flatMap { descendants(it).filterIsInstance<ViewRootForTest>() }
            .flatMap { descend(it.semanticsOwner.unmergedRootSemanticsNode) }.distinctBy { it.id }
        fun hasText(n: SemanticsNode, text: String) = n.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == text }
        fun preview() = nodes().singleOrNull { it.config.getOrElse(SemanticsProperties.TestTag) { "" } in
            setOf("bubble-opacity-preview", "input-corner-preview") }
        fun popupRoot() = roots().single { root -> descendants(root).filterIsInstance<ViewRootForTest>().any {
            descend(it.semanticsOwner.unmergedRootSemanticsNode).any { n -> n.id == preview()?.id }
        } }
        fun slider(title: String = "气泡透明度"): SemanticsNode {
            val label = nodes().single { hasText(it, tr(title)) }
            return nodes().filter { it.config.contains(SemanticsActions.SetProgress) &&
                it.boundsInRoot.top >= label.boundsInRoot.bottom }.minBy { it.boundsInRoot.top }
        }
        fun touch(action: Int, x: Float, y: Float, frameCount: Int = 3) {
            // Android sends ACTION_OUTSIDE to a non-focusable popup before routing
            // a new touch to the underlying window. Direct View dispatch does not.
            if (action == MotionEvent.ACTION_DOWN && preview() != null) {
                val outside = MotionEvent.obtain(0, 0, MotionEvent.ACTION_OUTSIDE, -1f, -1f, 0)
                try { popupRoot().dispatchTouchEvent(outside) } finally { outside.recycle() }
                frames(1)
            }
            eventTime = maxOf(eventTime + 20, SystemClock.uptimeMillis())
            if (action == MotionEvent.ACTION_DOWN) down = eventTime
            val event = MotionEvent.obtain(down, eventTime, action, x, y, 0)
            try { view.dispatchTouchEvent(event) } finally { event.recycle() }
            frames(frameCount)
        }
        fun tap(fraction: Float, title: String = "气泡透明度") {
            val bounds = slider(title).boundsInRoot
            touch(MotionEvent.ACTION_DOWN, bounds.left + bounds.width * fraction, bounds.center.y)
            touch(MotionEvent.ACTION_UP, bounds.left + bounds.width * fraction, bounds.center.y)
        }
    }
}
