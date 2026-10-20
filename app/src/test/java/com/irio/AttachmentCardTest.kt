package com.irio

import android.content.Context
import android.text.format.Formatter
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsActions
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
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AttachmentCardTest {
    @Test fun imageAndFileCardsShareACompactHeightBeforeAndAfterSending() {
        listOf(false, true).forEach { pending ->
            listOf("photo.jpg" to "image/jpeg", "notes.txt" to "text/plain").forEach { (name, mime) ->
                withCard(name, mime, onOpen = {}, onRemove = if (pending) ({}) else null) { nodes, _, _ ->
                    val surface = nodes.single {
                        it.config.contains(SemanticsActions.OnClick) &&
                            tr("移除附件") !in it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
                    }
                    assertEquals("Images and files must have the same compact height (pending=$pending, name=$name)", 56f, surface.boundsInRoot.height, 0.01f)
                    assertTrue("File cards must be narrower than the old 224dp layout", surface.boundsInRoot.width <= 192f)
                }
            }
        }
    }

    @Test fun filesShowExtensionStemAndFileSize() = withCard("report.final.pdf", "application/pdf") { nodes, attachment, context ->
        val texts = texts(nodes)
        assertTrue("The title must omit the extension", "report.final" in texts)
        assertTrue("The left tile must show the extension", "pdf" in texts)
        assertTrue("The subtitle must show the actual file size", Formatter.formatShortFileSize(context, attachment.size) in texts)
        assertFalse("The full filename is not a second title", attachment.name in texts)
    }

    @Test fun filenamePartsHandleMultipleDotsCaseAndUnknownExtensions() {
        assertEquals(AttachmentCardName("notes", "md"), attachmentCardName("notes.MD"))
        assertEquals(AttachmentCardName("movie", "webm"), attachmentCardName("movie.webm"))
        assertEquals(AttachmentCardName("archive.tar", "gz"), attachmentCardName("archive.tar.gz"))
        assertEquals(AttachmentCardName("report", "?"), attachmentCardName("report.longextension"))
        assertEquals(AttachmentCardName("README", "?"), attachmentCardName("README"))
        assertEquals(AttachmentCardName(".gitignore", "?"), attachmentCardName(".gitignore"))
        assertEquals(AttachmentCardName("trailing.", "?"), attachmentCardName("trailing."))
    }

    @Test fun videoUsesTheSameFileCardRatherThanAnImageThumbnail() = withCard("clip.mp4", "video/mp4") { nodes, attachment, context ->
        assertEquals(listOf("mp4", "clip", Formatter.formatShortFileSize(context, attachment.size)), texts(nodes))
    }

    @Test fun imagesOnlyShowASquareThumbnailEvenWhenThePreviewCannotBeDecoded() = withCard("photo.jpg", "image/jpeg") { nodes, _, _ ->
        assertTrue("Image cards must not show a filename, suffix or size", texts(nodes).isEmpty())
        val thumbnail = nodes.single { it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() } == listOf("photo.jpg") }
        assertEquals(56f, thumbnail.boundsInRoot.width, 0.01f)
        assertEquals(56f, thumbnail.boundsInRoot.height, 0.01f)
    }

    @Test fun pendingRemovalOverlaysTheTopRightCornerAndDoesNotOpenTheAttachment() {
        var opens = 0
        var removals = 0
        withCard("notes.txt", "text/plain", onOpen = { opens++ }, onRemove = { removals++ }) { nodes, _, _ ->
            val buttons = nodes.filter { it.config.contains(SemanticsActions.OnClick) }
            assertEquals(2, buttons.size)
            val remove = buttons.single { tr("移除附件") in it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() } }
            val card = buttons.single { it != remove }
            assertEquals("Delete must sit at the right corner, not beside the card", card.boundsInRoot.right, remove.boundsInRoot.center.x, 0.01f)
            assertEquals("Delete must sit at the top corner", card.boundsInRoot.top, remove.boundsInRoot.center.y, 0.01f)
            assertTrue(remove.config[SemanticsActions.OnClick].action!!.invoke())
            assertEquals(1, removals)
            assertEquals(0, opens)
            assertTrue(card.config[SemanticsActions.OnClick].action!!.invoke())
            assertEquals(1, opens)
        }
    }

    @Test fun sentImagesRemainOpenableWithoutADeleteButton() {
        var opens = 0
        withCard("photo.png", "image/png", onOpen = { opens++ }) { nodes, _, _ ->
            val buttons = nodes.filter { it.config.contains(SemanticsActions.OnClick) }
            assertEquals(1, buttons.size)
            assertTrue(buttons.single().config[SemanticsActions.OnClick].action!!.invoke())
            assertEquals(1, opens)
            assertFalse(nodes.any { tr("移除附件") in it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() } })
        }
    }

    @Test fun longNamesAndLargeTextKeepTheSizeBelowTheTitleInDarkMode() = withCard(
        "a-very-long-document-name-that-must-not-overlap-its-file-size.pdf", "application/pdf",
        fontScale = 1.5f, theme = ThemePreference.DARK,
    ) { nodes, attachment, context ->
        val textNodes = nodes.filter { it.config.contains(SemanticsProperties.Text) }
        val title = textNodes.single { it.config[SemanticsProperties.Text].single().text.startsWith("a-very-long") }
        val subtitle = textNodes.single { it.config[SemanticsProperties.Text].single().text == Formatter.formatShortFileSize(context, attachment.size) }
        assertTrue(title.boundsInRoot.bottom <= subtitle.boundsInRoot.top)
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        assertTrue(title.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts))
        assertTrue(layouts.single().lineCount <= 2)
    }

    @Test fun suffixThatCannotFitAtLargeFontScaleUsesAQuestionMark() = withCard(
        "wide.wwww", "application/octet-stream", fontScale = 2f,
    ) { nodes, _, _ ->
        assertTrue("A suffix that does not fit must use ? instead of clipping", "?" in texts(nodes))
    }

    @Test fun pastedTextSizeRefreshesAfterTheBackgroundWriteFinishes() {
        val loading = mutableStateOf(true)
        withCard(
            "pasted.txt", "text/plain", loading = loading,
            afterFirstLayout = { nodes, _ ->
                assertTrue("Unfinished files must not show a misleading zero size", "…" in texts(nodes))
                loading.value = false
            },
        ) { nodes, attachment, context ->
            assertTrue(Formatter.formatShortFileSize(context, attachment.size) in texts(nodes))
            assertFalse("…" in texts(nodes))
        }
    }

    private fun texts(nodes: List<SemanticsNode>): List<String> = nodes.flatMap {
        it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { text -> text.text }
    }

    private fun withCard(
        name: String,
        mime: String,
        fontScale: Float = 1f,
        theme: ThemePreference = ThemePreference.LIGHT,
        onOpen: (() -> Unit)? = null,
        onRemove: (() -> Unit)? = null,
        loading: State<Boolean>? = null,
        afterFirstLayout: ((List<SemanticsNode>, ChatAttachment) -> Unit)? = null,
        check: (List<SemanticsNode>, ChatAttachment, Context) -> Unit,
    ) = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val context = controller.get()
        ThemeStore.ensureLoaded(context)
        val previousTheme = ThemeStore.preference
        val previousLanguage = ThemeStore.language
        ThemeStore.save(context, theme)
        ThemeStore.saveLanguage(context, AppLanguage.ZH_CN)
        val file = File(context.cacheDir, "attachment-card-fixture").apply { writeBytes(ByteArray(1536)) }
        val attachment = ChatAttachment(name, mime, file.absolutePath)
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(context)
        try {
            view.setParentCompositionContext(recomposer)
            context.setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                    AppTheme {
                        AttachmentCard(attachment, loading = loading?.value ?: false, onOpen = onOpen, onRemove = onRemove)
                    }
                }
            }
            var frame = 0L
            fun settle() = repeat(8) {
                Snapshot.sendApplyNotifications()
                runCurrent()
                clock.sendFrame(++frame * 16_000_000L)
                runCurrent()
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.AT_MOST),
                )
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            }
            fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
            fun nodes(): List<SemanticsNode> = descend((view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
            settle()
            afterFirstLayout?.let { it(nodes(), attachment); settle() }
            check(nodes(), attachment, context)
        } finally {
            view.disposeComposition()
            recomposer.close()
            runner.cancel()
            ThemeStore.save(context, previousTheme)
            ThemeStore.saveLanguage(context, previousLanguage)
            controller.pause().stop().destroy()
            file.delete()
        }
    }
}
