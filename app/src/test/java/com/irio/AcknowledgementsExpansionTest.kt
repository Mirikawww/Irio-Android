package com.irio

import android.graphics.Bitmap
import android.graphics.Canvas
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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.recyclerview.widget.RecyclerView
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

@OptIn(ExperimentalCoroutinesApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AcknowledgementsExpansionTest {
    @Test fun rapidReversalsKeepNativeRowAndLicenseContentAtTheSameHeight() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val host = ComposeView(controller.get())
        var time = 0L
        fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
        fun frames(count: Int = 1) {
            repeat(count) {
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(16))
                Snapshot.sendApplyNotifications(); runCurrent()
                time += 16_000_000L; clock.sendFrame(time); runCurrent()
                host.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
                host.layout(0, 0, 360, 640)
                val bitmap = Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888)
                try { host.draw(Canvas(bitmap)) } finally { bitmap.recycle() }
            }
        }
        fun nodes(view: View): List<SemanticsNode> {
            fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
            return views(view).filterIsInstance<ViewRootForTest>().flatMap { descend(it.semanticsOwner.unmergedRootSemanticsNode) }
        }
        try {
            host.setParentCompositionContext(recomposer)
            controller.get().setContentView(host)
            host.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme { AcknowledgementsScreen(onBack = {}) }
                }
            }
            frames(12)
            val list = views(host).filterIsInstance<RecyclerView>().single()
            val id = acknowledgements.first().id
            fun row(): View = requireNotNull(list.findViewHolderForAdapterPosition(0)).itemView
            fun tag(value: String) = nodes(row()).single {
                it.config.getOrElse(SemanticsProperties.TestTag) { "" } == value
            }
            fun toggle() {
                val header = nodes(row()).single { it.config.contains(SemanticsProperties.StateDescription) && it.config.contains(SemanticsActions.OnClick) }
                assertTrue(header.config[SemanticsActions.OnClick].action!!.invoke())
            }
            val collapsed = row().height
            // Interrupt both directions at different intermediate animation frames.
            for (delay in listOf(1, 3, 7, 2, 12, 4)) {
                toggle(); frames(delay)
                toggle(); frames(delay)
            }
            frames(90)
            assertEquals("Collapse must not leave a blank native placeholder; card=${tag("credit-$id").size.height}, " +
                "rowMeasured=${row().measuredHeight}, rowRequested=${row().isLayoutRequested}, listRequested=${list.isLayoutRequested}, " +
                "states=${nodes(row()).map { it.config.getOrElse(SemanticsProperties.StateDescription) { "" } }.filter { it.isNotEmpty() }}", collapsed, row().height)
            assertEquals(collapsed, tag("credit-$id").size.height)
            toggle()
            repeat(80) {
                frames()
                if (nodes(row()).none { node -> node.config.getOrElse(SemanticsProperties.TestTag) { "" } == "credit-license-$id" }) Thread.sleep(5)
            }
            val expanded = row().height
            assertTrue("The real packaged license must expand the row", expanded > collapsed + 200)
            assertEquals("Native row bounds must follow the full expanded Compose card", tag("credit-$id").size.height, expanded)
            val licenseNodes = nodes(row()).filter { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "credit-license-$id" }
            assertTrue("Actual license content must load", licenseNodes.isNotEmpty())
            assertTrue("The entire license, including its last line, must fit inside the native row",
                licenseNodes.all { it.positionInRoot.y + it.size.height <= expanded - 15f })
            // After interruption, final expansion must still match an uninterrupted one.
            toggle(); frames(90)
            toggle(); frames(3)
            assertTrue("Expansion must still have intermediate layout frames", tag("credit-$id").size.height in (collapsed + 1) until expanded)
            toggle(); frames(2)
            toggle(); frames(90)
            assertEquals("Repeated toggles must not pin the native row at an intermediate height", expanded, row().height)
            toggle(); frames(90)
            assertEquals("Final collapse must reclaim all license space", collapsed, row().height)
            assertTrue("The license must really be removed after the exit animation", nodes(row()).none {
                it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "credit-license-$id"
            })
        } finally {
            host.disposeComposition(); recomposer.close(); runner.cancel()
            controller.pause().stop().destroy()
        }
    }
}
