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
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
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

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ModelSettingsLayoutTest {
    @Test
    fun toolsEntryTitleSharesTheSettingsTextStartEdge() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(controller.get())
        try {
            view.setParentCompositionContext(recomposer)
            controller.get().setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme { ModelSettingsScreen(onBack = {}) }
                }
            }
            repeat(8) { index ->
                Snapshot.sendApplyNotifications()
                runCurrent()
                clock.sendFrame((index + 1) * 16_000_000L)
                runCurrent()
                view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, 360, 2000)
            }
            fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
            val root = (view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode
            val nodes = descend(root)
            fun title(text: String) = nodes.single {
                it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { value -> value.text == tr(text) }
            }
            assertEquals("Tools must align with adjacent settings titles", title("单轮工具调用").boundsInRoot.left, title("工具").boundsInRoot.left, 0.5f)
            val layouts = mutableListOf<TextLayoutResult>()
            title("工具").config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
            assertEquals("The Tools entry must use normal weight, not inherit the Button's medium weight",
                FontWeight.Normal, layouts.single().layoutInput.style.fontWeight)
            layouts.clear()
            title("模型").config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
            assertEquals("The Models page heading must remain unchanged", FontWeight.Medium,
                layouts.single().layoutInput.style.fontWeight)
        } finally {
            view.disposeComposition()
            recomposer.close()
            runner.cancel()
            controller.pause().stop().destroy()
        }
    }
}
