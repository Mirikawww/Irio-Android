package com.irio

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AcknowledgementsScrollTest {
    @Test fun fastScrollingReachesTheLastCardWithBottomSpacing() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val host = ComposeView(controller.get())
        var time = 0L
        fun frames(count: Int = 8) {
            repeat(count) {
                shadowOf(android.os.Looper.getMainLooper()).idle()
                Snapshot.sendApplyNotifications(); runCurrent()
                time += 16_000_000L; clock.sendFrame(time); runCurrent()
                host.requestLayout()
                host.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
                host.layout(0, 0, 360, 640)
            }
        }
        fun recycler(view: View): RecyclerView? = when (view) {
            is RecyclerView -> view
            is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { recycler(view.getChildAt(it)) }
            else -> null
        }
        try {
            host.setParentCompositionContext(recomposer)
            controller.get().setContentView(host)
            host.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme { AcknowledgementsScreen(onBack = {}) }
                }
            }
            frames()
            val list = requireNotNull(recycler(host))
            repeat(3) {
                list.scrollBy(0, 100_000)
                val before = list.findViewHolderForAdapterPosition(acknowledgements.lastIndex)?.itemView
                    ?.let { "top=${it.top},bottom=${it.bottom},height=${it.height}" }
                frames()
                val last = list.findViewHolderForAdapterPosition(acknowledgements.lastIndex)?.itemView
                assertNotNull("Alpine must be reachable after a large native scroll", last)
                assertFalse("The list must really reach the end, not stop early: viewport=${list.height}, padding=${list.paddingBottom}, " +
                    "before=$before, lastTop=${last!!.top}, lastBottom=${last.bottom}, lastHeight=${last.height}, children=${list.childCount}", list.canScrollVertically(1))
                assertEquals("The last card must retain the intended 16dp bottom spacing", 16, list.height - last.bottom)
                list.scrollBy(0, -100_000)
                frames()
                assertFalse("Scrolling upward must release the bottom anchor, not pull the reader back down", list.canScrollVertically(-1))
                assertNotNull(list.findViewHolderForAdapterPosition(0))
            }
        } finally {
            host.disposeComposition(); recomposer.close(); runner.cancel()
            controller.pause().stop().destroy()
        }
    }
}
