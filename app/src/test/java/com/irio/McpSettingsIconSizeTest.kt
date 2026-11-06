package com.irio

import android.os.Looper
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
import androidx.compose.ui.semantics.SemanticsProperties
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class McpSettingsIconSizeTest {
    @Test fun integrationListUsesCompactBrandIcons() = runTest {
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
                    AppTheme { McpSettingsScreen(onBack = {}) }
                }
            }
            repeat(8) { index ->
                shadowOf(Looper.getMainLooper()).idle()
                Snapshot.sendApplyNotifications()
                runCurrent()
                clock.sendFrame((index + 1L) * 16_000_000L)
                runCurrent()
                view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, 360, 1000)
            }
            fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
            val nodes = descend((view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
            val todoist = nodes.single {
                it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() } == listOf("Todoist")
            }
            assertEquals("MCP list icons must use 42dp", 42f, todoist.boundsInRoot.width, 0.5f)
            for (brand in listOf("github", "todoist", "irio-docs")) {
                val icon = nodes.single { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "mcp-logo:$brand" }
                assertEquals("$brand width", 42f, icon.boundsInRoot.width, 0.5f)
                assertEquals("$brand height", 42f, icon.boundsInRoot.height, 0.5f)
            }
        } finally {
            view.disposeComposition()
            recomposer.close()
            runner.cancel()
            controller.pause().stop().destroy()
        }
    }
}
