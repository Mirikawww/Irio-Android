package com.irio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProcessStatusRippleTest {
    @Test fun clickableStatusesClipIndicationToLongCapsulesAndStillOpenDetails() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val context = controller.get()
        ThemeStore.ensureLoaded(context)
        val oldThinking = ThemeStore.thinkingBlock
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(context)
        val current = mutableStateOf(ProcessEvent(1, ProcessEventKind.TOOL, toolName = LinuxShellTool.NAME, live = true))
        var opened: ProcessEvent? = null
        var time = 0L
        try {
            ThemeStore.saveThinkingBlock(context, true)
            view.setParentCompositionContext(recomposer)
            context.setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme {
                        CompositionLocalProvider(LocalIndication provides SolidIndication) {
                            Box(Modifier.background(Color.White).padding(24.dp)) {
                                Box(Modifier.testTag("process-status-layout")) {
                                    ProcessEventRow(current.value, onOpen = { opened = it })
                                }
                            }
                        }
                    }
                }
            }
            fun frames() = repeat(8) {
                Snapshot.sendApplyNotifications(); advanceTimeBy(16); runCurrent()
                time += 16_000_000L; clock.sendFrame(time); runCurrent()
                shadowOf(Looper.getMainLooper()).idle()
                view.requestLayout()
                view.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(150, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, 400, 150)
                (view.getChildAt(0) as ViewRootForTest).measureAndLayoutForTest()
            }
            fun descend(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::descend)
            val fixtures = listOf(
                current.value,
                current.value.copy(live = false, content = "fixture output"),
                ProcessEvent(2, ProcessEventKind.THINKING, content = "fixture reasoning", millis = 2000),
                ProcessEvent(3, ProcessEventKind.SEARCH, content = "fixture results", pages = 3),
                ProcessEvent(4, ProcessEventKind.TOOL, toolName = "github_mcp_search_repositories", content = "fixture output"),
                ProcessEvent(5, ProcessEventKind.TOOL, toolName = "todoist_mcp_find_tasks", content = "fixture output"),
                ProcessEvent(6, ProcessEventKind.TOOL, toolName = "irio_docs_mcp_search", content = "fixture failure", toolOutcome = ToolOutcome.FAILED),
            )
            for (event in fixtures) {
                current.value = event
                frames()
                val root = (view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode
                val button = descend(root).single { it.config.contains(SemanticsActions.OnClick) }
                val bounds = button.boundsInRoot
                val layout = descend(root).single {
                    it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "process-status-layout"
                }.boundsInRoot
                val icon = descend(root).single {
                    it.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith("process-status-icon:")
                }.boundsInRoot
                assertEquals("Ripple padding must not indent the status content", layout.left, icon.left, .1f)
                assertEquals("Only the feedback surface may grow horizontally", layout.width + 16f, bounds.width, .1f)
                assertEquals("Ripple padding must not increase status row spacing", layout.height + 8f,
                    bounds.height + if (event.live) 0f else 2f, .1f)
                assertEquals("Capsule needs horizontal space before its icon", 8f, icon.left - bounds.left, .1f)
                assertTrue("Capsule needs top padding", icon.top - bounds.top >= 4f)
                assertTrue("Capsule needs bottom padding", bounds.bottom - icon.bottom >= 4f)
                assertTrue("Status hit area must remain a long content-sized row", bounds.width > bounds.height * 2)
                val downTime = SystemClock.uptimeMillis()
                fun touch(action: Int) {
                    val motion = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                        bounds.center.x, bounds.center.y, 0)
                    try { view.dispatchTouchEvent(motion) } finally { motion.recycle() }
                    frames()
                }
                touch(MotionEvent.ACTION_DOWN)
                val bitmap = Bitmap.createBitmap(400, 150, Bitmap.Config.ARGB_8888)
                try {
                    view.draw(Canvas(bitmap))
                    assertEquals("The indication must retain the long straight middle",
                        android.graphics.Color.MAGENTA, bitmap.getPixel(bounds.center.x.toInt(), bounds.top.toInt() + 1))
                    for ((x, y) in listOf(
                        bounds.left.toInt() + 1 to bounds.top.toInt() + 1,
                        bounds.right.toInt() - 2 to bounds.top.toInt() + 1,
                        bounds.left.toInt() + 1 to bounds.bottom.toInt() - 2,
                        bounds.right.toInt() - 2 to bounds.bottom.toInt() - 2,
                    )) {
                        assertEquals("${event.toolName}/${event.kind} indication must have capsule ends at ($x, $y)",
                            android.graphics.Color.WHITE, bitmap.getPixel(x, y))
                    }
                } finally { bitmap.recycle() }
                touch(MotionEvent.ACTION_CANCEL)
                assertTrue(button.config[SemanticsActions.OnClick].action!!.invoke())
                assertEquals(event, opened)
            }
        } finally {
            view.disposeComposition()
            recomposer.close()
            runner.cancel()
            ThemeStore.saveThinkingBlock(context, oldThinking)
            controller.pause().stop().destroy()
        }
    }

    // Deterministic paint verifies the actual clickable's clipping, not platform ripple timing.
    private data object SolidIndication : IndicationNodeFactory {
        override fun create(interactionSource: InteractionSource): DelegatableNode = object : Modifier.Node(), DrawModifierNode {
            override fun ContentDrawScope.draw() {
                drawContent()
                drawRect(Color.Magenta)
            }
        }
    }
}
