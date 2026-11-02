package com.irio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.composables.ui.theme.colors
import com.composables.ui.theme.primaryColor
import com.composeunstyled.theme.Theme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThinkingReadoutTransitionTest {
    @Test fun differentLengthLabelsStayRightAlignedWithoutWidthAnimation() = runTest {
        Harness(this).use { h ->
            h.frames(35)
            val narrow = h.animated().boundsInRoot.width
            val right = h.animated().boundsInRoot.right
            h.effort.value = ReasoningEffort.XHIGH
            val expansion = List(24) {
                h.frames()
                assertEquals("The text's right edge stays anchored", right, h.animated().boundsInRoot.right, 0.1f)
                h.animated().boundsInRoot.width
            }
            val wide = expansion.last()
            assertTrue(wide > narrow + 10f)
            assertTrue("Width must not interpolate: $expansion",
                expansion.all { kotlin.math.abs(it - narrow) <= 1f || kotlin.math.abs(it - wide) <= 1f })
            h.effort.value = ReasoningEffort.LOW
            val contraction = List(24) { h.frames(); h.animated().boundsInRoot.width }
            assertTrue("Two-to-one width must not interpolate: $contraction",
                contraction.all { kotlin.math.abs(it - narrow) <= 1f || kotlin.math.abs(it - wide) <= 1f })
            assertEquals(narrow, contraction.last(), 1f)
        }
    }

    @Test fun shorteningReplacesTheOldLabelImmediately() = runTest {
        Harness(this).use { h ->
            h.effort.value = ReasoningEffort.XHIGH; h.frames(35)
            val wide = h.animated().boundsInRoot.width
            val right = h.animated().boundsInRoot.right
            h.effort.value = ReasoningEffort.LOW; h.frames(2)
            assertTrue("The new label should replace the old label immediately", h.animated().boundsInRoot.width < wide - 10f)
            assertEquals(right, h.animated().boundsInRoot.right, 0.1f)
            h.assertMatchesReference("The new label should be settled without a transition")
        }
    }

    @Test fun changingLevelUpdatesWithoutAThinkingTextAnimation() = runTest {
        Harness(this).use { h ->
            h.frames(35)
            h.effort.value = ReasoningEffort.LOW; h.frames(4)
            val moving = h.headerPixels()
            val target = h.headerPixels(reference = true)
            assertArrayEquals("The readout should settle directly to the new text", target, moving)
        }
    }

    @Test fun thinkingReadoutDoesNotReserveAnimationOverflow() = runTest {
        Harness(this).use { h ->
            h.frames(35)
            h.effort.value = ReasoningEffort.LOW
            h.frames()
            assertFalse("A static readout should stay inside its line box", h.hasVerticalRollOverflow())
        }
    }

    @Test fun settledSingleCharacterMatchesFreshRenderingWithoutTrailingOldGlyphs() = runTest {
        Harness(this).use { h ->
            h.frames(35)
            for (language in AppLanguage.entries) {
                ThemeStore.saveLanguage(h.context, language)
                for (scale in listOf(1f, 2f)) {
                    h.scale.value = scale
                    for (target in listOf(ReasoningEffort.HIGH, ReasoningEffort.LOW, ReasoningEffort.OFF)) {
                        h.effort.value = ReasoningEffort.XHIGH; h.frames(25)
                        h.effort.value = ReasoningEffort.MINIMAL; h.frames(2)
                        h.effort.value = target; h.frames(40)
                        h.assertMatchesReference("$language/$scale/$target")
                    }
                }
            }
        }
    }

    @Test fun shrinkingKeepsTextWithinTheReadoutBounds() = runTest {
        Harness(this).use { h ->
            h.frames(35)
            for (long in listOf(ReasoningEffort.XHIGH, ReasoningEffort.MINIMAL)) {
                h.effort.value = long; h.frames(25)
                h.effort.value = ReasoningEffort.LOW
                repeat(25) { h.frames(); h.assertNoOutsideInk() }
            }
        }
    }

    @Test fun maxTextUsesTheSameAccentAsOrdinaryLevels() = runTest {
        Harness(this).use { h ->
            h.effort.value = ReasoningEffort.MAX
            h.frames(35)
            h.assertContainsInkColor(h.accent)
        }
    }

    private class Harness(private val scope: TestScope) : AutoCloseable {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val context = activity.get()
        val effort = mutableStateOf(ReasoningEffort.HIGH)
        val scale = mutableStateOf(1f)
        var accent = 0
        private val oldLanguage = ThemeStore.language
        private val clock = BroadcastFrameClock()
        private val recomposer = Recomposer(scope.coroutineContext + clock)
        private val runner = scope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        private val view = ComposeView(context)
        private var nanos = 0L
        init {
            ThemeStore.ensureLoaded(context)
            ThemeStore.saveLanguage(context, AppLanguage.ZH_CN)
            view.setParentCompositionContext(recomposer)
            context.setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, scale.value)) {
                    AppTheme {
                        accent = Theme[colors][primaryColor].toArgb()
                        Column(Modifier.fillMaxSize().background(Color.White)) {
                            Box(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterEnd) {
                                ModelSwitcherThinkingReadout(effort.value, true)
                            }
                            Box(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterEnd) {
                                ModelSwitcherThinkingReadout(effort.value, true)
                            }
                        }
                    }
                }
            }
        }
        fun frames(count: Int = 1) = repeat(count) {
            Snapshot.sendApplyNotifications(); scope.runCurrent()
            nanos += 16_000_000L; clock.sendFrame(nanos); scope.runCurrent()
            view.requestLayout()
            view.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(160, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 320, 160)
            bitmap().recycle()
        }
        private fun bitmap(): Bitmap = Bitmap.createBitmap(320, 160, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        fun animated(): SemanticsNode {
            fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
            return descend((view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode).filter {
                tr("思考强度") in it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
            }.minBy { it.boundsInRoot.top }
        }
        fun hasVerticalRollOverflow(): Boolean {
            fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
            val text = descend((view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode).filter {
                tr(effort.value.label) in it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
            }.minBy { it.boundsInRoot.top }.boundsInRoot
            val lineTop = animated().boundsInRoot.center.y - 10f * scale.value
            val lineBottom = animated().boundsInRoot.center.y + 10f * scale.value
            val bitmap = bitmap()
            try {
                return (0 until 60).filter { it < lineTop.toInt() || it >= lineBottom.toInt() }.any { y ->
                    (text.left.toInt().coerceAtLeast(0) until text.right.toInt().coerceAtMost(320)).any { x ->
                        val pixel = bitmap.getPixel(x, y)
                        765 - android.graphics.Color.red(pixel) - android.graphics.Color.green(pixel) - android.graphics.Color.blue(pixel) > 24
                    }
                }
            } finally { bitmap.recycle() }
        }
        fun headerPixels(reference: Boolean = false): IntArray {
            val bitmap = bitmap()
            try {
                return IntArray(320 * 60).also { bitmap.getPixels(it, 0, 320, 0, if (reference) 60 else 0, 320, 60) }
            } finally { bitmap.recycle() }
        }
        fun assertContainsInkColor(expected: Int) {
            val bitmap = bitmap()
            try {
                assertTrue("Max text must use the normal accent", (0 until 320).any { x ->
                    (0 until 60).any { y -> bitmap.getPixel(x, y) == expected }
                })
            } finally { bitmap.recycle() }
        }
        fun assertNoOutsideInk() {
            val bounds = animated().boundsInRoot
            val bitmap = bitmap()
            try {
                val outside = (0 until 320).filter { it < bounds.left.toInt() || it >= bounds.right.toInt() }
                assertTrue("Old glyph ink must not escape the animated text box $bounds", outside.all { x ->
                    (0 until 60).all { y -> bitmap.getPixel(x, y) == android.graphics.Color.WHITE }
                })
            } finally { bitmap.recycle() }
        }
        fun assertMatchesReference(message: String) {
            val bitmap = bitmap()
            try {
                val actual = IntArray(320 * 60)
                val expected = IntArray(actual.size)
                bitmap.getPixels(actual, 0, 320, 0, 0, 320, 60)
                bitmap.getPixels(expected, 0, 320, 0, 60, 320, 60)
                val differences = actual.indices.map { index ->
                    val a = actual[index]
                    val e = expected[index]
                    maxOf(kotlin.math.abs(android.graphics.Color.red(a) - android.graphics.Color.red(e)),
                        kotlin.math.abs(android.graphics.Color.green(a) - android.graphics.Color.green(e)),
                        kotlin.math.abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(e)))
                }
                assertTrue("$message: old trailing glyphs must not survive the transition; max=${differences.max()}, significant=${differences.count { it > 16 }}",
                    differences.all { it <= 16 })
            } finally { bitmap.recycle() }
        }
        override fun close() {
            view.disposeComposition(); recomposer.close(); runner.cancel()
            ThemeStore.saveLanguage(context, oldLanguage)
            activity.pause().stop().destroy()
        }
    }
}
