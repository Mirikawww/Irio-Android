package com.irio

import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.composables.ui.components.Button
import com.composables.ui.components.ButtonStyle
import com.composables.ui.components.Text
import com.composables.ui.theme.colors
import com.composables.ui.theme.onBackgroundColor
import com.composables.ui.theme.onDestructiveColor
import com.composables.ui.theme.onPanelColor
import com.composables.ui.theme.onPrimaryColor
import com.composables.ui.theme.onSecondaryColor
import com.composables.ui.theme.mutedColor
import com.composeunstyled.LocalContentColor
import com.composeunstyled.theme.Theme
import dev.chrisbanes.haze.rememberHazeState
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

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TextForegroundThemeTest {
    @Test fun rootDefaultsIgnoreExternalBlackButSemanticButtonColorsRemainIntact() = verify { harness ->
        var expectedBody = Color.Unspecified
        var primary = Color.Unspecified
        var secondary = Color.Unspecified
        var destructive = Color.Unspecified
        harness.content {
            expectedBody = Theme[colors][onBackgroundColor]
            primary = Theme[colors][onPrimaryColor]
            secondary = Theme[colors][onSecondaryColor]
            destructive = Theme[colors][onDestructiveColor]
            Column {
                // Intentionally omit color: this is the fallback regression probe.
                Text("implicit foreground")
                Button(onClick = {}, style = ButtonStyle.Primary) { Text("primary") }
                Button(onClick = {}, style = ButtonStyle.Secondary) { Text("secondary") }
                Button(onClick = {}, style = ButtonStyle.Destructive) { Text("destructive") }
            }
        }
        harness.frames()
        assertEquals("AppTheme must not inherit stale black from its host", expectedBody, harness.color("implicit foreground"))
        assertEquals(primary, harness.color("primary"))
        assertEquals(secondary, harness.color("secondary"))
        assertEquals(destructive, harness.color("destructive"))
    }

    @Test fun providerSaveErrorUsesReadableErrorColorAcrossThemes() = verify { harness ->
        var errorInk = Color.Unspecified
        var saveInk = Color.Unspecified
        var deleteInk = Color.Unspecified
        harness.content {
            errorInk = irioErrorTextColor()
            saveInk = Theme[colors][onPrimaryColor]
            deleteInk = Theme[colors][onDestructiveColor]
            ProviderConfigPage(
                name = rememberTextFieldState("Fixture"),
                endpoint = rememberTextFieldState("https://example.test"),
                apiKey = rememberTextFieldState(), headers = rememberProviderHeadersState(),
                protocol = ProviderProtocol.CHAT_COMPLETIONS, protocolMenuOpen = false,
                onProtocolMenuExpanded = {}, onProtocol = {}, formError = "HTTP 403: fixture error",
                saving = false, onSave = {}, onDelete = {}, scroll = rememberScrollState(),
                hazeState = rememberHazeState(), capsuleHeight = 0.dp, contentPaddingBottom = 0.dp,
            )
        }
        harness.frames()
        assertEquals("Provider save failures must not use inherited black", errorInk, harness.color("HTTP 403: fixture error"))
        assertEquals(saveInk, harness.color(tr("保存")))
        assertEquals(deleteInk, harness.color(tr("删除提供商")))
    }

    @Test fun headerValidationUsesTextErrorColorNotTheDestructiveButtonFill() = verify { harness ->
        var expected = Color.Unspecified
        harness.content {
            expected = irioErrorTextColor()
            val headers = rememberProviderHeadersState(listOf(ProviderHeader("invalid name", "fixture")))
            headers.validatedHeaders()
            ProviderHeadersEditor(headers)
        }
        harness.frames()
        assertEquals(expected, harness.color(tr("请求头名称无效")))
    }

    @Test fun providerPlaceholderAndSharedDialogTitleHaveExplicitThemeColors() = verify { harness ->
        var placeholder = Color.Unspecified
        var title = Color.Unspecified
        harness.content {
            placeholder = Theme[colors][mutedColor]
            title = Theme[colors][onPanelColor]
            Column {
                DialogTitle("dialog title fixture")
                ProviderField("provider placeholder fixture", rememberTextFieldState(),
                    androidx.compose.ui.text.input.KeyboardType.Text)
            }
        }
        harness.frames()
        assertEquals(placeholder, harness.color("provider placeholder fixture"))
        assertEquals(title, harness.color("dialog title fixture"))
    }

    @Test fun providerEmptyModelsUseThePageForegroundAcrossThemes() = verify { harness ->
        var expected = Color.Unspecified
        harness.content {
            expected = Theme[colors][onBackgroundColor]
            ProviderModelsPage(provider = ProviderConfig("fixture", "Fixture", "https://example.test", ""),
                refreshing = false, refreshDiff = ModelRefreshDiff(), onRefresh = {}, onModelCheckedChange = { _, _ -> },
                scroll = rememberScrollState(), hazeState = rememberHazeState(), capsuleHeight = 0.dp,
                contentPaddingBottom = 0.dp)
        }
        harness.frames()
        assertEquals(expected, harness.color(tr("还没有模型")))
    }

    private fun verify(check: (Harness) -> Unit) = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val context = controller.get()
        ThemeStore.ensureLoaded(context)
        val old = ThemeStore.preference
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(context)
        try {
            view.setParentCompositionContext(recomposer)
            context.setContentView(view)
            var time = 0L
            val harness = Harness(view) {
                repeat(12) {
                    shadowOf(Looper.getMainLooper()).idle()
                    Snapshot.sendApplyNotifications(); runCurrent()
                    clock.sendFrame(time.also { time += 16_000_000L }); runCurrent()
                    view.measure(View.MeasureSpec.makeMeasureSpec(420, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY))
                    view.layout(0, 0, 420, 1600)
                }
            }
            for (theme in listOf(ThemePreference.DARK, ThemePreference.AMOLED, ThemePreference.LIGHT)) {
                ThemeStore.save(context, theme)
                check(harness)
                view.disposeComposition()
            }
        } finally {
            view.disposeComposition(); recomposer.close(); runner.cancel()
            ThemeStore.save(context, old)
            controller.pause().stop().destroy()
        }
    }

    private class Harness(private val view: ComposeView, val frames: () -> Unit) {
        fun content(content: @Composable () -> Unit) {
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f), LocalContentColor provides Color.Black) {
                    AppTheme(content)
                }
            }
        }
        fun color(label: String): Color {
            fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
            val nodes = descendants((view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
            val node = nodes.single { it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { t -> t.text == label } }
            val result = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(result)
            return result.single().layoutInput.style.color
        }
    }
}
