package com.irio

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.TextLayoutResult
import com.composables.ui.theme.colors
import com.composables.ui.theme.onBackgroundColor
import com.composables.ui.theme.mutedColor
import com.composeunstyled.theme.Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
class AboutScreenTest {
    @Test fun versionMovesFromSettingsToAboutAndResourceRowsNavigate() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val context = controller.get()
        ThemeStore.ensureLoaded(context)
        val originalTheme = ThemeStore.preference
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(context)
        val about = mutableStateOf(false)
        val fontScale = mutableStateOf(1f)
        var openedCredits = false
        var expectedMuted = Color.Unspecified
        var frameTime = 0L
        fun frames(count: Int = 8) {
            repeat(count) {
                shadowOf(android.os.Looper.getMainLooper()).idle()
                Snapshot.sendApplyNotifications(); runCurrent()
                frameTime += 16_000_000L; clock.sendFrame(frameTime); runCurrent()
                view.requestLayout()
                view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, 360, 1000)
            }
        }
        fun nodes(): List<SemanticsNode> {
            fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
            return descend((view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
        }
        fun hasText(text: String) = nodes().any {
            it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { value -> value.text == text }
        }
        fun click(text: String) {
            var node: SemanticsNode? = nodes().single {
                it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { value -> value.text == text }
            }
            while (node != null && !node.config.contains(SemanticsActions.OnClick)) node = node.parent
            assertNotNull(text, node)
            assertTrue(node!!.config[SemanticsActions.OnClick].action!!.invoke())
        }
        try {
            view.setParentCompositionContext(recomposer)
            context.setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale.value)) {
                    AppTheme {
                        expectedMuted = Theme[colors][mutedColor]
                        if (about.value) AboutScreen(onBack = {}, onAcknowledgements = { openedCredits = true })
                        else SettingsScreen(onBack = {})
                    }
                }
            }
            frames()
            val version = androidSoftwareInfo(context)
            assertFalse("The Settings footer must no longer display the version", hasText(version))
            assertFalse("Update controls must not remain on the Settings homepage", nodes().any {
                it.config.getOrElse(SemanticsProperties.TestTag) { "" } in setOf("check-updates", "update-channel")
            })
            click(tr("关于"))
            assertEquals(AboutActivity::class.java.name, shadowOf(context).nextStartedActivity.component!!.className)
            about.value = true
            frames()
            assertTrue("The exact installed-version line must move to About", hasText(version))
            fun assertUpdatesBelowVersion() {
                val versionBounds = nodes().single { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "about-version" }.boundsInRoot
                val check = nodes().single { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "check-updates" }.boundsInRoot
                val channel = nodes().single { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "update-channel" }.boundsInRoot
                assertTrue("Check for updates belongs directly below the version", check.top >= versionBounds.bottom + 15.5f)
                assertTrue("The update channel stays to the right", check.right + 7.5f <= channel.left)
                assertEquals(check.center.y, channel.center.y, 0.5f)
                val identity = nodes().single { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "about-identity" }.boundsInRoot
                assertTrue("Update controls belong to the About identity block", check.bottom <= identity.bottom && channel.bottom <= identity.bottom)
            }
            assertUpdatesBelowVersion()
            for (title in listOf("检查更新", "检查渠道")) {
                val hit = searchSettings(title, label = { it }).single { it.title == title }
                assertEquals("关于", hit.page)
                assertEquals(AboutActivity::class.java, hit.destination)
            }
            assertTrue("About must show the variant's app name, including Irio Debug for debug builds",
                hasText(context.getString(R.string.app_name)))
            assertTrue("The project URL must be displayed beneath its title", hasText(IRIO_PROJECT_URL))
            fun assertLinkSubtitle(label: String, address: String) {
                assertTrue("$label must display its URL beneath the title", hasText(address))
                val title = nodes().single { it.config.getOrElse(SemanticsProperties.Text) { emptyList() }
                    .any { value -> value.text == tr(label) } }
                val url = nodes().single { it.config.getOrElse(SemanticsProperties.Text) { emptyList() }
                    .any { value -> value.text == address } }
                assertTrue("URL must occupy a separate line below the title", url.boundsInRoot.top >= title.boundsInRoot.bottom)
                assertEquals(title.boundsInRoot.left, url.boundsInRoot.left, 0.5f)
                assertTrue("Long URLs must remain within the row", url.boundsInRoot.right <= 344f)
                val layouts = mutableListOf<TextLayoutResult>()
                url.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
                assertEquals("The URL must use the theme's muted gray", expectedMuted, layouts.single().layoutInput.style.color)
            }
            assertLinkSubtitle("项目地址", IRIO_PROJECT_URL)
            assertLinkSubtitle("文档与更新日志", IRIO_DOCS_URL)
            fun assertRowAlignment() {
                val titles = listOf("项目地址", "文档与更新日志", "鸣谢").map { label ->
                    nodes().single { it.config.getOrElse(SemanticsProperties.Text) { emptyList() }
                        .any { value -> value.text == tr(label) } }.boundsInRoot
                }
                assertEquals("All About rows must share the same icon and title inset", titles[0].left, titles[1].left, 0.5f)
                assertEquals("Acknowledgements must align with the project and docs icons", titles[0].left, titles[2].left, 0.5f)
            }
            assertRowAlignment()
            fun assertResourceGroup() {
                fun row(label: String): SemanticsNode {
                    var node: SemanticsNode? = nodes().single {
                        it.config.getOrElse(SemanticsProperties.Text) { emptyList() }
                            .any { value -> value.text == tr(label) }
                    }
                    while (node != null && !node.config.contains(SemanticsActions.OnClick)) node = node.parent
                    return requireNotNull(node)
                }
                val project = row("项目地址").boundsInRoot
                val docs = row("文档与更新日志").boundsInRoot
                assertEquals("Project and docs must be adjacent rows within one settings card", project.bottom, docs.top, 0.5f)
                val group = nodes().single { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "about-resources" }.boundsInRoot
                assertEquals(project.top, group.top, 0.5f)
                assertEquals(docs.bottom, group.bottom, 0.5f)
                assertTrue("Acknowledgements must remain a separate card above the two links",
                    row("鸣谢").boundsInRoot.bottom + 11.5f <= group.top)
            }
            assertResourceGroup()
            click(tr("项目地址"))
            val project = shadowOf(context).nextStartedActivity
            assertEquals(Intent.ACTION_VIEW, project.action)
            assertEquals(IRIO_PROJECT_URL, project.dataString)
            click(IRIO_PROJECT_URL)
            assertEquals("Clicking the URL must keep the same full-row action", IRIO_PROJECT_URL,
                shadowOf(context).nextStartedActivity.dataString)
            click(tr("文档与更新日志"))
            assertEquals(IRIO_DOCS_URL, shadowOf(context).nextStartedActivity.dataString)
            click(IRIO_DOCS_URL)
            assertEquals(IRIO_DOCS_URL, shadowOf(context).nextStartedActivity.dataString)
            click(tr("鸣谢"))
            assertTrue(openedCredits)
            for (theme in listOf(ThemePreference.LIGHT, ThemePreference.DARK, ThemePreference.AMOLED)) {
                ThemeStore.save(context, theme)
                fontScale.value = 1.3f
                frames()
                val identity = nodes().single { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "about-identity" }.boundsInRoot
                val versionBounds = nodes().single { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "about-version" }.boundsInRoot
                assertTrue("Version text must fit the identity block at larger font scales", versionBounds.bottom <= identity.bottom)
                assertTrue(versionBounds.left >= 0f && versionBounds.right <= 360f)
                assertLinkSubtitle("项目地址", IRIO_PROJECT_URL)
                assertLinkSubtitle("文档与更新日志", IRIO_DOCS_URL)
                assertRowAlignment()
                assertResourceGroup()
                assertUpdatesBelowVersion()
            }
        } finally {
            view.disposeComposition(); recomposer.close(); runner.cancel()
            ThemeStore.save(context, originalTheme)
            controller.pause().stop().destroy()
        }
    }

    @Test fun licenseTextIsCollapsedByDefaultAndCanBeExpandedAndCollapsed() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(controller.get())
        val expanded = mutableStateOf(false)
        val credit = mutableStateOf(acknowledgements.single { it.id == "lora" })
        var expectedForeground = Color.Unspecified
        var expectedLinkMuted = Color.Unspecified
        var projectClicks = 0
        var frameTime = 0L
        fun frames(count: Int = 8) {
            repeat(count) {
                shadowOf(android.os.Looper.getMainLooper()).idle()
                Snapshot.sendApplyNotifications(); runCurrent()
                frameTime += 16_000_000L; clock.sendFrame(frameTime); runCurrent()
                view.requestLayout()
                view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, 360, 1000)
            }
        }
        fun nodes(): List<SemanticsNode> {
            fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
            return descend((view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
        }
        fun licenseNodes() = nodes().filter { it.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith("credit-license-") }
        fun toggle() {
            val header = nodes().single { it.config.contains(SemanticsActions.OnClick) &&
                it.config.contains(SemanticsProperties.StateDescription) }
            header.config[SemanticsActions.OnClick].action!!.invoke()
            frames()
        }
        try {
            view.setParentCompositionContext(recomposer)
            controller.get().setContentView(view)
            view.setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) {
                AppTheme {
                    expectedForeground = Theme[colors][onBackgroundColor]
                    expectedLinkMuted = Theme[colors][mutedColor]
                    AcknowledgementCard(credit.value, expanded.value, { expanded.value = !expanded.value }, { projectClicks++ })
                }
            } }
            frames()
            assertTrue("Legal text must not make the initial credits list excessively long", licenseNodes().isEmpty())
            val cardBitmap = Bitmap.createBitmap(360, 1000, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(cardBitmap))
                assertTrue("Acknowledgement cards must use the larger settings-card corner, allowing its antialiased edge",
                    android.graphics.Color.alpha(cardBitmap.getPixel(4, 4)) < 64)
                assertTrue("The card's central background must remain filled",
                    android.graphics.Color.alpha(cardBitmap.getPixel(180, 4)) > 0)
            } finally { cardBitmap.recycle() }
            val headerBounds = nodes().single { it.config.contains(SemanticsProperties.StateDescription) &&
                it.config.contains(SemanticsActions.OnClick) }.boundsInRoot
            fun chevronPixels(): IntArray {
                val bitmap = Bitmap.createBitmap(360, 1000, Bitmap.Config.ARGB_8888)
                return try {
                    view.draw(Canvas(bitmap))
                    IntArray(18 * 18).also { bitmap.getPixels(it, 0, 18,
                        headerBounds.right.toInt() - 34, headerBounds.center.y.toInt() - 9, 18, 18) }
                } finally { bitmap.recycle() }
            }
            val collapsedChevron = chevronPixels()
            toggle()
            val expandingChevron = chevronPixels()
            for (attempt in 0 until 100) {
                frames(2)
                if (licenseNodes().isNotEmpty()) break
                withContext(Dispatchers.IO) { Thread.sleep(10) }
            }
            assertTrue(licenseNodes().single().config[SemanticsProperties.Text].single().text.contains("The Lora Project Authors"))
            frames(60)
            val projectTitle = nodes().single { it.config.getOrElse(SemanticsProperties.Text) { emptyList() }
                .any { value -> value.text == tr("项目地址") } }
            val projectUrls = nodes().filter { it.config.getOrElse(SemanticsProperties.Text) { emptyList() }
                .any { value -> value.text == credit.value.projectUrl } }
            assertEquals("Expanded acknowledgement projects must display their complete URL", 1, projectUrls.size)
            val projectUrl = projectUrls.single()
            assertTrue("The URL must appear beneath the project title", projectUrl.boundsInRoot.top >= projectTitle.boundsInRoot.bottom)
            assertEquals(projectTitle.boundsInRoot.left, projectUrl.boundsInRoot.left, 0.5f)
            assertTrue("The URL must wrap within the card instead of overflowing", projectUrl.boundsInRoot.right <= 344f)
            val urlLayouts = mutableListOf<TextLayoutResult>()
            projectUrl.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(urlLayouts)
            assertEquals("Credit project URLs must use muted gray", expectedLinkMuted, urlLayouts.single().layoutInput.style.color)
            var projectButton: SemanticsNode? = projectUrl
            while (projectButton != null && !projectButton.config.contains(SemanticsActions.OnClick)) projectButton = projectButton.parent
            assertNotNull(projectButton)
            assertTrue(projectButton!!.config[SemanticsActions.OnClick].action!!.invoke())
            assertEquals("The whole project row must retain its existing navigation action", 1, projectClicks)
            assertTrue("Opening the project must not toggle the credit card", expanded.value)
            val expandedChevron = chevronPixels()
            assertFalse("Expansion must rotate through an intermediate angle, not jump to 180 degrees",
                expandingChevron.contentEquals(expandedChevron))
            assertFalse(expandingChevron.contentEquals(collapsedChevron))
            toggle()
            val collapsingChevron = chevronPixels()
            frames(80)
            assertFalse("Collapse must animate back instead of immediately jumping to zero",
                collapsingChevron.contentEquals(chevronPixels()))
            assertFalse(collapsingChevron.contentEquals(expandedChevron))
            assertTrue(licenseNodes().isEmpty())
            credit.value = acknowledgements.single { it.id == "markdown" }
            frames()
            assertTrue("Local library modifications must be marked without requiring expansion", nodes().any {
                it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { text -> text.text == tr("本地修改") }
            })
            val modificationLabel = nodes().single {
                it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { text -> text.text == tr("本地修改") }
            }
            val layouts = mutableListOf<TextLayoutResult>()
            modificationLabel.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
            assertEquals("Small modification labels must use the high-contrast foreground, not an accent color",
                expectedForeground, layouts.single().layoutInput.style.color)
        } finally {
            view.disposeComposition(); recomposer.close(); runner.cancel()
            controller.pause().stop().destroy()
        }
    }
}
