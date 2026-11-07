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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.composables.ui.theme.colors
import com.composables.ui.theme.onBackgroundColor
import com.composables.ui.theme.onPrimaryColor
import com.composables.ui.theme.primaryColor
import com.composeunstyled.theme.Theme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class SettingsSearchHighlightTest {
    @Test fun matchedRangesUseLiveAccentAndContrastingTextAcrossThemes() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val context = controller.get()
        ThemeStore.ensureLoaded(context)
        val oldAccent = ThemeStore.accent
        val oldTheme = ThemeStore.preference
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(context)
        var expectedAccent = Color.Unspecified
        var expectedInk = Color.Unspecified
        var expectedBody = Color.Unspecified
        var time = 0L
        try {
            view.setParentCompositionContext(recomposer)
            context.setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme {
                        expectedAccent = Theme[colors][primaryColor]
                        expectedInk = Theme[colors][onPrimaryColor]
                        expectedBody = Theme[colors][onBackgroundColor]
                        SettingsSearchTitle("搜索设置搜索", listOf(0..1, 4..5), expectedBody)
                    }
                }
            }
            for (theme in listOf(ThemePreference.LIGHT, ThemePreference.DARK, ThemePreference.AMOLED)) {
                ThemeStore.save(context, theme)
                for (accent in AccentColor.entries) {
                    ThemeStore.saveAccent(context, accent)
                    repeat(8) {
                        Snapshot.sendApplyNotifications(); runCurrent()
                        time += 16_000_000L; clock.sendFrame(time); runCurrent()
                        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                        view.requestLayout()
                        view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY))
                        view.layout(0, 0, 360, 200)
                        val bitmap = android.graphics.Bitmap.createBitmap(360, 200, android.graphics.Bitmap.Config.ARGB_8888)
                        try { view.draw(android.graphics.Canvas(bitmap)) } finally { bitmap.recycle() }
                    }
                    fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
                    val node = descend((view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
                        .single { it.config.contains(SemanticsProperties.Text) }
                    val text = node.config[SemanticsProperties.Text].single()
                    assertEquals("搜索设置搜索", text.text)
                    assertEquals(listOf(0 to 2, 4 to 6), text.spanStyles.map { it.start to it.end })
                    text.spanStyles.forEach { span ->
                        assertEquals("$theme / $accent match background", expectedAccent, span.item.background)
                        assertEquals("$theme / $accent match foreground", expectedInk, span.item.color)
                    }
                    val layouts = mutableListOf<TextLayoutResult>()
                    node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
                    assertEquals("Unmatched text keeps its ordinary foreground", expectedBody,
                        layouts.single().layoutInput.style.color)
                }
            }
        } finally {
            view.disposeComposition(); recomposer.close(); runner.cancel()
            controller.pause().stop().destroy()
            ThemeStore.saveAccent(context, oldAccent)
            ThemeStore.save(context, oldTheme)
        }
    }
}
