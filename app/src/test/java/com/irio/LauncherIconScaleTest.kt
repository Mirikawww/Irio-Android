package com.irio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LauncherIconScaleTest {
    @Test fun gammaForegroundMatchesIotaPaddingAtDifferentIconSizes() {
        val context = RuntimeEnvironment.getApplication()
        for (iconId in listOf(R.mipmap.ic_launcher, R.mipmap.ic_launcher_round)) {
            for (side in listOf(72, 108, 144, 216)) {
                val gamma = context.getDrawable(iconId) as AdaptiveIconDrawable
                val iota = checkNotNull(context.getDrawable(R.mipmap.ic_launcher_foreground))
                val expected = opaqueBounds(iota, side)
                val actual = opaqueBounds(gamma.foreground, side)
                assertTrue(
                    "Gamma must match Iota's proportional padding at ${side}px: expected $expected, got $actual",
                    abs(expected.left - actual.left) <= 1 &&
                        abs(expected.top - actual.top) <= 1 &&
                        abs(expected.right - actual.right) <= 1 &&
                        abs(expected.bottom - actual.bottom) <= 1,
                )
            }
        }
    }

    private fun opaqueBounds(drawable: Drawable, side: Int): Rect {
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        return try {
            drawable.setBounds(0, 0, side, side)
            drawable.draw(Canvas(bitmap))
            val bounds = Rect(side, side, 0, 0)
            for (y in 0 until side) for (x in 0 until side) {
                if (Color.alpha(bitmap.getPixel(x, y)) >= 240) {
                    bounds.left = minOf(bounds.left, x)
                    bounds.top = minOf(bounds.top, y)
                    bounds.right = maxOf(bounds.right, x + 1)
                    bounds.bottom = maxOf(bounds.bottom, y + 1)
                }
            }
            assertTrue("Icon must contain opaque artwork", bounds.width() > 0 && bounds.height() > 0)
            bounds
        } finally {
            bitmap.recycle()
        }
    }
}
