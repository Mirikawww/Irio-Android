package com.irio

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
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
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ToolSettingsSnackbarTest {
    @Test
    fun repeatingTheSameMessageRestartsTheDismissCountdown() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(controller.get())
        val visible = mutableStateOf(true)
        val restartKey = mutableStateOf(0)
        var time = 0L
        try {
            controller.get().setContentView(view)
            view.setParentCompositionContext(recomposer)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme {
                        Box(Modifier.fillMaxSize()) {
                            IrioSnackbar(
                                visible = visible.value,
                                message = "重复提示",
                                bottomInset = 0.dp,
                                onDismiss = { visible.value = false },
                                restartKey = restartKey.value,
                            )
                        }
                    }
                }
            }
            fun frame() {
                Snapshot.sendApplyNotifications()
                advanceTimeBy(16L)
                runCurrent()
                time += 16_000_000L
                clock.sendFrame(time)
                runCurrent()
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                view.requestLayout()
                view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, 360, 640)
            }
            repeat(8) { frame() }
            advanceTimeBy(4_500L)
            runCurrent()
            restartKey.value++
            frame()
            advanceTimeBy(1_000L)
            runCurrent()
            assertTrue("The repeated snackbar must remain visible after one second", visible.value)
            advanceTimeBy(4_200L)
            runCurrent()
            assertFalse("The repeated snackbar must dismiss five seconds after the latest trigger", visible.value)
        } finally {
            view.disposeComposition()
            recomposer.close()
            runner.cancel()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun newMessageCompletesTheOldExitBeforeEntering() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(controller.get())
        val visible = mutableStateOf(true)
        val message = mutableStateOf("旧提示")
        var time = 0L
        try {
            controller.get().setContentView(view)
            view.setParentCompositionContext(recomposer)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme {
                        Box(Modifier.fillMaxSize()) {
                            IrioSnackbar(visible.value, message.value, 0.dp, onDismiss = { visible.value = false })
                        }
                    }
                }
            }
            fun frame() {
                Snapshot.sendApplyNotifications()
                advanceTimeBy(16L)
                runCurrent()
                time += 16_000_000L
                clock.sendFrame(time)
                runCurrent()
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                view.requestLayout()
                view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, 360, 640)
            }
            repeat(8) { frame() }
            assertNotNull(textNode(view, "旧提示"))

            message.value = "新提示"
            repeat(8) { frame() }
            assertNotNull("The old message must remain during its exit animation", textNode(view, "旧提示"))
            assertNull("The new message must wait for the old exit", textNode(view, "新提示"))

            repeat(20) { frame() }
            assertNull("The old message must finish exiting", textNode(view, "旧提示"))
            assertNotNull("The new message must enter after the old exit", textNode(view, "新提示"))
        } finally {
            view.disposeComposition()
            recomposer.close()
            runner.cancel()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun toolNoticeUsesTheSharedSnackbarEntranceAndKeepsTextDuringExit() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val screen = ComposeView(controller.get())
        val reference = ComposeView(controller.get())
        val referenceVisible = mutableStateOf(false)
        val entry = ToolSettingsEntry(MemoryTool.GET, "读取记忆", "Read memory", ToolBlockedReason.MEMORY)
        val message = entry.blockedReason!!.message()
        var time = 0L
        try {
            val container = android.widget.FrameLayout(controller.get())
            controller.get().setContentView(container)
            listOf(screen, reference).forEach { view ->
                view.setParentCompositionContext(recomposer)
                container.addView(view)
            }
            screen.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme { ToolSettingsScreen(onBack = {}, loadTools = { listOf(entry) }) }
                }
            }
            reference.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme {
                        Box(Modifier.fillMaxSize()) {
                            IrioSnackbar(referenceVisible.value, message, 0.dp, onDismiss = { referenceVisible.value = false })
                        }
                    }
                }
            }
            fun frame() {
                Snapshot.sendApplyNotifications()
                runCurrent()
                time += 16_000_000L
                clock.sendFrame(time)
                runCurrent()
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                listOf(screen, reference).forEach { view ->
                    view.requestLayout()
                    view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
                    view.layout(0, 0, 360, 640)
                }
            }
            repeat(8) { frame() }
            val header = nodes(screen).single { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "tool-expand:${entry.name}" }
            header.config[SemanticsActions.OnClick].action!!.invoke()
            referenceVisible.value = true
            val widths = (0 until 40).map {
                frame()
                width(screen, message) to width(reference, message)
            }
            assertTrue("The reference must exercise intermediate animation frames", widths.any { it.second > 1f && it.second < 320f })
            widths.forEachIndexed { index, (actual, expected) ->
                assertEquals("Tool snackbar must reuse the same entrance as the existing settings at frame $index", expected, actual, 0.5f)
            }
            // Both snackbars auto-dismiss after five seconds; exit must animate the original text.
            advanceTimeBy(5_001L)
            runCurrent()
            frame()
            assertNotNull("Do not clear notice text while the shared snackbar is exiting", textNode(screen, message))
            repeat(50) { frame() }
            assertNull("The snackbar must finish its shared exit", textNode(screen, message))
        } finally {
            screen.disposeComposition()
            reference.disposeComposition()
            recomposer.close()
            runner.cancel()
            controller.pause().stop().destroy()
        }
    }

    private fun width(view: View, message: String): Float = textNode(view, message)?.boundsInRoot?.width ?: 0f

    private fun textNode(view: View, message: String): SemanticsNode? = nodes(view).firstOrNull {
        it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { text -> text.text == message }
    }

    private fun nodes(view: View): List<SemanticsNode> {
        fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
        val own = if (view is ViewRootForTest) descend(view.semanticsOwner.unmergedRootSemanticsNode) else emptyList()
        val children = if (view is ViewGroup) (0 until view.childCount).flatMap { nodes(view.getChildAt(it)) } else emptyList()
        return own + children
    }
}
