package com.irio

import android.view.View
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.composables.ui.theme.colors
import com.composables.ui.theme.panelColor
import com.composables.ui.theme.primaryColor
import com.composeunstyled.theme.Theme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class ScrollToBottomButtonTest {
    @Test fun clickIndicationIsClippedToTheButtonWithoutRemovingTheClick() = runTest {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(activity.get())
        var clicked = 0
        var time = 0L
        fun frames() = repeat(8) {
            Snapshot.sendApplyNotifications()
            advanceTimeBy(16L)
            runCurrent()
            time += 16_000_000L
            clock.sendFrame(time)
            runCurrent()
            org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
            view.requestLayout()
            view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 360, 800)
        }
        try {
            view.setParentCompositionContext(recomposer)
            activity.get().setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme {
                        CompositionLocalProvider(LocalIndication provides SolidIndication) {
                            Box(Modifier.fillMaxSize().background(Color.White)) {
                                ScrollToBottomButton(
                                    visible = true, followingBottom = false, responding = false,
                                    bottomInset = 100.dp, onClick = { clicked++ },
                                )
                            }
                        }
                    }
                }
            }
            frames()
            fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
            val button = descend((view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
                .single { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "scroll-to-bottom" }
            val bounds = button.boundsInRoot
            assertEquals(44f, bounds.width, 0.1f)
            assertEquals(44f, bounds.height, 0.1f)
            val downTime = SystemClock.uptimeMillis()
            fun touch(action: Int) {
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                    bounds.center.x, bounds.center.y, 0)
                try { view.dispatchTouchEvent(event) } finally { event.recycle() }
                frames()
            }
            touch(MotionEvent.ACTION_DOWN)
            val bitmap = android.graphics.Bitmap.createBitmap(360, 800, android.graphics.Bitmap.Config.ARGB_8888)
            try {
                view.draw(android.graphics.Canvas(bitmap))
                assertEquals("Click feedback must still draw inside the button", android.graphics.Color.MAGENTA,
                    bitmap.getPixel(bounds.center.x.toInt(), bounds.top.toInt() + 4))
                for ((x, y) in listOf(
                    bounds.left.toInt() + 1 to bounds.top.toInt() + 1,
                    bounds.right.toInt() - 2 to bounds.top.toInt() + 1,
                    bounds.left.toInt() + 1 to bounds.bottom.toInt() - 2,
                    bounds.right.toInt() - 2 to bounds.bottom.toInt() - 2,
                )) {
                    assertEquals("Click feedback must not escape the circular button at ($x, $y)",
                        android.graphics.Color.WHITE, bitmap.getPixel(x, y))
                }
            } finally { bitmap.recycle() }
            touch(MotionEvent.ACTION_UP)
            assertEquals("A tap must still request scrolling to the bottom", 1, clicked)
        } finally {
            view.disposeComposition(); recomposer.close(); runner.cancel()
            activity.pause().stop().destroy()
        }
    }

    // Fill the real clickable's draw boundary without platform ripple timing.
    private data object SolidIndication : IndicationNodeFactory {
        override fun create(interactionSource: InteractionSource): DelegatableNode = object : Modifier.Node(), DrawModifierNode {
            override fun ContentDrawScope.draw() {
                drawContent()
                drawRect(Color.Magenta)
            }
        }
    }

    @Test fun buttonMovesAndFadesAtBottomRightHidesWhileScrollingAndHasOneResponseRing() = runTest {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(activity.get())
        val pinned = mutableStateOf(true)
        val followingBottom = mutableStateOf(false)
        val scrolling = mutableStateOf(false)
        val responding = mutableStateOf(false)
        var clicked = 0
        var time = 0L
        var buttonAlpha = 0
        var panel = 0
        var accent = 0
        fun frame() {
            Snapshot.sendApplyNotifications(); runCurrent()
            time += 16_000_000L; clock.sendFrame(time); runCurrent()
            view.requestLayout()
            view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 360, 800)
            val bitmap = android.graphics.Bitmap.createBitmap(360, 800, android.graphics.Bitmap.Config.ARGB_8888)
            try {
                view.draw(android.graphics.Canvas(bitmap))
                buttonAlpha = android.graphics.Color.alpha(bitmap.getPixel(322, 680))
            } finally { bitmap.recycle() }
        }
        fun nodes(): List<SemanticsNode> {
            fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
            return descend((view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
        }
        fun node(tag: String) = nodes().singleOrNull { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == tag }
        try {
            view.setParentCompositionContext(recomposer)
            view.setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) { AppTheme {
                panel = Theme[colors][panelColor].toArgb()
                accent = Theme[colors][primaryColor].toArgb()
                Box(Modifier.fillMaxSize()) {
                    ScrollToBottomButton(
                        visible = !pinned.value && !scrolling.value,
                        followingBottom = followingBottom.value,
                        responding = responding.value,
                        bottomInset = 100.dp,
                        onClick = { clicked++; pinned.value = true },
                    )
                }
            } } }
            activity.get().setContentView(view)
            repeat(4) { frame() }
            assertNull(node("scroll-to-bottom"))
            pinned.value = false
            val alpha = mutableListOf<Int>()
            val tops = List(40) { frame(); alpha += buttonAlpha; node("scroll-to-bottom")?.boundsInRoot?.top }
            val shown = node("scroll-to-bottom")!!
            assertEquals(344f, shown.boundsInRoot.right, 1f)
            assertEquals(688f, shown.boundsInRoot.bottom, 1f)
            assertTrue("Entrance must contain a small translation, not just appear", tops.filterNotNull().distinct().size > 2)
            assertTrue("Entrance must fade through intermediate opacity", alpha.any { it in 1..254 })
            assertNull(node("scroll-to-bottom-ripple"))
            val neutral = android.graphics.Bitmap.createBitmap(360, 800, android.graphics.Bitmap.Config.ARGB_8888)
            try {
                view.draw(android.graphics.Canvas(neutral))
                assertNotEquals("The panel surface must differ from the accent ring", accent, panel)
                assertEquals("Button background must not be filled with the accent", panel, neutral.getPixel(322, 651))
                fun distance(a: Int, b: Int): Int =
                    kotlin.math.abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)) +
                    kotlin.math.abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)) +
                    kotlin.math.abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b))
                val glyph = (312..332).flatMap { x -> (656..676).mapNotNull { y ->
                    if (distance(neutral.getPixel(x, y), panel) > 70) x to y else null
                } }
                assertTrue("Chevron must have visible ink", glyph.isNotEmpty())
                assertEquals("Icon's horizontal ink must be centered", 322f,
                    (glyph.minOf { it.first } + glyph.maxOf { it.first }) / 2f, 1f)
                assertEquals("Icon's vertical ink must be centered", 666f,
                    (glyph.minOf { it.second } + glyph.maxOf { it.second }) / 2f, 1f)
            } finally { neutral.recycle() }
            responding.value = true
            repeat(4) { frame() }
            assertEquals("Only one wave is allowed around this button", 1, ScrollToBottomRippleCount)
            assertNotNull(node("scroll-to-bottom-ripple"))
            repeat(24) { frame() }
            val capture = android.graphics.Bitmap.createBitmap(360, 800, android.graphics.Bitmap.Config.ARGB_8888)
            try {
                view.draw(android.graphics.Canvas(capture))
                assertTrue("The single wave must actually draw beyond the circular button, not be clipped",
                    (345..355).any { x -> (658..674).any { y -> android.graphics.Color.alpha(capture.getPixel(x, y)) > 0 } })
            } finally { capture.recycle() }
            assertEquals(0f, generatingRippleAlpha(0f), 0f)
            assertEquals(0f, generatingRippleAlpha(1f), 0f)
            followingBottom.value = true
            repeat(40) { frame() }
            assertNull("Automatic bottom following hides the button even while the viewport is behind", node("scroll-to-bottom"))
            // Slow streaming can leave long IDLE gaps between layout growth and catch-up.
            repeat(3) {
                pinned.value = true
                repeat(20) { frame() }
                pinned.value = false
                repeat(40) { frame() }
                assertNull("Streaming growth must not restart the button entrance", node("scroll-to-bottom"))
                assertNull(node("scroll-to-bottom-ripple"))
            }
            followingBottom.value = false
            repeat(40) { frame() }
            assertNotNull("Reading history during a response must still expose the return button", node("scroll-to-bottom"))
            assertNotNull(node("scroll-to-bottom-ripple"))
            scrolling.value = true
            val exitAlpha = List(40) { frame(); buttonAlpha }
            assertTrue("Hiding during scrolling must fade out", exitAlpha.any { it in 1..254 })
            assertNull("Scrolling hides both the button and its wave", node("scroll-to-bottom"))
            assertNull(node("scroll-to-bottom-ripple"))
            scrolling.value = false
            repeat(40) { frame() }
            node("scroll-to-bottom")!!.config[SemanticsActions.OnClick].action!!.invoke()
            assertEquals(1, clicked)
            repeat(40) { frame() }
            assertNull("Reaching the bottom removes the button", node("scroll-to-bottom"))
        } finally {
            view.disposeComposition(); recomposer.close(); runner.cancel()
            activity.pause().stop().destroy()
        }
    }
}
