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
class UserBubbleRippleTest {
    @Test fun ordinaryBubbleClipsItsIndicationAndRetainsEditing() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val context = controller.get()
        ThemeStore.ensureLoaded(context)
        val oldGlass = GlassSurface.USER_BUBBLE in ThemeStore.glassSurfaces
        ThemeStore.saveGlassSurface(context, GlassSurface.USER_BUBBLE, false)
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(context)
        var edits = 0
        try {
            view.setParentCompositionContext(recomposer)
            context.setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme {
                        CompositionLocalProvider(LocalIndication provides SolidIndication) {
                            Box(Modifier.background(Color.White).padding(24.dp)) {
                                MessageRow(
                                    chatMessage = ChatMessage(1L, "User bubble\nSecond line", fromUser = true),
                                    reactionAnimations = ReactionBounceTracker(),
                                    markdownScope = this@runTest,
                                    onOpenProcess = {}, onOpenAttachment = {},
                                    onCopy = {}, onQuote = {}, onAddReaction = {}, onRemoveReaction = {},
                                    onRetry = {}, canRetry = false, onEdit = { edits++ },
                                )
                            }
                        }
                    }
                }
            }
            var time = 0L
            fun frames() = repeat(8) {
                Snapshot.sendApplyNotifications()
                advanceTimeBy(16L)
                runCurrent()
                time += 16_000_000L
                clock.sendFrame(time)
                runCurrent()
                shadowOf(Looper.getMainLooper()).idle()
                view.requestLayout()
                view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, 360, 240)
            }
            frames()
            fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
            val bubble = descend((view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
                .single { it.config.contains(SemanticsActions.OnClick) &&
                    it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { text -> text.text == "User bubble\nSecond line" } }
            val bounds = bubble.boundsInRoot
            assertTrue("Exercise a multiline bubble", bounds.height > 60f)
            val downTime = SystemClock.uptimeMillis()
            fun touch(action: Int) {
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                    bounds.center.x, bounds.center.y, 0)
                try { view.dispatchTouchEvent(event) } finally { event.recycle() }
                frames()
            }
            touch(MotionEvent.ACTION_DOWN)
            val bitmap = Bitmap.createBitmap(360, 240, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                assertEquals("Do not remove the ripple to hide overflow", android.graphics.Color.MAGENTA,
                    bitmap.getPixel(bounds.center.x.toInt(), bounds.center.y.toInt()))
                for ((x, y) in listOf(
                    bounds.left.toInt() + 1 to bounds.top.toInt() + 1,
                    bounds.right.toInt() - 2 to bounds.top.toInt() + 1,
                    bounds.left.toInt() + 1 to bounds.bottom.toInt() - 2,
                    bounds.right.toInt() - 2 to bounds.bottom.toInt() - 2,
                )) {
                    assertEquals("Bubble indication must stay inside the rounded outline at ($x, $y)",
                        android.graphics.Color.WHITE, bitmap.getPixel(x, y))
                }
            } finally { bitmap.recycle() }
            touch(MotionEvent.ACTION_CANCEL)
            assertTrue(bubble.config[SemanticsActions.OnClick].action!!.invoke())
            assertEquals("Tapping the bubble must still edit the message", 1, edits)
        } finally {
            view.disposeComposition()
            recomposer.close()
            runner.cancel()
            ThemeStore.saveGlassSurface(context, GlassSurface.USER_BUBBLE, oldGlass)
            controller.pause().stop().destroy()
        }
    }

    // Exercise the real clickable's draw boundary without hardware ripple timing.
    private data object SolidIndication : IndicationNodeFactory {
        override fun create(interactionSource: InteractionSource): DelegatableNode = object : Modifier.Node(), DrawModifierNode {
            override fun ContentDrawScope.draw() {
                drawContent()
                drawRect(Color.Magenta)
            }
        }
    }
}
