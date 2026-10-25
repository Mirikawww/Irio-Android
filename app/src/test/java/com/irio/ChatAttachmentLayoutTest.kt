package com.irio

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
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
class ChatAttachmentLayoutTest {
    @Test fun pendingAttachmentsKeepBothGuttersInsideTheFullWidthScroll() =
        withList(pending = true, count = 6) { nodes ->
            assertFullWidthScroll(nodes, pending = true)
            assertEquals(6, nodes.count { tr("移除附件") in it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() } })
        }

    @Test fun sentAttachmentsReachBothScreenEdgesWithAndWithoutTextOrAvatar() {
        for (avatar in listOf(false, true)) for (text in listOf("", "message body")) {
            withList(avatar = avatar, text = text, count = 6) { nodes ->
                assertFullWidthScroll(nodes, pending = false, endInset = if (avatar && text.isEmpty()) 36f else 0f)
                assertEquals(if (avatar) 1 else 0, nodes.count {
                    tr("头像") in it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
                })
            }
        }
    }

    @Test fun shortSentListsStayRightAlignedAndKeepTheAvatarGap() {
        for (avatar in listOf(false, true)) withList(avatar = avatar, count = 1) { nodes ->
            val image = images(nodes).single()
            assertEquals(304f - if (avatar) 36f else 0f, image.positionInRoot.x + image.size.width, 0.5f)
            assertEquals(0f, scroll(nodes).config[SemanticsProperties.HorizontalScrollAxisRange].maxValue(), 0.5f)
        }
    }

    @Test fun shortPendingListsStayLeftAligned() = withList(pending = true, count = 1) { nodes ->
        assertEquals(16f, images(nodes).single().positionInRoot.x, 0.5f)
        assertEquals(0f, scroll(nodes).config[SemanticsProperties.HorizontalScrollAxisRange].maxValue(), 0.5f)
    }

    @Test fun sentFilesUseTheSameEdgeToEdgeViewport() = withList(count = 3, image = false) { nodes ->
        val strip = scroll(nodes)
        assertEquals(0f, strip.boundsInRoot.left, 0.5f)
        assertEquals(320f, strip.boundsInRoot.right, 0.5f)
        val cards = nodes.filter { it.config.contains(SemanticsActions.OnClick) }.sortedBy { it.positionInRoot.x }
        assertEquals(3, cards.size)
        assertEquals(304f, cards.last().positionInRoot.x + cards.last().size.width, 0.5f)
    }

    private fun assertFullWidthScroll(nodes: List<SemanticsNode>, pending: Boolean, endInset: Float = 0f) {
        val strip = scroll(nodes)
        assertEquals("Attachment viewport must reach the left screen edge", 0f, strip.boundsInRoot.left, 0.5f)
        assertEquals("Attachment viewport must reach the right screen edge", 320f, strip.boundsInRoot.right, 0.5f)
        val axis = strip.config[SemanticsProperties.HorizontalScrollAxisRange]
        assertTrue(axis.maxValue() > 0f)
        assertEquals(0f, axis.value(), 0.5f)
        val images = images(nodes)
        val firstLeft = images.first().positionInRoot.x
        val lastRight = images.last().positionInRoot.x + images.last().size.width
        if (pending) {
            assertEquals("Pending leading gutter is inside content", 16f, firstLeft, 0.5f)
            assertEquals("Pending trailing gutter includes removal overhang", 292f, lastRight - axis.maxValue(), 0.5f)
        } else {
            assertEquals("Sent attachments start at the right end", 304f - endInset, lastRight, 0.5f)
            assertEquals("Leading gutter remains reachable", 16f, firstLeft + axis.maxValue(), 0.5f)
        }
        val middleLeft = firstLeft + (if (pending) -1f else 1f) * axis.maxValue() / 2f
        val middleRight = lastRight + (if (pending) -1f else 1f) * axis.maxValue() / 2f
        assertTrue("Attachments can cross the left screen edge", middleLeft < 0f)
        assertTrue("Attachments can cross the right screen edge", middleRight > 320f)
    }

    private fun scroll(nodes: List<SemanticsNode>) = nodes.single { it.config.contains(SemanticsProperties.HorizontalScrollAxisRange) }
    private fun images(nodes: List<SemanticsNode>) = nodes.filter {
        it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.any { name -> name.startsWith("photo-") }
    }.sortedBy { it.positionInRoot.x }

    private fun withList(
        pending: Boolean = false,
        avatar: Boolean = false,
        text: String = "",
        count: Int,
        image: Boolean = true,
        check: (List<SemanticsNode>) -> Unit,
    ) = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val context = controller.get()
        ThemeStore.ensureLoaded(context)
        val previousAvatar = ThemeStore.showUserAvatar
        ThemeStore.saveShowUserAvatar(context, avatar)
        val attachments = (1..count).map {
            ChatAttachment(if (image) "photo-$it.png" else "file-$it.txt", if (image) "image/png" else "text/plain", "${context.cacheDir}/fixture-$it")
        }
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(context)
        try {
            view.setParentCompositionContext(recomposer)
            context.setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme {
                        if (pending) {
                            ChatAttachmentList(
                                attachments = attachments,
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                                onRemove = {},
                            )
                        } else {
                            ChatTranscriptRow {
                                Column {
                                    MessageRow(
                                        chatMessage = ChatMessage(1L, text, fromUser = true, attachments = attachments),
                                        reactionAnimations = ReactionBounceTracker(),
                                        markdownScope = this@runTest,
                                        onOpenProcess = {}, onOpenAttachment = {},
                                        onCopy = {}, onQuote = {}, onAddReaction = {}, onRemoveReaction = {},
                                        onRetry = {}, canRetry = false,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            repeat(6) { frame ->
                Snapshot.sendApplyNotifications()
                runCurrent()
                clock.sendFrame((frame + 1) * 16_000_000L)
                runCurrent()
                view.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.AT_MOST))
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            }
            fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
            check(descendants((view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode))
        } finally {
            view.disposeComposition(); recomposer.close(); runner.cancel()
            ThemeStore.saveShowUserAvatar(context, previousAvatar)
            controller.pause().stop().destroy()
        }
    }
}
