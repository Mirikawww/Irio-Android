package com.irio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
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
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModelSwitcherActionRevealTest {
    @Test fun unavailableSearchTapExplainsConfigurationWithoutEnablingSearch() = runTest {
        Harness(this).use { h ->
            h.available.value = false
            h.advance(160)
            assertTrue(h.button("网络搜索").config.contains(SemanticsProperties.Disabled))
            h.touch("网络搜索", MotionEvent.ACTION_DOWN); h.advance(32)
            h.touch("网络搜索", MotionEvent.ACTION_UP); h.advance(64)
            assertEquals("A real tap on unavailable search must explain the prerequisite", 1, h.searchConfigurationNotices)
            assertFalse(h.search.value)
            assertFalse(h.labelShown("网络搜索"))
            h.active.value = false; h.advance(32)
            h.click("网络搜索"); h.advance(32)
            assertEquals("An outgoing panel must not issue notices", 1, h.searchConfigurationNotices)
            h.active.value = true; h.available.value = true; h.advance(32)
            h.click("网络搜索"); h.advance(64)
            assertTrue(h.search.value)
            assertEquals(1, h.searchConfigurationNotices)
        }
    }

    @Test fun restingActionsShowOnlyCenteredIconsWithAccessibleToggleNames() = runTest {
        Harness(this).use { h ->
            h.advance(160)
            for (label in h.labels) {
                assertFalse("Resting $label must be icon-only", h.labelShown(label))
                val button = h.button(label)
                assertEquals(tr(label), button.config[SemanticsProperties.ContentDescription].single())
                assertEquals(button.boundsInRoot.center.x, h.icon(label).boundsInRoot.center.x, 1f)
            }
            val widths = h.labels.map { h.button(it).boundsInRoot.width }
            assertEquals(widths[0], widths[1], 1f)
            assertEquals(widths[1], widths[2], 1f)
        }
    }

    @Test fun tappingExpandsOneLabelSmoothlySqueezesPeersAndCollapsesAfter1500Millis() = runTest {
        Harness(this).use { h ->
            h.advance(160)
            val resting = h.labels.map { h.button(it).boundsInRoot.width }
            h.click("网络搜索")
            val clickedAt = testScheduler.currentTime
            h.advance(48)
            val intermediate = h.button("网络搜索").boundsInRoot.width
            assertTrue("Click must begin a real expansion", intermediate > resting[1] + 1f)
            h.advance(320)
            val expanded = h.labels.map { h.button(it).boundsInRoot.width }
            assertTrue("Expansion must animate rather than jump", intermediate < expanded[1] - 1f)
            assertTrue(expanded[0] < resting[0] && expanded[2] < resting[2])
            assertEquals(resting.sum(), expanded.sum(), 1f)
            assertEquals(listOf("网络搜索"), h.labels.filter(h::labelShown))
            assertTrue("Tap still toggles the setting", h.search.value)
            h.advance(1499 - (testScheduler.currentTime - clickedAt))
            assertTrue(h.labelShown("网络搜索"))
            h.advance(1)
            assertFalse("The 1.5s timer must close the label", h.labelShown("网络搜索"))
            h.advance(320)
            h.labels.forEachIndexed { index, name -> assertEquals(resting[index], h.button(name).boundsInRoot.width, 1f) }
        }
    }

    @Test fun expansionArrivesQuicklyThenOvershootsAndSettlesWithoutClippingPeers() = runTest {
        Harness(this).use { h ->
            h.advance(160)
            val resting = h.button("网络搜索").boundsInRoot.width
            h.click("网络搜索"); h.advance(64)
            val early = h.button("网络搜索").boundsInRoot.width
            val samples = mutableListOf(early)
            repeat(22) {
                h.advance(16)
                samples += h.button("网络搜索").boundsInRoot.width
                val widths = h.labels.map { h.button(it).boundsInRoot.width }
                assertEquals(244f, widths.sum(), 1.5f)
                assertTrue("Elastic overshoot must not crush neighboring icons", widths.all { it >= 39f })
            }
            val settled = samples.last()
            assertTrue("Most travel must happen in the first 64ms: $resting -> $early -> $settled",
                early - resting >= (settled - resting) * 0.65f)
            assertTrue("Keep a visible elastic rebound rather than clamping it away: $samples", samples.max() > settled + 3f)
            assertEquals(settled, samples[samples.lastIndex - 2], 1f)
        }
    }

    @Test fun physicalLongPressKeepsLabelUntilReleaseWithoutToggling() = runTest {
        Harness(this).use { h ->
            h.advance(160)
            h.touch("快速", MotionEvent.ACTION_DOWN)
            h.advance(650)
            assertEquals(listOf("快速"), h.labels.filter(h::labelShown))
            h.advance(2_000)
            assertTrue("A held label must outlive the click timeout", h.labelShown("快速"))
            assertFalse("Long press is a label preview, not a toggle", h.fast.value)
            h.touch("快速", MotionEvent.ACTION_UP)
            h.advance(32)
            assertFalse("Release must collapse immediately, without a click timeout", h.labelShown("快速"))
            assertFalse("Releasing a long press must not trigger a click", h.fast.value)
        }
    }

    @Test fun switchingAndRepeatedClicksReplaceThePreviousTimeout() = runTest {
        Harness(this).use { h ->
            h.advance(160)
            h.click("快速"); h.advance(1_200)
            h.click("自动朗读"); h.advance(320)
            assertEquals(listOf("自动朗读"), h.labels.filter(h::labelShown))
            h.advance(1_000)
            h.click("自动朗读"); h.advance(200)
            assertEquals("An old timer must not close a repeated activation", listOf("自动朗读"), h.labels.filter(h::labelShown))
            h.advance(1_300)
            assertTrue(h.labels.none(h::labelShown))
        }
    }

    @Test fun releasingAnOldHoldDoesNotCollapseTheNewlyClickedAction() = runTest {
        Harness(this).use { h ->
            h.advance(160)
            h.touch("快速", MotionEvent.ACTION_DOWN); h.advance(650)
            assertTrue(h.labelShown("快速"))
            h.click("自动朗读"); h.advance(100)
            h.touch("快速", MotionEvent.ACTION_UP); h.advance(32)
            assertEquals(listOf("自动朗读"), h.labels.filter(h::labelShown))
            assertFalse(h.fast.value)
            assertTrue(h.read.value)
            h.advance(1_500)
            assertTrue(h.labels.none(h::labelShown))
            h.touch("网络搜索", MotionEvent.ACTION_DOWN); h.advance(650)
            assertTrue(h.labelShown("网络搜索"))
            h.touch("网络搜索", MotionEvent.ACTION_CANCEL); h.advance(32)
            assertTrue("Cancelled holds must not leave labels stuck open", h.labels.none(h::labelShown))
            assertFalse(h.search.value)
        }
    }

    @Test fun physicalTapTogglesButAccessibilityLongClickOnlyReveals() = runTest {
        Harness(this).use { h ->
            h.advance(160)
            h.touch("快速", MotionEvent.ACTION_DOWN); h.advance(32)
            h.touch("快速", MotionEvent.ACTION_UP); h.advance(64)
            assertTrue(h.fast.value); assertTrue(h.labelShown("快速"))
            assertTrue(h.button("网络搜索").config[SemanticsActions.OnLongClick].action!!.invoke())
            h.advance(32)
            assertFalse(h.search.value)
            assertEquals(listOf("网络搜索"), h.labels.filter(h::labelShown))
            h.advance(1_500)
            assertTrue("Accessibility long click has no release event, so it uses a timeout", h.labels.none(h::labelShown))
        }
    }

    @Test fun disabledSearchAndTransitioningPanelsCannotOpenLabelsOrToggleSettings() = runTest {
        Harness(this).use { h ->
            h.advance(160)
            h.click("网络搜索"); h.advance(64)
            h.available.value = false; h.advance(32)
            assertFalse(h.labelShown("网络搜索"))
            h.click("网络搜索"); h.advance(32)
            assertTrue(h.search.value)
            assertTrue(h.labels.none(h::labelShown))
            h.click("快速"); h.advance(64)
            h.active.value = false; h.advance(32)
            assertTrue(h.labels.none(h::labelShown))
            h.click("快速"); h.advance(32)
            assertTrue(h.fast.value)
            assertTrue(h.labels.none(h::labelShown))
        }
    }

    @Test fun expandedLabelsFitBothLanguagesAndFontScalesWithoutMovingOutsideTheRow() = runTest {
        Harness(this).use { h ->
            h.advance(160)
            for (language in listOf(AppLanguage.ZH_CN, AppLanguage.EN)) {
                ThemeStore.saveLanguage(h.context, language)
                for (scale in listOf(1f, 2f)) {
                    h.scale.value = scale; h.advance(100)
                    for (name in h.labels) {
                        h.click(name); h.advance(400)
                        val layouts = mutableListOf<TextLayoutResult>()
                        h.text(name).config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
                        assertFalse("$language/$scale/$name must fit", layouts.single().hasVisualOverflow)
                        assertEquals(listOf(name), h.labels.filter(h::labelShown))
                        val bounds = h.labels.map { h.button(it).boundsInRoot }
                        assertTrue(bounds.all { it.width >= 39f && it.left >= 5f && it.right <= 259f })
                        assertEquals(4f, bounds[1].left - bounds[0].right, 1f)
                        assertEquals(4f, bounds[2].left - bounds[1].right, 1f)
                        for (label in h.labels) assertEquals(18f, h.icon(label).boundsInRoot.width, 0.1f)
                    }
                }
            }
        }
    }

    @Test fun labelInkFadesInWhileRapidRetargetingConservesTheRowWidth() = runTest {
        Harness(this).use { h ->
            h.advance(160)
            h.click("网络搜索"); h.advance(48)
            val earlyInk = h.labelInkContrast("网络搜索")
            h.advance(320)
            val settledInk = h.labelInkContrast("网络搜索")
            assertTrue("Early text must be visible but translucent: $earlyInk -> $settledInk", earlyInk > 0 && earlyInk < settledInk * 0.9)
            for (name in listOf("快速", "自动朗读", "网络搜索", "快速")) {
                h.click(name)
                repeat(5) {
                    h.advance(16)
                    val widths = h.labels.map { h.button(it).boundsInRoot.width }
                    assertEquals(244f, widths.sum(), 1.5f)
                    assertTrue(widths.all { it >= 39f })
                }
                assertEquals(listOf(name), h.labels.filter(h::labelShown))
            }
        }
    }

    private class Harness(private val scope: TestScope) : AutoCloseable {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val context = activity.get()
        private val oldLanguage = ThemeStore.language
        val view = ComposeView(context)
        private val clock = BroadcastFrameClock()
        private val recomposer = Recomposer(scope.coroutineContext + clock)
        private val runner = scope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val labels = listOf("快速", "网络搜索", "自动朗读")
        val fast = mutableStateOf(false)
        val search = mutableStateOf(false)
        val read = mutableStateOf(false)
        val available = mutableStateOf(true)
        var searchConfigurationNotices = 0
        val active = mutableStateOf(true)
        val scale = mutableStateOf(1f)
        private var nanos = 0L
        private var downTime = 0L
        init {
            ThemeStore.ensureLoaded(context)
            ThemeStore.saveLanguage(context, AppLanguage.ZH_CN)
            view.setParentCompositionContext(recomposer)
            context.setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, scale.value)) {
                    AppTheme {
                        ModelSwitcherQuickActions(
                            fastMode = fast.value, webSearchAvailable = available.value,
                            webSearchEnabled = search.value, autoRead = read.value, active = active.value,
                            onFastModeChange = { fast.value = it }, onWebSearchChange = { search.value = it },
                            onAutoReadChange = { read.value = it },
                            onWebSearchUnavailable = { searchConfigurationNotices++ },
                        )
                    }
                }
            }
        }
        fun advance(ms: Long) {
            var remaining = ms
            while (remaining > 0) {
                val step = remaining.coerceAtMost(16)
                Snapshot.sendApplyNotifications(); scope.runCurrent()
                scope.advanceTimeBy(step); scope.runCurrent()
                Snapshot.sendApplyNotifications(); scope.runCurrent()
                nanos += step * 1_000_000L; clock.sendFrame(nanos); scope.runCurrent()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step))
                view.requestLayout()
                view.measure(View.MeasureSpec.makeMeasureSpec(264, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, 264, 600)
                val bitmap = Bitmap.createBitmap(264, 600, Bitmap.Config.ARGB_8888)
                try { view.draw(Canvas(bitmap)) } finally { bitmap.recycle() }
                remaining -= step
            }
        }
        fun nodes(): List<SemanticsNode> {
            fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
            return descend((view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
        }
        fun text(label: String) = nodes().single {
            it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { value -> value.text == tr(label) }
        }
        fun icon(label: String): SemanticsNode {
            val tag = when (label) { "快速" -> "model-fast-mode-icon"; "网络搜索" -> "model-web-search-icon"; else -> "model-auto-read-icon" }
            return nodes().single { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == tag }
        }
        fun button(label: String): SemanticsNode {
            var node = icon(label)
            while (!node.config.contains(SemanticsActions.OnClick)) node = node.parent!!
            return node
        }
        fun labelShown(label: String) = !text(label).config.contains(SemanticsProperties.HideFromAccessibility)
        fun labelInkContrast(label: String): Int {
            val bitmap = Bitmap.createBitmap(264, 600, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                val button = button(label).boundsInRoot
                val fill = bitmap.getPixel(button.center.x.toInt(), button.top.toInt() + 3)
                val text = text(label).boundsInRoot
                var contrast = 0
                for (x in text.left.toInt().coerceAtLeast(0) until text.right.toInt().coerceAtMost(264)) {
                    for (y in text.top.toInt().coerceAtLeast(0) until text.bottom.toInt().coerceAtMost(600)) {
                        val pixel = bitmap.getPixel(x, y)
                        // Selected text and translucent fill share RGB; opacity carries their contrast.
                        val distance = 3 * kotlin.math.abs(android.graphics.Color.alpha(pixel) - android.graphics.Color.alpha(fill)) +
                            kotlin.math.abs(android.graphics.Color.red(pixel) - android.graphics.Color.red(fill)) +
                            kotlin.math.abs(android.graphics.Color.green(pixel) - android.graphics.Color.green(fill)) +
                            kotlin.math.abs(android.graphics.Color.blue(pixel) - android.graphics.Color.blue(fill))
                        contrast = maxOf(contrast, distance)
                    }
                }
                return contrast
            } finally { bitmap.recycle() }
        }
        fun click(label: String) { assertTrue(button(label).config[SemanticsActions.OnClick].action!!.invoke()); scope.runCurrent() }
        fun touch(label: String, action: Int) {
            val point = button(label).boundsInRoot.center
            if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, point.x, point.y, 0)
            try { view.dispatchTouchEvent(event) } finally { event.recycle() }
            scope.runCurrent()
        }
        override fun close() {
            view.disposeComposition(); recomposer.close(); runner.cancel()
            ThemeStore.saveLanguage(context, oldLanguage)
            activity.pause().stop().destroy()
        }
    }
}
