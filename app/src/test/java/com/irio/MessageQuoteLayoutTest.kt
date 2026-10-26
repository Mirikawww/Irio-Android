package com.irio

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
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
class MessageQuoteLayoutTest {
    @Test fun sentQuotesHugTheirContentWithOrWithoutBodyAttachmentsAndAvatar() {
        for (avatar in listOf(false, true)) {
            for (body in listOf("", "message body")) {
                for (attachment in listOf(false, true)) {
                    val short = layout("Hi", avatar, body, attachment)
                    val longer = layout("A longer quoted excerpt", avatar, body, attachment)
                    assertTrue("Short sent quotes must not fill the width limit", short.left > longer.left + 20f)
                    val expectedRight = 320f - if (avatar && body.isEmpty() && !attachment) 36f else 0f
                    assertEquals(expectedRight, short.right, 0.5f)
                    assertEquals(expectedRight, longer.right, 0.5f)
                }
            }
        }
    }

    @Test fun longSentQuotesStayWithinTheExistingWidthAndTwoLineLimit() {
        for (avatar in listOf(false, true)) {
            val bounds = layout("A long excerpt that wraps across many lines. ".repeat(20), avatar)
            assertEquals(272f - if (avatar) 36f else 0f, bounds.width, 0.5f)
            assertTrue("The quote preview must retain its two-line height limit", bounds.height < 100f)
        }
    }

    @Test fun composerPreviewStillFillsTheWidthAndKeepsItsCancelAction() {
        val short = layout("Hi", composer = true)
        val longer = layout("A longer quoted excerpt", composer = true)
        assertEquals(320f, short.width, 0.5f)
        assertEquals(short, longer)
    }

    private fun layout(
        quote: String,
        avatar: Boolean = false,
        body: String = "",
        attachment: Boolean = false,
        composer: Boolean = false,
    ): Rect {
        var result = Rect.Zero
        runTest {
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            val context = controller.get()
            ThemeStore.ensureLoaded(context)
            val previousAvatar = ThemeStore.showUserAvatar
            ThemeStore.saveShowUserAvatar(context, avatar)
            val clock = BroadcastFrameClock()
            val recomposer = Recomposer(coroutineContext + clock)
            val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
            val view = ComposeView(context)
            var previewBounds = Rect.Zero
            try {
                view.setParentCompositionContext(recomposer)
                context.setContentView(view)
                view.setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f)) {
                        AppTheme {
                            Column(Modifier.fillMaxWidth()) {
                                if (composer) {
                                    MessageQuotePreview(
                                        quote,
                                        modifier = Modifier.onGloballyPositioned { previewBounds = it.boundsInRoot() },
                                        onCancel = {},
                                    )
                                } else {
                                    MessageRow(
                                        chatMessage = ChatMessage(
                                            1L, body, fromUser = true, quote = quote,
                                            attachments = if (attachment) listOf(
                                                ChatAttachment("fixture.txt", "text/plain", "${context.cacheDir}/fixture.txt")
                                            ) else emptyList(),
                                        ),
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
                repeat(6) { frame ->
                    Snapshot.sendApplyNotifications()
                    runCurrent()
                    clock.sendFrame((frame + 1) * 16_000_000L)
                    runCurrent()
                    view.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.AT_MOST))
                    view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                }
                fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
                val nodes = descendants((view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
                result = if (composer) {
                    assertEquals(1, nodes.count { tr("取消引用") in it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() } })
                    previewBounds
                } else {
                    val text = nodes.single { it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { text -> text.text == quote } }
                    // Text -> quote Column -> preview Row, whose inner coordinates exclude its padding.
                    val content = text.layoutInfo.parentInfo!!.parentInfo!!.coordinates.boundsInRoot()
                    Rect(content.left - 12f, content.top - 8f, content.right + 4f, content.bottom + 8f)
                }
            } finally {
                view.disposeComposition()
                recomposer.close()
                runner.cancel()
                ThemeStore.saveShowUserAvatar(context, previousAvatar)
                controller.pause().stop().destroy()
            }
        }
        return result
    }
}
