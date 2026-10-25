package com.irio

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
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
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsUpdatesTest {
    @Test fun channelDefaultsToReleaseAndPersistsTheSelection() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        context.getSharedPreferences("updates", Context.MODE_PRIVATE).edit().clear().commit()
        assertEquals(UpdateChannel.RELEASE, UpdateChannel.load(context))
        UpdateChannel.PRE_RELEASE.save(context)
        assertEquals(UpdateChannel.PRE_RELEASE, UpdateChannel.load(context))
        UpdateChannel.RELEASE.save(context)
        assertEquals(UpdateChannel.RELEASE, UpdateChannel.load(context))
        context.getSharedPreferences("updates", Context.MODE_PRIVATE).edit().putString("channel", "invalid").commit()
        assertEquals(UpdateChannel.RELEASE, UpdateChannel.load(context))
    }

    @Test fun buttonsShareOneRowAndRemainReadableInBothLanguagesAndThemes() = runTest {
        val channel = mutableStateOf(UpdateChannel.RELEASE)
        val density = mutableStateOf(Density(1f))
        var checks = 0
        withUi(content = {
            CompositionLocalProvider(LocalDensity provides density.value) {
                SettingsUpdateButtons(channel.value, false, { channel.value = it }, { checks++ })
            }
        }) { fixture ->
            val previousLanguage = ThemeStore.language
            val previousTheme = ThemeStore.preference
            try {
                for (language in listOf(AppLanguage.ZH_CN, AppLanguage.EN)) {
                    ThemeStore.saveLanguage(fixture.context, language)
                    for (theme in listOf(ThemePreference.LIGHT, ThemePreference.DARK, ThemePreference.AMOLED)) {
                        ThemeStore.save(fixture.context, theme)
                        for (option in UpdateChannel.entries) {
                            channel.value = option
                            density.value = Density(1f, 1.3f)
                            fixture.frames()
                            val check = fixture.tag("check-updates")
                            val select = fixture.tag("update-channel")
                            assertTrue("Channel must be on the right", check.boundsInRoot.right + 7.5f <= select.boundsInRoot.left)
                            assertEquals(check.boundsInRoot.center.y, select.boundsInRoot.center.y, 0.5f)
                            assertTrue(check.boundsInRoot.height >= 48f && select.boundsInRoot.height >= 48f)
                            assertTrue(select.boundsInRoot.right <= 344f)
                            assertEquals(option.label, select.config[SemanticsProperties.StateDescription])
                            fixture.nodes().filter { it.config.contains(SemanticsActions.GetTextLayoutResult) }.forEach { text ->
                                val layouts = mutableListOf<TextLayoutResult>()
                                text.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
                                // SimpleText's synthesized semantics layout can retain the larger
                                // paragraph constraint. Check the actual line extents, not that width.
                                layouts.forEach { layout ->
                                    assertFalse("Button text must not lose lines", layout.multiParagraph.didExceedMaxLines)
                                    for (line in 0 until layout.lineCount) {
                                        assertTrue("Button text must fit ($language, $theme, $option): ${layout.layoutInput.text}",
                                            layout.getLineRight(line) <= layout.size.width + 0.5f &&
                                                layout.getLineBottom(line) <= layout.size.height + 0.5f)
                                    }
                                }
                            }
                        }
                    }
                }
                fixture.click(fixture.tag("check-updates"))
                assertEquals(1, checks)
            } finally {
                ThemeStore.saveLanguage(fixture.context, previousLanguage)
                ThemeStore.save(fixture.context, previousTheme)
            }
        }
    }

    @Test fun channelMenuOffersBothChoicesAndSavesTheChosenOne() = runTest {
        withUi(content = { SettingsUpdateSection(onNotice = {}) }) { fixture ->
            fixture.click(fixture.tag("update-channel"))
            fixture.frames(30)
            val preview = fixture.nodes().single {
                it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { value -> value.text == "Pre-release" }
            }
            fixture.click(preview)
            fixture.frames(30)
            assertEquals(UpdateChannel.PRE_RELEASE, UpdateChannel.load(fixture.context))
            assertEquals("Pre-release", fixture.tag("update-channel").config[SemanticsProperties.StateDescription])
        }
    }

    @Test fun checkingDisablesBothButtonsAndUsesTheSelectedChannel() = runTest {
        val result = CompletableDeferred<UpdateCheckResult>()
        var calls = 0
        var requestedChannel: UpdateChannel? = null
        val notices = mutableListOf<String>()
        withUi(before = { UpdateChannel.PRE_RELEASE.save(it) }, content = {
            SettingsUpdateSection(onNotice = { notices += it }, checkForUpdates = { _, channel ->
                calls++
                requestedChannel = channel
                result.await()
            })
        }) { fixture ->
            fixture.click(fixture.tag("check-updates"))
            fixture.frames()
            assertEquals(1, calls)
            assertEquals(UpdateChannel.PRE_RELEASE, requestedChannel)
            assertTrue(fixture.tag("check-updates").config.contains(SemanticsProperties.Disabled))
            assertTrue(fixture.tag("update-channel").config.contains(SemanticsProperties.Disabled))
            result.complete(UpdateCheckResult.NoReleases)
            fixture.frames()
            assertEquals(listOf(tr("所选渠道暂无已发布版本")), notices)
            assertFalse(fixture.tag("check-updates").config.contains(SemanticsProperties.Disabled))
        }
    }

    @Test fun checkResultsShowFeedbackOrOpenTheExactReleasePage() = runTest {
        var response: UpdateCheckResult = UpdateCheckResult.UpToDate
        var failure = false
        val notices = mutableListOf<String>()
        val release = GitHubUpdate("1.0.0-beta.1", "$IRIO_PROJECT_URL/releases/tag/1.0.0-beta.1", true)
        withUi(content = {
            SettingsUpdateSection(onNotice = { notices += it }, checkForUpdates = { _, _ ->
                if (failure) throw UpdateCheckException("GitHub 请求过于频繁，请稍后重试")
                response
            })
        }) { fixture ->
            fixture.click(fixture.tag("check-updates")); fixture.frames()
            assertEquals(tr("当前已是最新版本"), notices.last())
            failure = true
            fixture.click(fixture.tag("check-updates")); fixture.frames()
            assertEquals(tr("GitHub 请求过于频繁，请稍后重试"), notices.last())
            assertFalse(fixture.tag("check-updates").config.contains(SemanticsProperties.Disabled))
            failure = false
            response = UpdateCheckResult.Available(release)
            fixture.click(fixture.tag("check-updates")); fixture.frames(30)
            fixture.click(fixture.nodes().single {
                it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { value -> value.text == tr("前往下载") }
            })
            assertEquals(release.url, shadowOf(fixture.context).nextStartedActivity.dataString)
        }
    }

    @Test fun updateNotesRenderMarkdownHeadingsEmphasisAndLinks() = runTest {
        val release = GitHubUpdate("1.0.0", "$IRIO_PROJECT_URL/releases/tag/1.0.0", false,
            "## Important changes\n\nNew **bold feature** with a [project link]($IRIO_PROJECT_URL).\n\n- First improvement\n- Second improvement")
        withUi(content = { UpdateDialogContent(release, {}, {}) }) { fixture ->
            val texts = fixture.nodes().flatMap { it.config.getOrElse(SemanticsProperties.Text) { emptyList() } }
            assertTrue("Headings must render without Markdown markers", texts.any { it.text == "Important changes" })
            val paragraph = texts.single { it.text.contains("bold feature") }
            assertFalse(paragraph.text.contains("**"))
            assertTrue("Strong emphasis must become a bold span", paragraph.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
            assertTrue("Release-note links must remain interactive", paragraph.getLinkAnnotations(0, paragraph.length).isNotEmpty())
            assertTrue(texts.any { it.text.contains("First improvement") })
            assertTrue(texts.any { it.text.contains("Second improvement") })
        }
    }

    @Test fun longNotesScrollWithoutMovingTheCloseAndDownloadButtons() = runTest {
        val release = GitHubUpdate("1.0.0", "$IRIO_PROJECT_URL/releases/tag/1.0.0", false,
            (1..50).joinToString("\n\n") { "## Change $it\n\nA detailed release note with **important changes** and more information." })
        var closes = 0
        var downloads = 0
        withUi(content = { UpdateDialogContent(release, { closes++ }, { downloads++ }) }) { fixture ->
            val notes = fixture.tag("update-release-notes")
            val axis = notes.config[SemanticsProperties.VerticalScrollAxisRange]
            assertTrue("Long release notes must scroll", axis.maxValue() > 0f)
            val buttons = fixture.nodes().filter { it.config.contains(SemanticsActions.OnClick) }
            val close = buttons.single { node ->
                fixture.textsWithin(node).any { it == "关闭" || it == "Close" }
            }
            val download = buttons.single { node -> fixture.textsWithin(node).contains(tr("前往下载")) }
            val before = listOf(close.boundsInRoot, download.boundsInRoot)
            assertEquals(close.boundsInRoot.center.y, download.boundsInRoot.center.y, 0.5f)
            assertTrue("Actions must stay below the scroll viewport", notes.boundsInRoot.bottom <= close.boundsInRoot.top)
            assertTrue("Dialog must fit the window", fixture.tag("update-dialog-content").boundsInRoot.bottom <= 800f)
            assertTrue(notes.config[SemanticsActions.ScrollBy].action!!.invoke(0f, 600f))
            fixture.frames(30)
            assertTrue("The actual notes viewport must have scrolled", fixture.tag("update-release-notes").config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f)
            assertEquals(before, listOf(close.boundsInRoot, download.boundsInRoot))
            fixture.click(close)
            assertEquals(1, closes)
            assertEquals("Close must not start a download", 0, downloads)
            fixture.click(download)
            assertEquals(1, downloads)
        }
    }

    @Test fun missingReleaseNotesStillLeaveBothActionsAvailableInBothLanguages() = runTest {
        val release = GitHubUpdate("1.0.0", "$IRIO_PROJECT_URL/releases/tag/1.0.0", false)
        withUi(content = { UpdateDialogContent(release, {}, {}) }) { fixture ->
            val previousLanguage = ThemeStore.language
            try {
                for (language in listOf(AppLanguage.ZH_CN, AppLanguage.EN)) {
                    ThemeStore.saveLanguage(fixture.context, language)
                    fixture.frames()
                    val texts = fixture.nodes().flatMap { it.config.getOrElse(SemanticsProperties.Text) { emptyList() } }.map { it.text }
                    assertTrue(texts.contains(tr("暂无更新说明")))
                    assertTrue(texts.contains(if (language == AppLanguage.EN) "Close" else "关闭"))
                    assertTrue(texts.contains(tr("前往下载")))
                    assertFalse("The old Cancel action must be replaced", texts.contains(tr("取消")))
                }
            } finally { ThemeStore.saveLanguage(fixture.context, previousLanguage) }
        }
    }

    private suspend fun TestScope.withUi(
        before: (Context) -> Unit = {},
        content: @Composable () -> Unit,
        check: (Fixture) -> Unit,
    ) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val context = controller.get()
        ThemeStore.ensureLoaded(context)
        context.getSharedPreferences("updates", Context.MODE_PRIVATE).edit().clear().commit()
        before(context)
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(context)
        try {
            view.setParentCompositionContext(recomposer)
            context.setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme { Box(Modifier.padding(16.dp)) { content() } }
                }
            }
            val fixture = Fixture(context, view, clock, this)
            fixture.frames()
            check(fixture)
        } finally {
            view.disposeComposition(); recomposer.close(); runner.cancel()
            controller.pause().stop().destroy()
        }
    }

    private class Fixture(val context: ComponentActivity, val view: ComposeView, val clock: BroadcastFrameClock, val scope: TestScope) {
        private var time = 0L
        fun frames(count: Int = 8) = repeat(count) {
            shadowOf(Looper.getMainLooper()).idle()
            Snapshot.sendApplyNotifications(); scope.runCurrent()
            time += 16_000_000L; clock.sendFrame(time); scope.runCurrent()
            view.requestLayout()
            view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 360, 800)
        }
        fun nodes(): List<SemanticsNode> {
            fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
            fun roots(view: View): List<ViewRootForTest> =
                (if (view is ViewRootForTest) listOf(view) else emptyList()) +
                    (if (view is ViewGroup) (0 until view.childCount).flatMap { roots(view.getChildAt(it)) } else emptyList())
            val globalClass = Class.forName("android.view.WindowManagerGlobal")
            val global = globalClass.getMethod("getInstance").invoke(null)
            val views = globalClass.getDeclaredField("mViews").apply { isAccessible = true }.get(global) as List<*>
            return (views.filterIsInstance<View>() + view).flatMap(::roots).distinct().flatMap {
                descend(it.semanticsOwner.unmergedRootSemanticsNode)
            }
        }
        fun tag(tag: String) = nodes().single { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == tag }
        fun textsWithin(node: SemanticsNode): List<String> =
            node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text } + node.children.flatMap(::textsWithin)
        fun click(target: SemanticsNode) {
            var node: SemanticsNode? = target
            while (node != null && !node.config.contains(SemanticsActions.OnClick)) node = node.parent
            assertNotNull("Clickable ancestor required", node)
            assertTrue(node!!.config[SemanticsActions.OnClick].action!!.invoke())
        }
    }
}
