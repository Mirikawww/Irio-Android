package com.irio

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import com.composables.ui.theme.colors
import com.composables.ui.theme.backgroundColor as backgroundColorToken
import com.composeunstyled.theme.Theme
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
class ComposerFooterBackgroundTest {
    @Test
    fun fullscreenActionsUseTheOpaqueThemeBackgroundWithoutHaze() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(controller.get())
        val expanded = mutableStateOf(true)
        val state = TextFieldState("input text ".repeat(100), TextRange(2, 9))
        var frameTime = 0L
        var expectedBackground = Color.Unspecified
        try {
            view.setParentCompositionContext(recomposer)
            controller.get().setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme {
                        expectedBackground = Theme[colors][backgroundColorToken]
                        ComposerField(
                            state = state,
                            placeholder = "",
                            modifier = Modifier.fillMaxWidth().then(if (expanded.value) Modifier.height(400.dp) else Modifier),
                            expanded = expanded.value,
                            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = if (expanded.value) Int.MAX_VALUE else 4),
                            expandedFooter = {
                                Column(Modifier.fillMaxWidth()) {
                                    Box(Modifier.fillMaxWidth().height(36.dp))
                                    Box(Modifier.fillMaxWidth().height(56.dp))
                                }
                            },
                        )
                    }
                }
            }
            fun settle() {
                repeat(4) {
                    Snapshot.sendApplyNotifications()
                    runCurrent()
                    frameTime += 16_000_000L
                    clock.sendFrame(frameTime)
                    runCurrent()
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                    view.requestLayout()
                    view.measure(
                        View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.AT_MOST),
                    )
                    view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                }
            }
            fun elements(): List<Pair<LayoutInfo, Modifier>> {
                val root = (view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode.layoutInfo
                fun nodes(node: LayoutInfo): List<LayoutInfo> {
                    val getter = node.javaClass.methods.single { it.name.startsWith("getChildren") && it.name.endsWith("ui") && it.parameterCount == 0 }
                    val children = (getter.invoke(node) as List<*>).filterIsInstance<LayoutInfo>()
                    return listOf(node) + children.flatMap { nodes(it) }
                }
                return nodes(root).flatMap { layout -> layout.getModifierInfo().map { layout to it.modifier } }
            }
            settle()
            val modifiers = elements()
            assertTrue("The full-screen footer must not register a Haze source or effect", modifiers.none {
                it.second.javaClass.simpleName in listOf("HazeSourceElement", "TypedHazeEffectNodeElement")
            })
            val background = modifiers.single { it.second.javaClass.simpleName == "BackgroundElement" }
            val color = background.second.javaClass.getDeclaredField("color").apply { isAccessible = true }.get(background.second) as Long
            assertEquals("The footer must use the current theme background", expectedBackground.value.toLong(), color)
            assertEquals("The footer background must be opaque", 1f, expectedBackground.alpha, 0.0001f)
            val bounds = background.first.coordinates.boundsInRoot()
            assertEquals("A solid footer needs no extra Haze fade area", 92f, bounds.height, 0.5f)
            assertEquals(400f, bounds.bottom, 0.5f)
            val order = background.first.getModifierInfo().map { it.modifier.javaClass.simpleName }
            val fadeIndex = order.indexOfFirst { it.contains("GraphicsLayer") }
            val backgroundIndex = order.indexOf("BackgroundElement")
            assertTrue("Footer fade/slide must also wrap its solid background", fadeIndex >= 0 && fadeIndex < backgroundIndex)
            expanded.value = false
            settle()
            assertTrue("Compact input must release the full-screen footer background", elements().none {
                it.second.javaClass.simpleName == "BackgroundElement"
            })
            assertEquals(TextRange(2, 9), state.selection)
        } finally {
            view.disposeComposition()
            recomposer.close()
            runner.cancel()
            controller.pause().stop().destroy()
        }
    }
}
