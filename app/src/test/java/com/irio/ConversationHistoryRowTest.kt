package com.irio

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.composables.ui.components.ButtonSize
import com.composables.ui.components.ButtonStyle
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
class ConversationHistoryRowTest {
    @Test fun compactHistoryIsSingleLineEllipsizedAndStillClickable() = checkRow(compact = true)

    @Test fun ordinaryRowsKeepLargeSizeAndWrapping() = checkRow(compact = false)

    private fun checkRow(compact: Boolean) = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val context = controller.get()
        ThemeStore.ensureLoaded(context)
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(context)
        val label = "A very long conversation title that should not expand the history row ".repeat(3)
        var clicks = 0
        try {
            view.setParentCompositionContext(recomposer)
            context.setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme {
                        if (compact) {
                            ListRowButton(
                                label = label, onClick = { clicks++ },
                                style = ButtonStyle.Ghost, buttonSize = ButtonSize.Small,
                                labelMaxLines = 1, icon = IrioIcons.Pin, active = true,
                                contentPadding = PaddingValues(start = 16.dp, end = 56.dp),
                            )
                        } else {
                            ListRowButton(label = label, onClick = { clicks++ })
                        }
                    }
                }
            }
            repeat(8) { frame ->
                Snapshot.sendApplyNotifications(); runCurrent()
                clock.sendFrame((frame + 1) * 16_000_000L); runCurrent()
                view.measure(View.MeasureSpec.makeMeasureSpec(280, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.AT_MOST))
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            }
            fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
            val nodes = descend((view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
            val text = nodes.single {
                it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.singleOrNull()?.text == label
            }
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue(text.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts))
            val layout = layouts.single()
            val button = nodes.single { it.config.contains(SemanticsActions.OnClick) }
            if (compact) {
                assertEquals("History title must stay on one line", 1, layout.lineCount)
                assertTrue("Long history titles must end in ellipsis", layout.isLineEllipsized(0))
                assertEquals("History row must use the compact touch size", 44, button.size.height)
                assertTrue("Generating indicator padding must be reserved", text.boundsInRoot.right <= 224f)
            } else {
                assertTrue("Other list rows must still wrap", layout.lineCount > 1)
                assertTrue("Other list rows must retain their large sizing", button.size.height >= 48)
            }
            assertTrue(button.config[SemanticsActions.OnClick].action!!.invoke())
            assertEquals(1, clicks)
        } finally {
            view.disposeComposition()
            recomposer.close()
            runner.cancel()
            controller.pause().stop().destroy()
        }
    }
}
