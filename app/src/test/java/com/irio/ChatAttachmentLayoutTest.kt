package com.irio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.composables.ui.theme.colors
import com.composables.ui.theme.fieldColor
import com.composeunstyled.theme.Theme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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
@Config(sdk = [35], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatAttachmentLayoutTest {
    @Test fun pendingAttachmentsUseAnInsetScrollableStripWithInlineRemoveButtons() =
        withList(pending = true, count = 6) { nodes ->
            val strip = scroll(nodes)
            assertEquals(16f, strip.boundsInRoot.left, 0.5f)
            assertEquals(304f, strip.boundsInRoot.right, 0.5f)
            assertEquals(40f, strip.boundsInRoot.height, 0.5f)
            assertTrue(strip.config[SemanticsProperties.HorizontalScrollAxisRange].maxValue() > 0f)
            assertEquals(6, removeButtons(nodes).size)
            assertEquals(6, names(nodes).size)
            assertTrue("Use the full filename including the extension", names(nodes).all { it.endsWith(".png") })
        }

    @Test fun sentAttachmentsReachBothScreenEdgesWithAndWithoutTextOrAvatar() {
        for (avatar in listOf(false, true)) for (text in listOf("", "message body")) {
            withList(avatar = avatar, text = text, count = 6) { nodes ->
                assertFullWidthScroll(nodes, endInset = if (avatar && text.isEmpty()) 36f else 0f)
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
        val name = nodes.single { it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { text -> text.text == "photo-1.png" } }
        assertEquals(60f, name.boundsInRoot.left, 0.5f)
        assertEquals(0f, scroll(nodes).config[SemanticsProperties.HorizontalScrollAxisRange].maxValue(), 0.5f)
    }

    @Test fun pendingTrayBackgroundSeparatesItsChipsFromTheEditor() =
        withList(pending = true, count = 1, check = {}, checkPixels = { nodes, bitmap ->
            val strip = scroll(nodes).boundsInRoot
            val field = editor(nodes).boundsInRoot
            val chipFill = bitmap.getPixel(42, strip.top.toInt() + 5)
            val trayFill = bitmap.getPixel(42, strip.bottom.toInt() - 2)
            val fieldFill = bitmap.getPixel(42, field.center.y.toInt())
            assertTrue("The chip must sit on its own surface", chipFill != trayFill)
            assertTrue("The field must sit on its own surface", fieldFill != trayFill)
        })

    @Test fun removeFeedbackFitsInASmallCircleWithinTheFullClickTarget() =
        withList(pending = true, count = 1, check = {}, indication = SolidIndication, checkPixels = { nodes, bitmap ->
            val button = removeButtons(nodes).single().boundsInRoot
            assertEquals(44f, button.width, 0.5f)
            assertEquals(36f, button.height, 0.5f)
            val x = button.center.x.toInt()
            val y = button.center.y.toInt()
            val magenta = android.graphics.Color.MAGENTA
            assertEquals("Indication is rendered", magenta, bitmap.getPixel(x, y - 10))
            assertNotEquals("Feedback must stop before the top edge", magenta, bitmap.getPixel(x, y - 15))
            assertNotEquals("Feedback must stop before the right edge", magenta, bitmap.getPixel(x + 16, y))
            assertNotEquals("The feedback surface must be round", magenta, bitmap.getPixel(x + 11, y - 11))
        })

    private data object SolidIndication : IndicationNodeFactory {
        override fun create(interactionSource: InteractionSource): DelegatableNode = object : Modifier.Node(), DrawModifierNode {
            override fun ContentDrawScope.draw() {
                drawContent()
                drawRect(Color.Magenta)
            }
        }
    }

    @Test fun firstAttachmentExpandsUpwardAndLastRemovalCollapsesWithoutMovingTheEditor() =
        withList(pending = true, count = 2, check = {}, animate = { fixtures, draft, frame, nodes ->
            val baseline = editor(nodes()).boundsInRoot
            val collapsedTray = tagged(nodes(), "tray").boundsInRoot
            assertFalse(nodes().any { it.config.contains(SemanticsProperties.HorizontalScrollAxisRange) })

            draft.value = fixtures.take(1)
            frame(4)
            val opening = tagged(nodes(), "tray").boundsInRoot.height
            frame(16)
            val expandedTray = tagged(nodes(), "tray").boundsInRoot
            val openStrip = scroll(nodes()).boundsInRoot
            assertTrue("Expected an intermediate height: ${collapsedTray.height} -> $opening -> ${expandedTray.height}",
                collapsedTray.height < opening && opening < expandedTray.height)
            assertTrue(expandedTray.top < collapsedTray.top)
            assertEquals(collapsedTray.bottom, expandedTray.bottom, 0.5f)
            assertTrue(openStrip.bottom <= baseline.top)
            assertEquals("The editor must stay anchored", baseline, editor(nodes()).boundsInRoot)

            draft.value = fixtures
            frame(2)
            assertEquals(openStrip, scroll(nodes()).boundsInRoot)
            removeButtons(nodes()).first().config[SemanticsActions.OnClick].action!!.invoke()
            frame(2)
            assertEquals(openStrip, scroll(nodes()).boundsInRoot)
            assertEquals(listOf(fixtures.last().name), names(nodes()))

            removeButtons(nodes()).single().config[SemanticsActions.OnClick].action!!.invoke()
            frame(4)
            val closing = tagged(nodes(), "tray").boundsInRoot.height
            assertTrue("Expected an intermediate height: ${expandedTray.height} -> $closing -> ${collapsedTray.height}",
                collapsedTray.height < closing && closing < expandedTray.height)
            assertEquals(listOf(fixtures.last().name), names(nodes()))
            assertTrue(draft.value.isEmpty())
            assertEquals(baseline, editor(nodes()).boundsInRoot)
            frame(16)
            assertFalse(nodes().any { it.config.contains(SemanticsProperties.HorizontalScrollAxisRange) })
            assertEquals(collapsedTray, tagged(nodes(), "tray").boundsInRoot)
            assertEquals(baseline, editor(nodes()).boundsInRoot)
        })

    @Test fun sentFilesUseTheSameEdgeToEdgeViewport() = withList(count = 3, image = false) { nodes ->
        val strip = scroll(nodes)
        assertEquals(0f, strip.boundsInRoot.left, 0.5f)
        assertEquals(320f, strip.boundsInRoot.right, 0.5f)
        val cards = nodes.filter { it.config.contains(SemanticsActions.OnClick) }.sortedBy { it.positionInRoot.x }
        assertEquals(3, cards.size)
        assertEquals(304f, cards.last().positionInRoot.x + cards.last().size.width, 0.5f)
    }

    private fun assertFullWidthScroll(nodes: List<SemanticsNode>, endInset: Float = 0f) {
        val strip = scroll(nodes)
        assertEquals("Attachment viewport must reach the left screen edge", 0f, strip.boundsInRoot.left, 0.5f)
        assertEquals("Attachment viewport must reach the right screen edge", 320f, strip.boundsInRoot.right, 0.5f)
        val axis = strip.config[SemanticsProperties.HorizontalScrollAxisRange]
        assertTrue(axis.maxValue() > 0f)
        assertEquals(0f, axis.value(), 0.5f)
        val images = images(nodes)
        val firstLeft = images.first().positionInRoot.x
        val lastRight = images.last().positionInRoot.x + images.last().size.width
        assertEquals("Sent attachments start at the right end", 304f - endInset, lastRight, 0.5f)
        assertEquals("Leading gutter remains reachable", 16f, firstLeft + axis.maxValue(), 0.5f)
        val middleLeft = firstLeft + axis.maxValue() / 2f
        val middleRight = lastRight + axis.maxValue() / 2f
        assertTrue("Attachments can cross the left screen edge", middleLeft < 0f)
        assertTrue("Attachments can cross the right screen edge", middleRight > 320f)
    }

    private fun scroll(nodes: List<SemanticsNode>) = nodes.single { it.config.contains(SemanticsProperties.HorizontalScrollAxisRange) }
    private fun editor(nodes: List<SemanticsNode>) = tagged(nodes, "editor")
    private fun tagged(nodes: List<SemanticsNode>, tag: String) = nodes.single {
        it.config.getOrElse(SemanticsProperties.TestTag) { "" } == tag
    }
    private fun removeButtons(nodes: List<SemanticsNode>) = nodes.filter {
        tr("移除附件") in it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
    }
    private fun names(nodes: List<SemanticsNode>) = nodes.flatMap {
        it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { name -> name.text }
    }.filter { it.startsWith("photo-") }
    private fun images(nodes: List<SemanticsNode>) = nodes.filter {
        it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.any { name -> name.startsWith("photo-") }
    }.sortedBy { it.positionInRoot.x }

    private fun withList(
        pending: Boolean = false,
        avatar: Boolean = false,
        text: String = "",
        count: Int,
        image: Boolean = true,
        animate: ((List<ChatAttachment>, MutableState<List<ChatAttachment>>, (Int) -> Unit, () -> List<SemanticsNode>) -> Unit)? = null,
        checkPixels: ((List<SemanticsNode>, Bitmap) -> Unit)? = null,
        indication: IndicationNodeFactory? = null,
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
        val draft = mutableStateOf(if (animate != null) emptyList() else attachments)
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
                            CompositionLocalProvider(LocalIndication provides (indication ?: LocalIndication.current)) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                                    ComposerAttachmentTray(
                                        attachments = draft.value,
                                        loadingFiles = emptySet(),
                                        expansion = remember { mutableStateOf(0f) },
                                        expandedLayout = false,
                                        onRemove = { attachment -> draft.value = draft.value - attachment },
                                        modifier = Modifier.testTag("tray"),
                                    ) {
                                        Box(Modifier.fillMaxWidth().height(64.dp).background(Theme[colors][fieldColor]).testTag("editor"))
                                    }
                                }
                            }
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
            var nanos = 0L
            fun frame(times: Int) = repeat(times) {
                Snapshot.sendApplyNotifications()
                runCurrent()
                nanos += 16_000_000L
                clock.sendFrame(nanos)
                runCurrent()
                if (animate != null) {
                    shadowOf(Looper.getMainLooper()).idle()
                    view.requestLayout()
                }
                view.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.AT_MOST))
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            }
            frame(6)
            fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
            fun nodes() = descendants((view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
            animate?.invoke(attachments, draft, ::frame, ::nodes)
            check(nodes())
            if (checkPixels != null) {
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                try {
                    view.draw(Canvas(bitmap))
                    checkPixels(nodes(), bitmap)
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            view.disposeComposition(); recomposer.close(); runner.cancel()
            ThemeStore.saveShowUserAvatar(context, previousAvatar)
            controller.pause().stop().destroy()
        }
    }
}
