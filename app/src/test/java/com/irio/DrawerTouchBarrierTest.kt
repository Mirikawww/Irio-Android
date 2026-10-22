package com.irio

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class DrawerTouchBarrierTest {
    @Test
    fun footerGapDoesNotClickThroughAndSettingsButtonStillWorks() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val host = ComposeView(activity.get())
        var backgroundClicks by mutableIntStateOf(0)
        var settingsClicks by mutableIntStateOf(0)
        var footerBounds = Rect.Zero
        var settingsBounds = Rect.Zero
        try {
            activity.get().setContentView(host)
            host.setContent {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().clickable { backgroundClicks++ })
                    Box(
                        Modifier.align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .onGloballyPositioned {
                                val top = it.positionInRoot()
                                footerBounds = Rect(top.x, top.y, top.x + it.size.width, top.y + it.size.height)
                            }
                            .background(Color.Black)
                            .absorbDrawerBackgroundTaps()
                            .padding(bottom = 8.dp),
                    ) {
                        Box(
                            Modifier.align(Alignment.TopCenter).fillMaxWidth().height(52.dp)
                                .onGloballyPositioned {
                                    val top = it.positionInRoot()
                                    settingsBounds = Rect(top.x, top.y, top.x + it.size.width, top.y + it.size.height)
                                }
                                .clickable { settingsClicks++ },
                        )
                    }
                }
            }
            host.measure(
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
            )
            host.layout(0, 0, 300, 300)
            shadowOf(Looper.getMainLooper()).idle()

            fun tap(y: Float) {
                val downTime = SystemClock.uptimeMillis()
                listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEachIndexed { index, action ->
                    val event = MotionEvent.obtain(downTime, downTime + index * 20L, action, 150f, y, 0)
                    try { host.dispatchTouchEvent(event) } finally { event.recycle() }
                }
                shadowOf(Looper.getMainLooper()).idle()
            }

            assertEquals(8f, footerBounds.bottom - settingsBounds.bottom, 0.1f)
            tap(100f)
            assertEquals("The fixture's background target should be tappable", 1, backgroundClicks)
            tap((settingsBounds.bottom + footerBounds.bottom) / 2f)
            assertEquals("Footer gap tap leaked to the page", 1, backgroundClicks)
            tap(settingsBounds.center.y)
            assertEquals(1, settingsClicks)
            assertEquals(1, backgroundClicks)
        } finally {
            host.disposeComposition()
            activity.pause().stop().destroy()
        }
    }
}
