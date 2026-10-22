package com.irio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import com.composables.ui.theme.colors
import com.composables.ui.theme.onPanelColor
import com.composeunstyled.theme.Theme
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.LocalHazeBlurStyle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, shadows = [SettingsImeFocusTest.NoCredentialWrites::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SpeechSettingsThemeTest {
    @Test fun sttArrowsUseTheDarkThemeForeground() = verifyArrows(ThemePreference.DARK)
    @Test fun sttArrowsUseTheLightThemeForeground() = verifyArrows(ThemePreference.LIGHT)

    private fun verifyArrows(theme: ThemePreference) = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val context = controller.get()
        ThemeStore.ensureLoaded(context)
        val previousTheme = ThemeStore.preference
        val previousLanguage = ThemeStore.language
        ThemeStore.save(context, theme)
        ThemeStore.saveLanguage(context, AppLanguage.ZH_CN)
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val host = ComposeView(context)
        val noBlur = HazeBlurStyle { blurEnabled(false) }
        var expectedColor = 0
        try {
            host.setParentCompositionContext(recomposer)
            context.setContentView(host)
            host.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f), LocalHazeBlurStyle provides noBlur) {
                    AppTheme {
                        expectedColor = Theme[colors][onPanelColor].toArgb()
                        SpeechSettingsScreen(onBack = {})
                    }
                }
            }
            repeat(8) { frame ->
                Snapshot.sendApplyNotifications()
                runCurrent()
                clock.sendFrame((frame + 1L) * 16_000_000L)
                runCurrent()
                shadowOf(Looper.getMainLooper()).idle()
                host.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY))
                host.layout(0, 0, 360, 900)
            }
            fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
            val nodes = descend((host.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
            val bitmap = Bitmap.createBitmap(360, 900, Bitmap.Config.ARGB_8888)
            try {
                host.draw(Canvas(bitmap))
                listOf("选择提供商", "选择模型").forEach { label ->
                    val arrow = nodes.single {
                        label in it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
                    }
                    val bounds = arrow.boundsInRoot
                    var foregroundPixels = 0
                    for (y in bounds.top.toInt().coerceAtLeast(0) until bounds.bottom.toInt().coerceAtMost(bitmap.height)) {
                        for (x in bounds.left.toInt().coerceAtLeast(0) until bounds.right.toInt().coerceAtMost(bitmap.width)) {
                            val actual = bitmap.getPixel(x, y)
                            // Lucide draws a 2-unit anti-aliased stroke: unlike the old filled
                            // Material chevron, an on-color pixel need not match exactly.
                            val closeToForeground = listOf(16, 8, 0).all { shift ->
                                kotlin.math.abs((actual ushr shift and 0xff) - (expectedColor ushr shift and 0xff)) <= 64
                            }
                            if (closeToForeground) foregroundPixels++
                        }
                    }
                    assertTrue("$label must render using onPanelColor in $theme (matching pixels=$foregroundPixels)", foregroundPixels > 0)
                }
            } finally {
                bitmap.recycle()
            }
        } finally {
            host.disposeComposition()
            recomposer.close()
            runner.cancel()
            ThemeStore.save(context, previousTheme)
            ThemeStore.saveLanguage(context, previousLanguage)
            controller.pause().stop().destroy()
        }
    }
}
