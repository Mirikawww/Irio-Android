package com.irio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HapticRippleClipTest {
    @Test fun pressedIndicationIsClippedToTheRealNavigationCardCorners() = runTest {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(activity.get())
        var time = 0L
        fun frames() = repeat(12) {
            shadowOf(Looper.getMainLooper()).idle()
            Snapshot.sendApplyNotifications(); advanceTimeBy(16); runCurrent()
            time += 16_000_000L; clock.sendFrame(time); runCurrent()
            view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 360, 300)
            (view.getChildAt(0) as ViewRootForTest).measureAndLayoutForTest()
        }
        fun bitmap(): Bitmap = Bitmap.createBitmap(360, 300, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        try {
            view.setParentCompositionContext(recomposer)
            activity.get().setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme {
                        CompositionLocalProvider(LocalIndication provides SolidPressIndication) {
                            Box(Modifier.fillMaxSize().background(Color.White).padding(24.dp)) { HapticSettingsCard() }
                        }
                    }
                }
            }
            frames()
            fun descend(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::descend)
            val card = descend((view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
                .single { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "haptics-navigation" }.boundsInRoot
            val before = bitmap()
            val down = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, card.center.x, card.center.y, 0)
            try { view.dispatchTouchEvent(event) } finally { event.recycle() }
            frames()
            val pressed = bitmap()
            try {
                val left = card.left.toInt(); val top = card.top.toInt()
                val right = card.right.toInt() - 1; val bottom = card.bottom.toInt() - 1
                assertEquals("The indication must actually draw while pressed", android.graphics.Color.MAGENTA,
                    pressed.getPixel(card.center.x.toInt(), top + 2))
                for ((x, y) in listOf(left to top, right to top, left to bottom, right to bottom)) {
                    assertEquals("Pressed ripple must not fill the rounded-off corner ($x,$y)",
                        before.getPixel(x, y), pressed.getPixel(x, y))
                }
            } finally { before.recycle(); pressed.recycle() }
            val cancel = MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, card.center.x, card.center.y, 0)
            try { view.dispatchTouchEvent(cancel) } finally { cancel.recycle() }
        } finally {
            view.disposeComposition(); recomposer.close(); runner.cancel()
            activity.pause().stop().destroy()
        }
    }

    /** Deterministic press fill exposes the same indication clipping boundary as a ripple. */
    private object SolidPressIndication : IndicationNodeFactory {
        override fun create(interactionSource: InteractionSource): DelegatableNode = PressNode(interactionSource)
        override fun equals(other: Any?) = other === this
        override fun hashCode() = 7142
    }

    private class PressNode(private val interactions: InteractionSource) : Modifier.Node(), DrawModifierNode {
        private var pressed = false
        override fun onAttach() {
            coroutineScope.launch {
                interactions.interactions.collect { interaction ->
                    when (interaction) {
                        is PressInteraction.Press -> pressed = true
                        is PressInteraction.Release, is PressInteraction.Cancel -> pressed = false
                    }
                    invalidateDraw()
                }
            }
        }
        override fun ContentDrawScope.draw() {
            drawContent()
            if (pressed) drawRect(Color.Magenta)
        }
    }
}
