package com.irio

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import dev.chrisbanes.haze.rememberHazeState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.graphics.Color
import com.composables.ui.theme.colors
import com.composables.ui.theme.backgroundColor as backgroundColorToken
import com.composeunstyled.theme.Theme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ComposerAnimationContainerTest {
    @Test
    fun theAnimationDoesNotRecomposeItsParentAndContentEachFrame() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(controller.get())
        val expanded = mutableStateOf(false)
        var parentCompositions = 0
        var contentCompositions = 0
        var animationProgress: State<Float>? = null
        var frameTime = 0L
        var expectedBackground = Color.Unspecified
        try {
            view.setParentCompositionContext(recomposer)
            controller.get().setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme {
                        expectedBackground = Theme[colors][backgroundColorToken]
                        SideEffect { parentCompositions++ }
                        AnimatedComposerContainer(
                            expanded = expanded.value,
                            availableHeight = 400.dp,
                            compactHeight = 100.dp,
                            hazeState = rememberHazeState(),
                            onCompactHeightChanged = {},
                        ) { progress, _ ->
                            SideEffect { contentCompositions++; animationProgress = progress }
                            Box(Modifier.fillMaxWidth().height(100.dp))
                        }
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
                    view.measure(
                        View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.AT_MOST),
                    )
                    view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                }
            }
            fun modifiers(): List<Modifier> {
                val root = (view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode.layoutInfo
                fun nodes(node: LayoutInfo): List<LayoutInfo> {
                    val getter = node.javaClass.methods.single { it.name.startsWith("getChildren") && it.name.endsWith("ui") && it.parameterCount == 0 }
                    val children = (getter.invoke(node) as List<*>).filterIsInstance<LayoutInfo>()
                    return listOf(node) + children.flatMap { nodes(it) }
                }
                return nodes(root).flatMap { it.getModifierInfo().map { info -> info.modifier } }
            }
            settle()
            assertTrue("The home composer must retain Haze", modifiers().any { it.javaClass.simpleName == "TypedHazeEffectNodeElement" })
            val parentBefore = parentCompositions
            val contentBefore = contentCompositions
            expanded.value = true
            settle()
            repeat(6) { settle() }
            assertEquals(1f, animationProgress!!.value, 0.0001f)
            val fullscreenModifiers = modifiers()
            assertTrue("The full-screen container must not use Haze", fullscreenModifiers.none { it.javaClass.simpleName == "TypedHazeEffectNodeElement" })
            val background = fullscreenModifiers.single { it.javaClass.simpleName == "BackgroundElement" }
            val color = background.javaClass.getDeclaredField("color").apply { isAccessible = true }.get(background) as Long
            assertEquals("The full-screen container must use the opaque theme background", expectedBackground.value.toLong(), color)
            assertEquals(1f, expectedBackground.alpha, 0.0001f)
            expanded.value = false
            settle()
            repeat(6) { settle() }
            assertEquals(0f, animationProgress!!.value, 0.0001f)
            assertTrue("Collapsing must restore the home Haze", modifiers().any { it.javaClass.simpleName == "TypedHazeEffectNodeElement" })
            assertEquals("Only the two expand/collapse actions may recompose the parent", 2, parentCompositions - parentBefore)
            assertTrue("Animation content must not recompose on each frame", contentCompositions - contentBefore <= 4)
            println("Composer container probe: parentCompositions=" + (parentCompositions - parentBefore) + ", contentCompositions=" + (contentCompositions - contentBefore))
        } finally {
            view.disposeComposition()
            recomposer.close()
            runner.cancel()
            controller.pause().stop().destroy()
        }
    }
}
