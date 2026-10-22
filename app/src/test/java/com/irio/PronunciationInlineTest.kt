package com.irio

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
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

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PronunciationInlineTest {
    @Test fun replacesOnlyVerifiedLinksAndKeepsOtherFormattingAndUrls() {
        val recording = fixturePronunciation()
        var plays = 0
        var opens = 0
        val builder = AnnotatedString.Builder("Say hello and web")
        builder.addStyle(SpanStyle(fontWeight = FontWeight.Bold), 4, 9)
        builder.addLink(LinkAnnotation.Url("irio-pronunciation:${recording.id}"), 4, 9)
        builder.addLink(LinkAnnotation.Url("https://example.com", linkInteractionListener = LinkInteractionListener { opens++ }), 14, 17)
        val result = decoratePronunciationLinks(builder.toAnnotatedString(), mapOf(recording.id to recording), Color.Black) { plays++ }
        assertEquals("Say ${PRONUNCIATION_ICON_PREFIX}hello and web", result.text)
        val range = result.getStringAnnotations(PRONUNCIATION_SPAN, 0, result.length).single()
        assertEquals("${PRONUNCIATION_ICON_PREFIX}hello", result.text.substring(range.start, range.end))
        assertTrue(result.spanStyles.any { it.item.fontWeight == FontWeight.Bold && it.start == 4 && it.end == 11 })
        val links = result.getLinkAnnotations(0, result.length)
        val play = links.single { it.item is LinkAnnotation.Clickable }.item
        play.linkInteractionListener!!.onClick(play)
        assertEquals(1, plays)
        val normal = links.single { it.item is LinkAnnotation.Url }.item
        normal.linkInteractionListener!!.onClick(normal)
        assertEquals(1, opens)
    }

    @Test fun unknownIdsAndMismatchedWordsNeverOpenAnAudioOrExternalUrl() {
        val recording = fixturePronunciation()
        for (word in listOf("other", "hello")) {
            val builder = AnnotatedString.Builder(word)
            builder.addLink(LinkAnnotation.Url("irio-pronunciation:${if (word == "hello") "forged" else recording.id}"), 0, word.length)
            val result = decoratePronunciationLinks(builder.toAnnotatedString(), mapOf(recording.id to recording), Color.Black) { error("Must not play") }
            assertEquals(word, result.text)
            assertTrue(result.getLinkAnnotations(0, result.length).isEmpty())
            assertTrue(result.getStringAnnotations(PRONUNCIATION_SPAN, 0, result.length).isEmpty())
        }
    }

    @Test fun adjacentMarkersHaveIndependentClickableRanges() {
        val recording = fixturePronunciation()
        val builder = AnnotatedString.Builder("hellohello")
        builder.addLink(LinkAnnotation.Url("irio-pronunciation:${recording.id}"), 0, 5)
        builder.addLink(LinkAnnotation.Url("irio-pronunciation:${recording.id}"), 5, 10)
        val result = decoratePronunciationLinks(builder.toAnnotatedString(), mapOf(recording.id to recording), Color.Black) {}
        assertEquals("${PRONUNCIATION_ICON_PREFIX}hello${PRONUNCIATION_ICON_PREFIX}hello", result.text)
        assertEquals(listOf(0 to 7, 7 to 14), result.getLinkAnnotations(0, result.length).map { it.start to it.end })
        assertEquals("hello", speechPlainText(recording.toJson().getString("marker")))
    }

    @Test fun speakerDoesNotGetStrandedOnThePreviousLine() {
        val recording = fixturePronunciation()
        val builder = AnnotatedString.Builder("Please say hello now")
        builder.addLink(LinkAnnotation.Url("irio-pronunciation:${recording.id}"), 11, 16)
        val marked = decoratePronunciationLinks(builder.toAnnotatedString(), mapOf(recording.id to recording), Color.Black) {}
        val range = marked.getStringAnnotations(PRONUNCIATION_SPAN, 0, marked.length).single()
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val measurer = TextMeasurer(createFontFamilyResolver(context), Density(1f), LayoutDirection.Ltr)
        for (width in 80..220) {
            val layout = measurer.measure(marked, style = TextStyle(fontSize = 17.sp, lineHeight = 26.sp), constraints = Constraints(maxWidth = width))
            assertEquals("Speaker and word split at width=$width", layout.getLineForOffset(range.start),
                layout.getLineForOffset(range.start + PRONUNCIATION_ICON_PREFIX.length))
        }
    }

    @Test fun dashedUnderlineCoversOnlyTheWordNotTheSpeakerSlot() {
        val recording = fixturePronunciation()
        val builder = AnnotatedString.Builder("Please say hello now")
        builder.addLink(LinkAnnotation.Url("irio-pronunciation:${recording.id}"), 11, 16)
        val marked = decoratePronunciationLinks(builder.toAnnotatedString(), mapOf(recording.id to recording), Color.Black) {}
        val range = marked.getStringAnnotations(PRONUNCIATION_SPAN, 0, marked.length).single()
        val measurer = TextMeasurer(createFontFamilyResolver(org.robolectric.RuntimeEnvironment.getApplication()), Density(1f), LayoutDirection.Ltr)
        for (width in listOf(80, 120, 220)) {
            val layout = measurer.measure(marked, style = TextStyle(fontSize = 17.sp, lineHeight = 26.sp), constraints = Constraints(maxWidth = width))
            val underlines = pronunciationUnderlineBounds(layout, range.start, range.end)
            assertEquals("The underline must start under the word at width=$width",
                layout.getBoundingBox(range.start + PRONUNCIATION_ICON_PREFIX.length).left, underlines.first().left, 0.01f)
            assertTrue(underlines.first().left >= layout.getBoundingBox(range.start).right)
            assertEquals(layout.getBoundingBox(range.end - 1).right, underlines.last().right, 0.01f)
        }
    }

    @Test fun actualMarkdownRetainsSpeakerAndUnderlineSpansInParagraphsHeadingsListsAndTables() = runTest {
        val recording = fixturePronunciation()
        val marker = recording.toJson().getString("marker")
        val content = "# $marker\n\nSay $marker now.\n\n- $marker\n\n| Word |\n| --- |\n| $marker |"
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        ThemeStore.ensureLoaded(controller.get())
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(controller.get())
        var plays = 0
        try {
            view.setParentCompositionContext(recomposer)
            controller.get().setContentView(view)
            view.setContent {
                AppTheme {
                    CompositionLocalProvider(LocalPronunciationPlayback provides { plays++ }) {
                        AssistantMarkdown(content, streaming = true, renderScope = this@runTest,
                            pronunciations = mapOf(recording.id to recording))
                    }
                }
            }
            repeat(12) { frame ->
                Snapshot.sendApplyNotifications()
                runCurrent()
                clock.sendFrame(frame * 16_000_000L)
                runCurrent()
                view.measure(View.MeasureSpec.makeMeasureSpec(420, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.AT_MOST))
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            }
            fun visit(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::visit)
            val nodes = visit((view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
            val layouts = nodes.flatMap { node ->
                val results = mutableListOf<TextLayoutResult>()
                if (node.config.contains(SemanticsActions.GetTextLayoutResult)) {
                    node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
                }
                results
            }
            val marked = layouts.map { it.layoutInput.text }.filter { it.getStringAnnotations(PRONUNCIATION_SPAN, 0, it.length).isNotEmpty() }
            assertEquals("Each Markdown surface must preserve the drawable pronunciation spans", 4, marked.size)
            marked.forEach { text ->
                val link = text.getLinkAnnotations(0, text.length).single { it.item is LinkAnnotation.Clickable }.item
                link.linkInteractionListener!!.onClick(link)
            }
            assertEquals(4, plays)
            assertFalse("Word audio rendering must not create the TTS mini player", SpeechPlayer.playback.active)
        } finally {
            view.disposeComposition()
            recomposer.cancel()
            runner.cancel()
            controller.pause().stop().destroy()
        }
    }
}
