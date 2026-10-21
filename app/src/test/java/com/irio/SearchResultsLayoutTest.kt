package com.irio

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
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

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchResultsLayoutTest {
    private val item = SearchResultItem(
        url = "https://example.com/blog/article",
        title = "An article title",
        snippet = "This is a long search result description with enough text to require several lines. ".repeat(16),
        siteName = "Example",
        faviconUrl = "",
    )

    @Test fun resultHasGoogleStyleHierarchyAndAnUnmodifiedClickableUrl() = withRow(item) { nodes, opened ->
        fun text(value: String) = nodes.single {
            it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.singleOrNull()?.text == value
        }
        val site = text(item.siteName)
        val url = text(item.url)
        val title = text(item.title)
        val preview = text(item.snippet)
        assertTrue(site.boundsInRoot.bottom <= url.boundsInRoot.top + 1f)
        assertTrue(url.boundsInRoot.bottom <= title.boundsInRoot.top)
        assertTrue(title.boundsInRoot.bottom <= preview.boundsInRoot.top)
        val actions = nodes.filter { it.config.contains(SemanticsActions.OnClick) }
        assertEquals("No extra menu button", 1, actions.size)
        assertTrue(actions.single().config[SemanticsActions.OnClick].action!!.invoke())
        assertEquals(listOf(item.url), opened)
    }

    @Test fun longSnippetIsEllipsizedAtExactlyTwoLines() = withRow(item) { nodes, _ ->
        assertTwoLinePreview(nodes, item.snippet)
    }

    @Test fun largeTextInDarkModeStillUsesTwoLinesAndEllipsis() = withRow(
        item, fontScale = 1.5f, theme = ThemePreference.DARK,
    ) { nodes, _ -> assertTwoLinePreview(nodes, item.snippet) }

    @Test fun dateIsDisplayedOnlyWhenProvided() {
        withRow(item) { nodes, _ ->
            val texts = texts(nodes)
            assertTrue(item.snippet in texts)
            assertFalse(texts.any { " — " in it })
        }
        val dated = item.copy(publishedAt = "2026-10-20")
        withRow(dated) { nodes, _ ->
            val preview = "${dated.publishedAt} — ${dated.snippet}"
            assertTrue(preview in texts(nodes))
            assertTwoLinePreview(nodes, preview)
        }
    }

    private fun texts(nodes: List<SemanticsNode>): List<String> = nodes.flatMap {
        it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { text -> text.text }
    }

    private fun assertTwoLinePreview(nodes: List<SemanticsNode>, preview: String) {
        val node = nodes.single {
            it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.singleOrNull()?.text == preview
        }
        val layouts = mutableListOf<TextLayoutResult>()
        assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts))
        val layout = layouts.single()
        assertEquals("Search previews must occupy at most two lines", 2, layout.lineCount)
        assertTrue("Overflow must end in an ellipsis", layout.isLineEllipsized(1))
    }

    private fun withRow(
        result: SearchResultItem,
        fontScale: Float = 1f,
        theme: ThemePreference = ThemePreference.LIGHT,
        check: (List<SemanticsNode>, List<String>) -> Unit,
    ) = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val context = controller.get()
        ThemeStore.ensureLoaded(context)
        val previousTheme = ThemeStore.preference
        ThemeStore.save(context, theme)
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(context)
        val opened = mutableListOf<String>()
        try {
            view.setParentCompositionContext(recomposer)
            context.setContentView(view)
            view.setContent {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f, fontScale),
                    LocalUriHandler provides object : UriHandler {
                        override fun openUri(uri: String) { opened += uri }
                    },
                ) { AppTheme { SearchResultRow(result) } }
            }
            repeat(8) { frame ->
                Snapshot.sendApplyNotifications()
                runCurrent()
                clock.sendFrame((frame + 1) * 16_000_000L)
                runCurrent()
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.AT_MOST),
                )
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            }
            fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
            val root = (view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode
            check(descend(root), opened)
        } finally {
            view.disposeComposition()
            recomposer.close()
            runner.cancel()
            ThemeStore.save(context, previousTheme)
            controller.pause().stop().destroy()
        }
    }
}
