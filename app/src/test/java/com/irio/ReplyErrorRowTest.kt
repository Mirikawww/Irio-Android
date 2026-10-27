package com.irio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsProperties.ContentDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import com.composables.ui.components.Text
import com.composables.ui.theme.colors
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.onBackgroundColor
import com.composables.ui.theme.secondaryColor
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ReplyErrorRowTest {
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun iconIsCenteredOnTheFirstRenderedLineAcrossMarkdownAndFontScales() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(controller.get())
        val error = mutableStateOf("Request failed")
        val fontScale = mutableStateOf(1f)
        ThemeStore.ensureLoaded(controller.get())
        val originalFont = ThemeStore.appFont
        val fixtures = listOf(
            "Request failed",
            "网络请求失败，请检查连接后再试。这是一段需要自动换行的详细报错说明。",
            "# Request failed\n\nA detailed explanation below the heading.",
            "### Request failed\n\nA detailed explanation below the heading.",
            "**Request failed**\n\nA second paragraph must not move the icon.",
            "[Request failed](https://example.com)\n\nAn explanation below a link.",
            "- Request failed\n- Check your network connection",
        )
        fixtures.forEach { FinishedMarkdownCache.prepare(it, this) }
        var frameTime = 0L
        fun frames() {
            repeat(8) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                Snapshot.sendApplyNotifications()
                runCurrent()
                clock.sendFrame(frameTime.also { frameTime += 16_000_000L })
                runCurrent()
                view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1500, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, 360, 1500)
            }
        }
        fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
        try {
            view.setParentCompositionContext(recomposer)
            controller.get().setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale.value)) {
                    AppTheme {
                        key(error.value) { ReplyErrorRow(ChatRetryStatus(error.value, 2, remainingSeconds = 8)) }
                    }
                }
            }
            for (font in AppFont.entries) {
                ThemeStore.saveAppFont(controller.get(), font)
                for (scale in listOf(1f, 1.6f)) {
                    fontScale.value = scale
                    for (fixture in fixtures) {
                        error.value = fixture
                        frames()
                        val nodes = descend((view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
                        val icon = nodes.single { it.config.getOrElse(ContentDescription) { emptyList() }.contains(tr("重试状态")) }
                        val firstText = nodes.filter {
                            it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { text ->
                                text.text.contains("Request failed") || text.text.startsWith("网络请求失败")
                            } && it.config.contains(SemanticsActions.GetTextLayoutResult)
                        }.minBy { it.boundsInRoot.top }
                        val results = mutableListOf<TextLayoutResult>()
                        firstText.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
                        val layout = results.single()
                        val firstLineCenter = firstText.boundsInRoot.top + (layout.getLineTop(0) + layout.getLineBottom(0)) / 2f
                        assertEquals("Icon must follow the first rendered line ($font, scale=$scale, $fixture)",
                            firstLineCenter, icon.boundsInRoot.center.y, 0.75f)
                        assertEquals("Icon must retain its 20dp size", 20f, icon.boundsInRoot.height, 0.5f)
                        if (fixture.startsWith("网络")) {
                            assertTrue("The fixture must actually wrap", layout.lineCount > 1)
                        }
                    }
                }
            }
        } finally {
            view.disposeComposition(); recomposer.close(); runner.cancel()
            ThemeStore.saveAppFont(controller.get(), originalFont)
            controller.pause().stop().destroy()
        }
    }

    @Test fun errorReasonsRenderMarkdownWhileRetryStatusRemainsPlainText() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(controller.get())
        val error = "# Request failed\n\n**Access denied**\n\n- Check credentials\n- Try again\n\nError code: `HTTP_403`"
        val status = mutableStateOf(ChatRetryStatus(error, 2, remainingSeconds = 8))
        // Keep parsing deterministic in the headless composition.
        FinishedMarkdownCache.prepare(error, this)
        var frameTime = 0L
        fun frames() {
            repeat(12) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                Snapshot.sendApplyNotifications()
                runCurrent()
                clock.sendFrame(frameTime.also { frameTime += 16_000_000L })
                runCurrent()
                view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, 360, 1000)
            }
        }
        fun nodes(): List<SemanticsNode> {
            fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
            return descend((view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
        }
        fun text(value: String) = nodes().single {
            it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { entry -> entry.text == value }
        }
        try {
            view.setParentCompositionContext(recomposer)
            controller.get().setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme { ReplyErrorRow(status.value) }
                }
            }
            frames()
            assertTrue("Error Markdown syntax must not be displayed literally", nodes().none {
                it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { entry -> entry.text == error }
            })
            text("Request failed")
            val bold = text("Access denied").config[SemanticsProperties.Text].single()
            assertTrue("Strong emphasis must render as bold", bold.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
            text("Check credentials")
            text("Try again")
            val code = nodes().flatMap { it.config.getOrElse(SemanticsProperties.Text) { emptyList() } }
                .single { it.text.contains("HTTP_403") }
            assertFalse("Inline code delimiters must not be displayed", code.text.contains('`'))
            assertTrue("Error codes must retain monospace formatting", code.spanStyles.any { it.item.fontFamily == FontFamily.Monospace })
            text(chatRetryLine(status.value))
            status.value = status.value.copy(error = "A plain network error", stopped = true, remainingSeconds = null)
            frames()
            text("A plain network error")
            text(chatRetryLine(status.value))
        } finally {
            view.disposeComposition(); recomposer.close(); runner.cancel()
            controller.pause().stop().destroy()
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun onlyExclamationIsRedAndRetryMatchesVersionTextBelowReason() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(controller.get())
        var normal = Color.Unspecified
        var red = Color.Unspecified
        var cardFill = Color.Unspecified
        val retry = ChatRetryStatus("fixture error", 2, remainingSeconds = 8)
        try {
            view.setParentCompositionContext(recomposer)
            controller.get().setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme {
                        normal = Theme[colors][onBackgroundColor]
                        red = irioErrorTextColor()
                        cardFill = Theme[colors][secondaryColor]
                        Column {
                            ReplyErrorRow(retry)
                            Text("version reference", color = Theme[colors][mutedColor])
                        }
                    }
                }
            }
            repeat(8) { index ->
                Snapshot.sendApplyNotifications()
                runCurrent()
                clock.sendFrame((index + 1) * 16_000_000L)
                runCurrent()
                view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, 360, 300)
            }
            fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
            val nodes = descend((view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
            fun text(value: String) = nodes.single {
                it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { entry -> entry.text == value }
            }
            fun color(node: SemanticsNode): Color {
                val results = mutableListOf<TextLayoutResult>()
                node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
                return results.single().layoutInput.style.color
            }
            val icon = nodes.single { it.config.getOrElse(ContentDescription) { emptyList() }.contains(tr("重试状态")) }
            val reason = text("fixture error")
            val retryNode = text(chatRetryLine(retry))
            assertEquals(normal, color(reason))
            assertNotEquals(red, color(reason))
            assertEquals(color(text("version reference")), color(retryNode))
            assertTrue(reason.boundsInRoot.left > icon.boundsInRoot.left)
            assertEquals(reason.boundsInRoot.left, retryNode.boundsInRoot.left, 0.5f)
            assertTrue(retryNode.boundsInRoot.top > reason.boundsInRoot.top)
            val capture = Bitmap.createBitmap(360, 300, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(capture))
                assertEquals("The error card must use the theme's secondary background", cardFill.toArgb(), capture.getPixel(180, 4))
                assertNotEquals("The card must keep rounded, unpainted outer corners", cardFill.toArgb(), capture.getPixel(0, 0))
            } finally { capture.recycle() }
            assertEquals("The status icon must sit inside the card padding", 12f, icon.boundsInRoot.left, 0.5f)
        } finally {
            view.disposeComposition()
            recomposer.close()
            runner.cancel()
            controller.pause().stop().destroy()
        }
    }

    @Test fun retryStatesUseTheMatchingLucideIcons() {
        assertEquals("lucide:cloud-alert", replyErrorIcon(ChatRetryStatus("e", remainingSeconds = 4)).name)
        assertEquals("lucide:cloud-backup", replyErrorIcon(ChatRetryStatus("e", running = true)).name)
        assertEquals("lucide:cloud-off", replyErrorIcon(ChatRetryStatus("e", stopped = true)).name)
    }

    @Test fun retryPreferencesPersistAndClampToTheirSupportedRanges() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val context = controller.get()
        ThemeStore.ensureLoaded(context)
        val original = ThemeStore.retryOptions()
        try {
            ThemeStore.saveAutoRetry(context, false)
            ThemeStore.saveRetryLimit(context, 100)
            ThemeStore.saveRetryCooldown(context, -1)
            assertEquals(ChatRetryOptions(false, 20, 0), ThemeStore.retryOptions())
            val prefs = context.getSharedPreferences("appearance_settings", 0)
            assertFalse(prefs.getBoolean("auto_retry", true))
            assertEquals(20, prefs.getInt("retry_limit", 0))
            assertEquals(0, prefs.getInt("retry_cooldown_seconds", -1))
            ThemeStore.saveRetryLimit(context, 0)
            ThemeStore.saveRetryCooldown(context, 999)
            assertEquals(ChatRetryOptions(false, 1, 300), ThemeStore.retryOptions())
        } finally {
            ThemeStore.saveAutoRetry(context, original.enabled)
            ThemeStore.saveRetryLimit(context, original.maxRetries)
            ThemeStore.saveRetryCooldown(context, original.cooldownSeconds)
            controller.pause().stop().destroy()
        }
    }
}
