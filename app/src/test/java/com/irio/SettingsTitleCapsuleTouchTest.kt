package com.irio

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
class SettingsTitleCapsuleTouchTest {
    @Test
    fun titleAndCapsulePaddingBlockPageTapsWithoutBlockingBackOrTrailingActions() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(controller.get())
        var pageClicks = 0
        var backClicks = 0
        var trailingClicks = 0
        var trailingBounds = Rect.Zero
        try {
            view.setParentCompositionContext(recomposer)
            controller.get().setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme {
                        Box(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxSize().clickable { pageClicks++ })
                            SettingsTitleCapsule(
                                title = "Settings title",
                                onBack = { backClicks++ },
                                titleTrailing = {
                                    Box(
                                        Modifier.size(24.dp)
                                            .onGloballyPositioned { trailingBounds = it.boundsInRoot() }
                                            .clickable { trailingClicks++ },
                                    )
                                },
                            )
                        }
                    }
                }
            }
            var frameTime = 0L
            fun frames() = repeat(4) {
                Snapshot.sendApplyNotifications()
                advanceTimeBy(16L)
                runCurrent()
                frameTime += 16_000_000L
                clock.sendFrame(frameTime)
                runCurrent()
                shadowOf(Looper.getMainLooper()).idle()
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                )
                view.layout(0, 0, 400, 300)
            }
            frames()
            fun roots(view: View): List<ViewRootForTest> =
                (if (view is ViewRootForTest) listOf(view) else emptyList()) +
                    (if (view is ViewGroup) (0 until view.childCount).flatMap { roots(view.getChildAt(it)) } else emptyList())
            fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
            val nodes = roots(view).flatMap { descend(it.semanticsOwner.unmergedRootSemanticsNode) }
            val title = nodes.single {
                it.config.contains(SemanticsProperties.Text) &&
                    it.config[SemanticsProperties.Text].any { text -> text.text == "Settings title" }
            }.boundsInRoot
            val back = nodes.single {
                it.config.contains(SemanticsProperties.Role) && it.config[SemanticsProperties.Role] == Role.Button
            }.boundsInRoot
            assertTrue("The title must have a measured touch location", title.width > 0f)
            assertTrue(trailingBounds.width > 0f)
            fun tap(point: Offset) {
                val down = SystemClock.uptimeMillis()
                listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEachIndexed { index, action ->
                    val event = MotionEvent.obtain(down, down + index * 20L, action, point.x, point.y, 0)
                    try { view.dispatchTouchEvent(event) } finally { event.recycle() }
                    frames()
                }
            }

            tap(Offset(380f, back.center.y))
            assertEquals("Outside the capsule, the underlying page remains interactive", 1, pageClicks)
            tap(title.center)
            assertEquals("The capsule title must not click through to settings rows", 1, pageClicks)
            assertEquals("The title must not become a second back button", 0, backClicks)
            tap(Offset(trailingBounds.right + 12f, back.center.y))
            assertEquals("The capsule's trailing padding must not click through", 1, pageClicks)
            tap(back.center)
            assertEquals("The real back button must still work", 1, backClicks)
            tap(trailingBounds.center)
            assertEquals("A child title action must retain its own touch handler", 1, trailingClicks)
            assertEquals(1, backClicks)
            assertEquals(1, pageClicks)
            tap(Offset(title.center.x, back.bottom + 24f))
            assertEquals("Below the capsule, the underlying page remains interactive", 2, pageClicks)
        } finally {
            view.disposeComposition()
            recomposer.close()
            runner.cancel()
            controller.pause().stop().destroy()
        }
    }
}
