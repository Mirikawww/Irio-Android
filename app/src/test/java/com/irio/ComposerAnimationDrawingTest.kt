package com.irio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import com.kyant.shapes.RoundedRectangle
import com.kyant.shapes.UnevenRoundedRectangle
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
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
class ComposerAnimationDrawingTest {
    @Test
    fun decorationFadeMatchesOriginalPixelsIncludingTheUnfadedContent() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(controller.get())
        val progress = mutableFloatStateOf(0f)
        val reference = mutableStateOf(true)
        val shape = mutableStateOf<Shape>(RoundedRectangle(6.dp))
        val density = mutableFloatStateOf(1f)
        val ring = Color(0.3f, 0.6f, 0.9f, 0.8f)
        val fill = Color(0.8f, 0.7f, 0.6f, 0.95f)
        var frameTime = 0L
        try {
            view.setParentCompositionContext(recomposer)
            controller.get().setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(density.floatValue)) {
                    val decoration = if (reference.value) {
                        Modifier.border(1.dp, ring.copy(alpha = ring.alpha * (1f - progress.floatValue)), shape.value)
                            .clip(shape.value)
                            .background(fill.copy(alpha = fill.alpha * (1f - progress.floatValue)), shape.value)
                    } else {
                        Modifier.composerAnimatedDecoration(progress, ring, fill, shape.value)
                    }
                    Box(
                        modifier = Modifier.size(160.dp, 96.dp).then(decoration),
                        contentAlignment = Alignment.Center,
                    ) { Box(Modifier.size(12.dp).background(Color.Red)) }
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
                        View.MeasureSpec.makeMeasureSpec((160 * density.floatValue).toInt(), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.AT_MOST),
                    )
                    view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                }
            }
            for (scale in listOf(1f, 2.5f)) {
                density.floatValue = scale
                for (outline in listOf(
                    RectangleShape,
                    RoundedRectangle(6.dp),
                    RoundedRectangle(0.1.dp),
                    UnevenRoundedRectangle(topStart = 2.dp, topEnd = 12.dp, bottomEnd = 16.dp, bottomStart = 4.dp),
                )) {
                    shape.value = outline
                    for (sample in listOf(0f, 0.17f, 0.5f, 0.85f, 1f)) {
                        progress.floatValue = sample
                        fun drawPixels(useReference: Boolean): IntArray {
                            reference.value = useReference
                            settle()
                            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                            return try {
                                view.draw(Canvas(bitmap))
                                IntArray(bitmap.width * bitmap.height).also {
                                    bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                                }
                            } finally { bitmap.recycle() }
                        }
                        // Compare at identical canvas coordinates to avoid translated-path rasterization noise.
                        val expected = drawPixels(true)
                        val actual = drawPixels(false)
                        assertArrayEquals("density=$scale shape=$outline progress=$sample", expected, actual)
                    }
                }
            }
        } finally {
            view.disposeComposition()
            recomposer.close()
            runner.cancel()
            controller.pause().stop().destroy()
        }
    }
}
