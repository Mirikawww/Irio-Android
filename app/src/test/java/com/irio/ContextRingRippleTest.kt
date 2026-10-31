package com.irio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContextRingRippleTest {
    @Test
    fun indicationIsClippedToTheCircularHitAreaWithoutShrinkingTheButton() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(controller.get())
        var expanded = false
        try {
            view.setParentCompositionContext(recomposer)
            controller.get().setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme {
                        CompositionLocalProvider(LocalIndication provides SolidIndication) {
                            Box(Modifier.background(Color.White).padding(24.dp)) {
                                ToolbarCapsule {
                                    ContextUsageRing(
                                        breakdown = ContextUsageBreakdown(0, 0, 0, 100, 0, 0, 100),
                                        compressionCount = 0,
                                        showCompression = false,
                                        compressEnabled = false,
                                        onCompress = {},
                                        expanded = false,
                                        onExpandedChange = { expanded = it },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            var frameTime = 0L
            fun frames() = repeat(8) {
                Snapshot.sendApplyNotifications()
                advanceTimeBy(16L)
                runCurrent()
                frameTime += 16_000_000L
                clock.sendFrame(frameTime)
                runCurrent()
                shadowOf(Looper.getMainLooper()).idle()
                view.requestLayout()
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
                )
                view.layout(0, 0, 120, 100)
            }
            frames()
            fun roots(view: View): List<ViewRootForTest> =
                (if (view is ViewRootForTest) listOf(view) else emptyList()) +
                    (if (view is ViewGroup) (0 until view.childCount).flatMap { roots(view.getChildAt(it)) } else emptyList())
            fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
            val button = roots(view).flatMap { descend(it.semanticsOwner.unmergedRootSemanticsNode) }
                .single { it.config.contains(SemanticsProperties.Role) && it.config[SemanticsProperties.Role] == Role.Button }
            val bounds = button.boundsInRoot
            assertEquals(38f, bounds.width, 0.1f)
            assertEquals(38f, bounds.height, 0.1f)
            val downTime = SystemClock.uptimeMillis()
            fun touch(action: Int) {
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                    bounds.center.x, bounds.center.y, 0)
                try { view.dispatchTouchEvent(event) } finally { event.recycle() }
                frames()
            }
            touch(MotionEvent.ACTION_DOWN)
            val bitmap = Bitmap.createBitmap(120, 100, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                assertEquals("The indication must still draw inside the button", android.graphics.Color.MAGENTA,
                    bitmap.getPixel(bounds.center.x.toInt(), bounds.top.toInt() + 4))
                for ((x, y) in listOf(
                    bounds.left.toInt() + 1 to bounds.top.toInt() + 1,
                    bounds.right.toInt() - 2 to bounds.top.toInt() + 1,
                    bounds.left.toInt() + 1 to bounds.bottom.toInt() - 2,
                    bounds.right.toInt() - 2 to bounds.bottom.toInt() - 2,
                )) {
                    assertEquals("The indication must not paint outside the circular capsule at ($x, $y)",
                        android.graphics.Color.WHITE, bitmap.getPixel(x, y))
                }
            } finally { bitmap.recycle() }
            touch(MotionEvent.ACTION_CANCEL)
            assertTrue(button.config[SemanticsActions.OnClick].action!!.invoke())
            assertTrue("The ring must still request its usage menu on click", expanded)
        } finally {
            view.disposeComposition()
            recomposer.close()
            runner.cancel()
            controller.pause().stop().destroy()
        }
    }

    // Fill the real clickable's indication bounds deterministically; platform ripples use
    // hardware drawing/timing that is not reliable on Robolectric's software Canvas.
    private data object SolidIndication : IndicationNodeFactory {
        override fun create(interactionSource: InteractionSource): DelegatableNode = object : Modifier.Node(), DrawModifierNode {
            override fun ContentDrawScope.draw() {
                drawContent()
                drawRect(Color.Magenta)
            }
        }
    }
}
