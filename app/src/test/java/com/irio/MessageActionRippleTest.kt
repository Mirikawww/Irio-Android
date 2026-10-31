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
import androidx.compose.runtime.mutableStateOf
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
import org.junit.Assert.*
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
class MessageActionRippleTest {
    @Test fun footerActionsHaveLargerCircularFeedbackWithoutChangingIconPitch() = runTest {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(activity.get())
        val clicks = mutableListOf<String>()
        val retryEnabled = mutableStateOf(true)
        var time = 0L
        fun frames() = repeat(8) {
            Snapshot.sendApplyNotifications(); advanceTimeBy(16); runCurrent()
            time += 16_000_000L; clock.sendFrame(time); runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
            view.requestLayout()
            view.measure(View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 500, 400)
        }
        try {
            view.setParentCompositionContext(recomposer)
            activity.get().setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme {
                        CompositionLocalProvider(LocalIndication provides SolidIndication) {
                            Box(Modifier.background(Color.White).padding(24.dp)) {
                                MessageRow(
                                    ChatMessage(1L, "Response", false), ReactionBounceTracker(), markdownScope = this@runTest,
                                    onOpenProcess = {}, onOpenAttachment = {}, onCopy = { clicks += "copy" }, onQuote = {},
                                    onAddReaction = { clicks += "reaction" }, onRemoveReaction = {},
                                    onRetry = { clicks += "retry" }, canRetry = true,
                                    retryEnabled = retryEnabled.value,
                                    versionIndex = 2, versionCount = 3,
                                    onPrevVersion = { clicks += "previous" }, onNextVersion = { clicks += "next" },
                                )
                            }
                        }
                    }
                }
            }
            frames()
            fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
            val nodes = descend((view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
            val labels = listOf(tr("复制"), tr("重试"), tr("朗读"), "Add Reaction", tr("上一个回复"), tr("下一个回复"))
            val buttons = labels.map { label -> nodes.single {
                label in it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
            } }
            for (index in 1..3) assertEquals("Existing icon centers must retain their spacing", 34f,
                buttons[index].boundsInRoot.center.x - buttons[index - 1].boundsInRoot.center.x, 0.5f)
            buttons.forEachIndexed { index, button ->
                val bounds = button.boundsInRoot
                assertEquals("${labels[index]} feedback must be wider than its 18dp icon", 32f, bounds.width, 0.1f)
                assertEquals(32f, bounds.height, 0.1f)
                val downTime = SystemClock.uptimeMillis()
                fun touch(action: Int) {
                    val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                        bounds.center.x, bounds.center.y, 0)
                    try { view.dispatchTouchEvent(event) } finally { event.recycle() }
                    frames()
                }
                touch(MotionEvent.ACTION_DOWN)
                val bitmap = Bitmap.createBitmap(500, 400, Bitmap.Config.ARGB_8888)
                try {
                    view.draw(Canvas(bitmap))
                    assertEquals("The larger feedback must draw beyond the icon", android.graphics.Color.MAGENTA,
                        bitmap.getPixel(bounds.center.x.toInt(), bounds.top.toInt() + 2))
                    assertEquals("The feedback must be circular, not rectangular", android.graphics.Color.WHITE,
                        bitmap.getPixel(bounds.left.toInt() + 1, bounds.top.toInt() + 1))
                } finally { bitmap.recycle() }
                touch(MotionEvent.ACTION_CANCEL)
                if (index != 2) assertTrue(button.config[SemanticsActions.OnClick].action!!.invoke())
            }
            assertEquals(listOf("copy", "retry", "reaction", "previous", "next"), clicks)
            retryEnabled.value = false
            frames()
            val disabledRetry = descend((view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
                .single { tr("重试") in it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() } }
            assertTrue(disabledRetry.config.contains(SemanticsProperties.Disabled))
            val center = disabledRetry.boundsInRoot.center
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, center.x, center.y, 0)
                try { view.dispatchTouchEvent(event) } finally { event.recycle() }
                frames()
            }
            assertEquals("Disabled retry must remain inert", listOf("copy", "retry", "reaction", "previous", "next"), clicks)
        } finally {
            view.disposeComposition(); recomposer.close(); runner.cancel()
            activity.pause().stop().destroy()
        }
    }

    private data object SolidIndication : IndicationNodeFactory {
        override fun create(interactionSource: InteractionSource): DelegatableNode = object : Modifier.Node(), DrawModifierNode {
            override fun ContentDrawScope.draw() { drawContent(); drawRect(Color.Magenta) }
        }
    }
}
