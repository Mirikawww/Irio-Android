package com.irio

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Density
import com.composables.ui.components.Text
import com.composables.ui.theme.colors
import com.composables.ui.theme.onPanelColor
import com.composeunstyled.theme.Theme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageUploadSettingsUiTest {
    @Test fun parametersAnimateWithTheSwitchAndQualityIsCommittedOnlyOnRelease() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val context = controller.get()
        ThemeStore.ensureLoaded(context)
        val previous = ThemeStore.imageUploadOptions()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(context)
        var time = 0L
        fun frame() {
            shadowOf(Looper.getMainLooper()).idle()
            Snapshot.sendApplyNotifications(); runCurrent()
            clock.sendFrame(time.also { time += 16_000_000L }); runCurrent()
            view.requestLayout()
            view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 360, 900)
        }
        fun frames(count: Int = 8) { repeat(count) { frame() } }
        fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
        fun nodes() = descend((view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
        fun text(value: String) = nodes().single { it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { t -> t.text == value } }
        fun toggle() {
            val node = nodes().single { it.config.contains(SemanticsProperties.ToggleableState) }
            assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
        }
        try {
            ThemeStore.saveImageUploadCompression(context, true)
            ThemeStore.saveImageUploadQuality(context, 90)
            ThemeStore.saveImageUploadFormat(context, ImageUploadFormat.WEBP)
            view.setParentCompositionContext(recomposer)
            context.setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme {
                        Column {
                            ImageUploadSettings()
                            Text("below compression fixture", color = Theme[colors][onPanelColor])
                        }
                    }
                }
            }
            frames(40)
            text("WebP"); text("90%")
            fun selectFormat(label: String) {
                val tab = nodes().single { it.config.contains(SemanticsProperties.Selected) && descend(it).any { child ->
                    child.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { t -> t.text == label }
                } }
                assertTrue(tab.config[SemanticsActions.OnClick].action!!.invoke()); frames(40)
            }
            val defaultTab = nodes().single { it.config.getOrElse(SemanticsProperties.Selected) { false } }
            assertTrue(descend(defaultTab).any { it.id == text("WebP").id })
            selectFormat("JPEG"); assertEquals(ImageUploadFormat.JPEG, ThemeStore.imageUploadFormat)
            selectFormat("WebP"); assertEquals(ImageUploadFormat.WEBP, ThemeStore.imageUploadFormat)
            assertEquals(ToggleableState.On, nodes().single { it.config.contains(SemanticsProperties.ToggleableState) }.config[SemanticsProperties.ToggleableState])
            val slider = nodes().single { it.config.contains(SemanticsActions.SetProgress) }
            assertEquals(40f..100f, slider.config[SemanticsProperties.ProgressBarRangeInfo].range)
            val bounds = slider.boundsInRoot
            val down = SystemClock.uptimeMillis()
            fun touch(action: Int, step: Int, fraction: Float) {
                val event = MotionEvent.obtain(down, down + step * 20L, action,
                    bounds.left + bounds.width * fraction, bounds.center.y, 0)
                try { view.dispatchTouchEvent(event) } finally { event.recycle() }
                frames(2)
            }
            touch(MotionEvent.ACTION_DOWN, 0, 5f / 6f)
            for (step in 1..6) {
                touch(MotionEvent.ACTION_MOVE, step, 5f / 6f - step * 0.06f)
                assertEquals("Only the preview may change while dragging", 90, ThemeStore.imageUploadQuality)
            }
            val preview = nodes().single { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) }
                .config[SemanticsProperties.ProgressBarRangeInfo].current.toInt()
            assertTrue(preview < 80)
            text("$preview%")
            touch(MotionEvent.ACTION_UP, 7, 5f / 6f - 0.36f)
            frames()
            assertEquals(preview, ThemeStore.imageUploadQuality)
            val expanded = text("below compression fixture").boundsInRoot.top
            toggle()
            val collapse = List(40) { frame(); text("below compression fixture").boundsInRoot.top }
            val collapsed = text("below compression fixture").boundsInRoot.top
            assertTrue(collapse.any { it > collapsed && it < expanded })
            assertTrue(nodes().none { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) })
            assertEquals(preview, ThemeStore.imageUploadQuality)
            toggle()
            val expand = List(40) { frame(); text("below compression fixture").boundsInRoot.top }
            assertTrue(expand.any { it > collapsed && it < expanded })
            text("$preview%"); text("WebP")
        } finally {
            view.disposeComposition(); recomposer.close(); runner.cancel()
            ThemeStore.saveImageUploadCompression(context, previous.enabled)
            ThemeStore.saveImageUploadQuality(context, previous.quality)
            ThemeStore.saveImageUploadFormat(context, previous.format)
            controller.pause().stop().destroy()
        }
    }
}
