package com.irio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
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
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModelSwitcherHeaderAlignmentTest {
    @Test fun modelAndStaticDepthUseTheSameVisibleVerticalAlignment() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val context = controller.get()
        ThemeStore.ensureLoaded(context)
        val oldLanguage = ThemeStore.language
        val oldFont = ThemeStore.appFont
        val scale = mutableStateOf(1f)
        val modelName = mutableStateOf("GPT-5")
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(context)
        var time = 0L
        fun draw(): Bitmap {
            val bitmap = Bitmap.createBitmap(640, 80, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            return bitmap
        }
        fun frames() = repeat(35) {
            Snapshot.sendApplyNotifications(); runCurrent()
            time += 16_000_000L; clock.sendFrame(time); runCurrent()
            view.requestLayout()
            view.measure(View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 640, 80)
            draw().recycle()
        }
        fun inkCenter(bitmap: Bitmap, from: Int, to: Int): Float {
            // Normalize coverage so changing an accent to ordinary text does not move the sampled edge.
            val contrast = (from until to).maxOf { x -> (0 until 60).maxOf { y ->
                val p = bitmap.getPixel(x, y)
                765 - android.graphics.Color.red(p) - android.graphics.Color.green(p) - android.graphics.Color.blue(p)
            } }
            val ys = (0 until 60).filter { y -> (from until to).any { x ->
                val p = bitmap.getPixel(x, y)
                android.graphics.Color.alpha(p) > 100 &&
                    765 - android.graphics.Color.red(p) - android.graphics.Color.green(p) - android.graphics.Color.blue(p) > contrast / 2
            } }
            assertTrue("Expected actual text ink in $from..$to", ys.isNotEmpty())
            return (ys.first() + ys.last()) / 2f
        }
        try {
            view.setParentCompositionContext(recomposer)
            context.setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, scale.value)) {
                    AppTheme {
                        Box(Modifier.fillMaxWidth().background(Color.White)) {
                            Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                                ModelSwitcherModelName(modelName.value, modifier = Modifier.weight(1f))
                                ModelSwitcherThinkingReadout(ReasoningEffort.HIGH, enabled = true)
                            }
                        }
                    }
                }
            }
            val measurements = mutableListOf<String>()
            val mismatches = mutableListOf<String>()
            for (font in AppFont.entries) for (language in AppLanguage.entries) for (fontScale in listOf(1f, 2f)) {
                ThemeStore.saveAppFont(context, font)
                ThemeStore.saveLanguage(context, language)
                scale.value = fontScale
                for (name in listOf("GPT-5", "gpt-oss", "模型 Model")) {
                    modelName.value = name
                    frames()
                    val bitmap = draw()
                    try {
                        val model = inkCenter(bitmap, 0, 480)
                        val depth = inkCenter(bitmap, 480, 640)

                        val sample = "$name/$font/$language/$fontScale: model=$model depth=$depth delta=${depth - model}"
                        measurements += sample
                        // Rasterized ink edges vary by half a pixel with weight and foreground contrast.
                        if (abs(depth - model) > 1.5f) mismatches += sample
                    } finally { bitmap.recycle() }
                }
            }
            println(measurements.joinToString("\n"))
            assertTrue("Visible text is misaligned:\n${mismatches.joinToString("\n")}", mismatches.isEmpty())
        } finally {
            view.disposeComposition(); recomposer.close(); runner.cancel()
            ThemeStore.saveLanguage(context, oldLanguage); ThemeStore.saveAppFont(context, oldFont)
            controller.pause().stop().destroy()
        }
    }
}
