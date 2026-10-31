package com.irio

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import com.composables.ui.components.BottomSheetDetent
import com.composables.ui.components.rememberBottomSheetState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ToolFailureSheetTest {
    @Test fun failedToolsSearchAndShellShowTheErrorAndOriginalInput() = runTest {
        for ((kind, name) in listOf(
            ProcessEventKind.TOOL to "missing_tool",
            ProcessEventKind.SEARCH to WebSearchTool.NAME,
            ProcessEventKind.TOOL to LinuxShellTool.NAME,
        )) {
            val input = """{"query":"model supplied input","nested":{"limit":7}}"""
            withSheet(kind, name, input) { texts ->
                assertTrue("$name must show the existing failure", "fixture failure" in texts)
                assertTrue("$name must label the error", tr("错误信息") in texts)
                assertTrue("$name must label the original arguments", tr("输入参数") in texts)
                assertTrue("$name must show the complete structured arguments", JSONObject(input).toString(2) in texts)
            }
        }
    }

    @Test fun malformedInputIsShownVerbatimAndMissingLegacyInputIsNotInvented() = runTest {
        for (input in listOf("{invalid-json", "{}", null)) {
            withSheet(ProcessEventKind.TOOL, "missing_tool", input) { texts ->
                assertTrue("fixture failure" in texts)
                assertTrue(if (input == null) tr("未记录输入参数") in texts else input in texts)
                if (input == null) assertFalse("Missing old data must not be presented as an empty model object", "{}" in texts)
            }
        }
    }

    private fun kotlinx.coroutines.test.TestScope.withSheet(
        kind: ProcessEventKind, name: String, input: String?, check: (List<String>) -> Unit,
    ) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val host = ComposeView(activity.get())
        val json = JSONObject().put("id", 1).put("kind", kind.name).put("toolName", name)
            .put("content", "fixture failure").put("toolOutcome", "FAILED")
        input?.let { json.put("toolArguments", it) }
        val event = processEventsFrom(JSONObject().put("process", JSONArray().put(json))).single()
        var time = 0L
        try {
            host.setParentCompositionContext(recomposer)
            activity.get().setContentView(host)
            host.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme {
                        val state = rememberBottomSheetState(initialDetent = BottomSheetDetent.FullyExpanded)
                        ProcessEventSheet(event, state, {})
                    }
                }
            }
            repeat(3) { runCurrent(); time += 16_000_000L; clock.sendFrame(time); runCurrent() }
            val root = ShadowDialog.getLatestDialog().window!!.decorView
            repeat(12) {
                Snapshot.sendApplyNotifications()
                runCurrent(); time += 16_000_000L; clock.sendFrame(time); runCurrent()
                shadowOf(Looper.getMainLooper()).idle()
                root.requestLayout()
                root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, 400, 800)
            }
            fun views(view: View): List<View> = listOf(view) +
                if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
            fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
            val texts = views(root).filterIsInstance<ViewRootForTest>().flatMap {
                nodes(it.semanticsOwner.unmergedRootSemanticsNode)
            }.flatMap { it.config.getOrElse(SemanticsProperties.Text) { emptyList() } }.map { it.text }
            check(texts)
        } finally {
            host.disposeComposition(); recomposer.close(); runner.cancel()
            activity.pause().stop().destroy()
        }
    }
}
