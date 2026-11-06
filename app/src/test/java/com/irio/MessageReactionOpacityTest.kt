package com.irio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.composables.ui.theme.backgroundColor
import com.composables.ui.theme.colors
import com.composeunstyled.theme.Theme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
class MessageReactionOpacityTest {
    @Test
    fun reactionFillMatchesCustomBubbleOpacityEvenWhereItOverlapsTheMessage() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val context = controller.get()
        ThemeStore.ensureLoaded(context)
        val oldOpacity = ThemeStore.bubbleOpacity
        val oldTheme = ThemeStore.preference
        val oldAccent = ThemeStore.accent
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(context)
        try {
            ThemeStore.saveAccent(context, AccentColor.BLUE)
            view.setParentCompositionContext(recomposer)
            context.setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme {
                        val bubble = currentUserBubbleColors(editing = false).background
                        Box(Modifier.background(Theme[colors][backgroundColor]).padding(start = 60.dp, top = 70.dp)) {
                            UserBubbleReaction("👍", bubble, mutableStateOf(1f)) {
                                Box(Modifier.size(100.dp, 60.dp).background(bubble))
                            }
                        }
                    }
                }
            }
            var time = 0L
            fun frames() = repeat(6) {
                Snapshot.sendApplyNotifications()
                advanceTimeBy(16L)
                runCurrent()
                time += 16_000_000L
                clock.sendFrame(time)
                runCurrent()
                shadowOf(Looper.getMainLooper()).idle()
                view.requestLayout()
                view.measure(View.MeasureSpec.makeMeasureSpec(220, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(180, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, 220, 180)
            }
            for (theme in listOf(ThemePreference.LIGHT, ThemePreference.DARK, ThemePreference.AMOLED)) {
                ThemeStore.save(context, theme)
                for (opacity in listOf(0, 25, 60, 100, 37)) {
                    ThemeStore.saveBubbleOpacity(context, opacity)
                    frames()
                    val bitmap = Bitmap.createBitmap(220, 180, Bitmap.Config.ARGB_8888)
                    try {
                        view.draw(Canvas(bitmap))
                        val expected = Color(bitmap.getPixel(110, 100))
                        for ((label, point) in listOf(
                            "badge above the message" to (69 to 48),
                            "badge overlapping the message" to (69 to 70),
                            "large thought dot" to (52 to 74),
                            "small thought dot" to (46 to 83),
                        )) {
                            val actual = Color(bitmap.getPixel(point.first, point.second))
                            for ((channel, values) in listOf("red" to (expected.red to actual.red),
                                "green" to (expected.green to actual.green), "blue" to (expected.blue to actual.blue))) {
                                assertEquals("$label must match the bubble at $opacity% in $theme ($channel)",
                                    values.first, values.second, 0.025f)
                            }
                        }
                    } finally { bitmap.recycle() }
                }
            }
        } finally {
            view.disposeComposition()
            recomposer.close()
            runner.cancel()
            ThemeStore.saveBubbleOpacity(context, oldOpacity)
            ThemeStore.save(context, oldTheme)
            ThemeStore.saveAccent(context, oldAccent)
            controller.pause().stop().destroy()
        }
    }
}
