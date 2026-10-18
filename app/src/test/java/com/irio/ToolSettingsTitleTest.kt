package com.irio

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
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

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ToolSettingsTitleTest {
    @Test fun toolPageTitleUsesNormalWeightWithoutChangingOtherSettingsTitles() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val screen = ComposeView(controller.get())
        val reference = ComposeView(controller.get())
        try {
            val container = android.widget.FrameLayout(controller.get())
            controller.get().setContentView(container)
            for (view in listOf(screen, reference)) {
                view.setParentCompositionContext(recomposer)
                container.addView(view)
            }
            screen.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme { ToolSettingsScreen(onBack = {}, loadTools = { emptyList() }) }
                }
            }
            reference.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme { SettingsHazeScaffold(title = "其他设置", onBack = {}) { _, _ -> } }
                }
            }
            repeat(8) { index ->
                Snapshot.sendApplyNotifications()
                runCurrent()
                clock.sendFrame((index + 1L) * 16_000_000L)
                runCurrent()
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                for (view in listOf(screen, reference)) {
                    view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
                    view.layout(0, 0, 360, 640)
                }
            }
            val title = layout(screen, "工具").layoutInput.style
            assertEquals("Only the Tools page title should lose its medium weight", FontWeight.Normal, title.fontWeight)
            assertEquals(18.sp, title.fontSize)
            assertEquals(22.sp, title.lineHeight)
            assertEquals(FontWeight.Medium, layout(reference, "其他设置").layoutInput.style.fontWeight)
        } finally {
            screen.disposeComposition()
            reference.disposeComposition()
            recomposer.close()
            runner.cancel()
            controller.pause().stop().destroy()
        }
    }

    private fun layout(view: View, text: String): TextLayoutResult {
        fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
        fun nodes(view: View): List<SemanticsNode> {
            val own = if (view is ViewRootForTest) descend(view.semanticsOwner.unmergedRootSemanticsNode) else emptyList()
            val children = if (view is ViewGroup) (0 until view.childCount).flatMap { nodes(view.getChildAt(it)) } else emptyList()
            return own + children
        }
        val node = nodes(view).first { it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == text } }
        val results = mutableListOf<TextLayoutResult>()
        assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results))
        return results.single()
    }
}
