package com.irio

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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProcessStatusIconTest {
    @Test fun everyBuiltinToolHasExplicitArtwork() {
        val names = ToolCatalog.entries(ToolFeatureState()).map { it.name }
            .filterNot { it.startsWith(GitHubMcpToolProvider.PREFIX) }
        for (name in names) {
            val vector = processStatusVector(ProcessEvent(1, ProcessEventKind.TOOL, toolName = name))
            assertNotEquals("Built-in $name must not use the unknown-tool fallback", IrioIcons.Wrench, vector)
        }
        val fixtures = mapOf(
            TimeTool.NAME to IrioIcons.Clock,
            UserLocationTool.NAME to IrioIcons.MapPin,
            ToolGuideSearchTool.NAME to IrioIcons.BookOpen,
            MemoryTool.GET to IrioIcons.Brain,
            MemoryTool.UPDATE to IrioIcons.Brain,
            WebFetchTool.NAME to IrioIcons.Globe,
            LinuxBackgroundTool.NAME to IrioIcons.Terminal,
        )
        fixtures.forEach { (name, expected) ->
            assertSame(expected, processStatusVector(ProcessEvent(1, ProcessEventKind.TOOL, toolName = name)))
        }
    }

    @Test fun statusRowsHaveLeadingIconsInLiveCompletedAndCollapsedThinkingModes() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val context = controller.get()
        ThemeStore.ensureLoaded(context)
        val oldThinking = ThemeStore.thinkingBlock
        val oldTheme = ThemeStore.preference
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(context)
        val current = mutableStateOf(ProcessEvent(1, ProcessEventKind.THINKING, live = true))
        val density = mutableStateOf(Density(1f))
        var opened: ProcessEvent? = null
        var time = 0L
        try {
            view.setParentCompositionContext(recomposer)
            context.setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides density.value) {
                    AppTheme { ProcessEventRow(current.value, onOpen = { opened = it }) }
                }
            }
            fun frames() = repeat(8) {
                Snapshot.sendApplyNotifications(); runCurrent()
                time += 16_000_000L; clock.sendFrame(time); runCurrent()
                view.requestLayout()
                view.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.AT_MOST))
                view.layout(0, 0, 320, view.measuredHeight)
                (view.getChildAt(0) as ViewRootForTest).measureAndLayoutForTest()
            }
            fun descend(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::descend)
            fun nodes() = descend((view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
            fun tag(n: SemanticsNode) = n.config.getOrElse(SemanticsProperties.TestTag) { "" }
            fun assertLeading(artwork: String, sweeping: Boolean) {
                val all = nodes()
                val icons = all.filter { tag(it).startsWith("process-status-icon:") }
                assertEquals("Each visible status needs exactly one leading icon", 1, icons.size)
                val icon = icons.single()
                assertEquals("process-status-icon:$artwork", tag(icon))
                assertEquals(16f, icon.boundsInRoot.width, .1f)
                assertEquals(16f, icon.boundsInRoot.height, .1f)
                val label = all.first { it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.isNotEmpty() }
                assertTrue("Icon must be to the left of the label", icon.boundsInRoot.right < label.boundsInRoot.left)
                assertEquals("Icon and label must be vertically centered", icon.boundsInRoot.center.y,
                    label.boundsInRoot.center.y, 1f)
                assertEquals(if (sweeping) 1 else 0, all.count { tag(it) == "status-sweep" })
            }
            ThemeStore.saveThinkingBlock(context, true)
            frames()
            assertLeading("brain", sweeping = true)
            ThemeStore.saveThinkingBlock(context, false)
            frames()
            assertLeading("brain", sweeping = true)
            current.value = current.value.copy(live = false, millis = 2000)
            frames()
            assertTrue("Collapsed completed thinking stays hidden", nodes().none { tag(it).startsWith("process-status-icon:") })
            ThemeStore.saveThinkingBlock(context, true)
            frames()
            assertLeading("brain", sweeping = false)

            val fixtures = listOf(
                ProcessEvent(2, ProcessEventKind.SEARCH, searchQuery = "fixture") to "search",
                ProcessEvent(3, ProcessEventKind.TOOL, toolName = LinuxShellTool.NAME) to "terminal",
                ProcessEvent(4, ProcessEventKind.TOOL, toolName = "github_mcp_search_repositories") to "github",
                ProcessEvent(5, ProcessEventKind.TOOL, toolName = "todoist_mcp_find_tasks") to "todoist",
                ProcessEvent(6, ProcessEventKind.TOOL, toolName = "irio_docs_mcp_search") to "irio-docs",
                ProcessEvent(7, ProcessEventKind.TOOL, toolName = "unknown_future_tool") to "wrench",
            )
            for ((event, artwork) in fixtures) {
                current.value = event.copy(live = true)
                frames()
                assertLeading(artwork, sweeping = true)
                if (event.toolName == LinuxShellTool.NAME) {
                    nodes().single { it.config.contains(SemanticsActions.OnClick) }
                        .config[SemanticsActions.OnClick].action!!.invoke()
                    assertEquals(current.value, opened)
                }
                for (outcome in listOf(ToolOutcome.SUCCESS, ToolOutcome.FAILED, ToolOutcome.STOPPED)) {
                    current.value = event.copy(content = "fixture output", toolOutcome = outcome)
                    frames()
                    assertLeading(artwork, sweeping = false)
                    val button = nodes().single { it.config.contains(SemanticsActions.OnClick) }
                    assertTrue(button.config[SemanticsActions.OnClick].action!!.invoke())
                    assertEquals(current.value, opened)
                }
            }
            current.value = ProcessEvent(8, ProcessEventKind.TOOL, toolName = MERGED_TOOL_CALLS_NAME,
                content = "Merged tools fixture", live = true)
            frames()
            assertLeading("wrench", sweeping = false)
            val longHint = "正在查询工具调用的详细信息和相关资料 ".repeat(24)
            val longStatuses = fixtures.flatMap { (event, _) ->
                listOf(event.copy(live = true, displayText = longHint),
                    event.copy(content = "fixture output", displayText = longHint, toolOutcome = ToolOutcome.SUCCESS))
            } + listOf(
                ProcessEvent(9, ProcessEventKind.TOOL, toolName = MERGED_TOOL_CALLS_NAME, content = longHint),
                ProcessEvent(10, ProcessEventKind.TOOL, toolName = VideoGenTool.NAME,
                    content = """{"status":"running"}""", displayText = longHint, toolOutcome = ToolOutcome.SUCCESS),
            )
            for (fontScale in listOf(1f, 2f)) {
                density.value = Density(1f, fontScale)
                for (event in longStatuses) {
                    current.value = event
                    frames()
                    val text = nodes().single {
                        it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == longHint }
                    }
                    val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                    assertTrue(text.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts))
                    val layout = layouts.single()
                    assertEquals("Tool hint must explicitly cap maxLines: ${event.toolName}/${event.live}",
                        1, layout.layoutInput.maxLines)
                    assertEquals("Long tool hints must stay on one line", 1, layout.lineCount)
                    assertTrue("Long tool hints must ellipsize rather than wrap or clip", layout.isLineEllipsized(0))
                    assertTrue("Single-line hints must remain inside the transcript", text.boundsInRoot.right <= 320f)
                    nodes().filter { it.config.contains(SemanticsActions.OnClick) }.forEach {
                        assertTrue(it.config[SemanticsActions.OnClick].action!!.invoke())
                        assertEquals(event, opened)
                    }
                }
            }
            for (theme in listOf(ThemePreference.LIGHT, ThemePreference.DARK, ThemePreference.AMOLED)) {
                ThemeStore.save(context, theme)
                density.value = Density(1f, 2f)
                for ((event, artwork) in fixtures.filter { it.second in setOf("github", "todoist", "irio-docs") }) {
                    current.value = event.copy(live = true)
                    frames()
                    assertLeading(artwork, sweeping = true)
                    assertTrue("Large text must remain inside the row", nodes().filter {
                        it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.isNotEmpty()
                    }.all { it.boundsInRoot.right <= 320f })
                }
            }
        } finally {
            view.disposeComposition()
            recomposer.close()
            runner.cancel()
            ThemeStore.saveThinkingBlock(context, oldThinking)
            ThemeStore.save(context, oldTheme)
            controller.pause().stop().destroy()
        }
    }
}
