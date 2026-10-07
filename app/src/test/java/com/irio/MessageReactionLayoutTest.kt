package com.irio

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class MessageReactionLayoutTest {
    @Test
    fun addingReplacingAndRemovingReactionsKeepsBubbleAndFooterPositions() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(controller.get())
        val emoji = mutableStateOf("")
        val bounce = mutableStateOf(1f)
        var bubble = Rect.Zero
        var footer = Rect.Zero
        var followingAction = Rect.Zero
        var row = Rect.Zero
        try {
            view.setParentCompositionContext(recomposer)
            controller.get().setContentView(view)
            for ((fontScale, bubbleSize) in listOf(1f, 1.5f, 2f).flatMap { scale ->
                listOf(IntSize(100, 44), IntSize(10, 10)).map { scale to it }
            }) {
                emoji.value = ""
                view.setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                        AppTheme {
                            androidx.compose.foundation.layout.Column {
                                UserBubbleReaction(emoji.value, Color.Gray, bounce) {
                                    Box(Modifier.size(bubbleSize.width.dp, bubbleSize.height.dp).onGloballyPositioned { bubble = it.boundsInRoot() })
                                }
                                Row(
                                    Modifier.onGloballyPositioned { row = it.boundsInRoot() },
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                ) {
                                    Box(Modifier.onGloballyPositioned { footer = it.boundsInRoot() }) {
                                        AssistantReactionButton(emoji.value, bounce, {}, {})
                                    }
                                    Box(Modifier.size(18.dp).onGloballyPositioned { followingAction = it.boundsInRoot() })
                                }
                            }
                        }
                    }
                }
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                fun measure() {
                    view.requestLayout()
                    view.measure(
                        View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.AT_MOST),
                    )
                    view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                }
                runCurrent()
                clock.sendFrame(0L)
                runCurrent()
                measure()
                val before = listOf(bubble, footer, followingAction, row)
                val originalBottom = bubbleSize.height.toFloat()
                assertEquals("No reaction padding may change the original bubble position", Rect(0f, 0f, bubbleSize.width.toFloat(), originalBottom), bubble)
                assertEquals("The reaction control must retain the original 18dp icon slot", Rect(0f, originalBottom, 18f, originalBottom + 18f), footer)
                assertEquals("The following action must retain its original position", Rect(18f, originalBottom, 36f, originalBottom + 18f), followingAction)
                for ((index, reaction) in listOf("❤️", "👍", "👨‍👩‍👧‍👦", "").withIndex()) {
                    emoji.value = reaction
                    bounce.value = 1f
                    androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                    runCurrent()
                    clock.sendFrame((index + 1) * 16_000_000L)
                    runCurrent()
                    measure()
                    assertEquals("Reaction '$reaction' must not move content at font scale $fontScale", before, listOf(bubble, footer, followingAction, row))
                    if (reaction.isNotEmpty()) {
                        val root = (view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode
                        val modelGlyph = textNodes(root).first()
                        assertEquals("The model glyph must stay centered inside the 32dp badge", 10f, modelGlyph.positionInRoot.x + modelGlyph.size.width / 2f, 0.5f)
                        assertEquals("The model glyph must stay centered inside the 32dp badge", -10f, modelGlyph.positionInRoot.y + modelGlyph.size.height / 2f, 0.5f)
                    }
                    bounce.value = 1.16f
                    measure()
                    assertEquals("A reaction scale pulse must not change the original layout", before, listOf(bubble, footer, followingAction, row))
                }
            }
        } finally {
            view.disposeComposition()
            recomposer.close()
            runner.cancel()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun footerEmojiDrawsItsFullScaledLineBoxOutsideTheOriginal18dpSlot() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(activity)
        var measured = IntSize.Zero
        var minimumLineBoxHeight = 0f
        try {
            view.setParentCompositionContext(recomposer)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 1.5f)) {
                    AppTheme {
                        minimumLineBoxHeight = with(LocalDensity.current) { 24.sp.toPx() }
                        Row {
                            Box(Modifier.onSizeChanged { measured = it }) {
                                AssistantReactionButton("❤️", mutableStateOf(1f), {}, {})
                            }
                        }
                    }
                }
            }
            activity.setContentView(view)
            runCurrent()
            clock.sendFrame(0L)
            runCurrent()
            view.measure(
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.AT_MOST),
            )
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            assertEquals("The footer must retain the original icon's layout size", IntSize(18, 18), measured)
            val root = (view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode
            val glyph = textNodes(root).single()
            assertTrue("The actual text must measure its full line box, not be constrained to 18dp", glyph.size.height >= minimumLineBoxHeight)
            assertEquals("The overflowing glyph must stay horizontally centered", 9f, glyph.positionInRoot.x + glyph.size.width / 2f, 0.5f)
            assertEquals("The overflowing glyph must stay vertically centered", 9f, glyph.positionInRoot.y + glyph.size.height / 2f, 0.5f)
        } finally {
            view.disposeComposition()
            recomposer.close()
            runner.cancel()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun recyclerItemsDoNotClipTheOverlaidReaction() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val list = MessageRecyclerView(controller.get())
            assertFalse("RecyclerView must allow the badge above the item", list.clipChildren)
            val item = list.adapter!!.createViewHolder(list, 0).itemView as ComposeView
            assertFalse("ComposeView must allow glyph and badge overflow", item.clipChildren)
            assertFalse(item.clipToPadding)
            item.disposeComposition()
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun textNodes(node: SemanticsNode): List<SemanticsNode> =
        (if (node.config.contains(SemanticsProperties.Text)) listOf(node) else emptyList()) +
            node.children.flatMap(::textNodes)
}
