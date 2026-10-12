package com.irio

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.composables.ui.theme.InteractionMode
import com.composables.ui.theme.LocalInteractionMode
import com.composables.ui.theme.buttonShape
import com.composables.ui.theme.fieldShape
import com.composables.ui.theme.shapes
import com.composeunstyled.LocalTextStyle
import com.composeunstyled.theme.Theme
import com.kyant.shapes.RoundedRectangularShape
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class InteractionThemeTest {
    @Test
    fun androidGeometryStaysTouchSizedWhenAPointerIsReported() = runTest {
        for (inputMode in listOf(InteractionMode.Pointer, InteractionMode.Touch, null)) {
            for (densityValue in listOf(1f, 1.5f, 2.75f)) {
                for (fontScale in listOf(1f, 1.5f, 2f)) {
                    val density = Density(densityValue, fontScale)
                    val recomposer = Recomposer(coroutineContext)
                    val composition = Composition(NoNodesApplier(), recomposer)
                    var observed = false
                    try {
                        composition.setContent {
                            CompositionLocalProvider(
                                LocalInteractionMode provides inputMode,
                                LocalDensity provides density,
                            ) {
                                IrioComponentTheme {
                                    assertEquals(InteractionMode.Touch, LocalInteractionMode.current)
                                    assertEquals(17.sp, LocalTextStyle.current.fontSize)
                                    assertEquals(24.sp, LocalTextStyle.current.lineHeight)
                                    val size = Size(200f * densityValue, 48f * densityValue)
                                    val button = Theme[shapes][buttonShape]
                                    val field = Theme[shapes][fieldShape]
                                    assertTrue(button.createOutline(size, LayoutDirection.Ltr, density) is Outline.Generic)
                                    assertTrue(field.createOutline(size, LayoutDirection.Ltr, density) is Outline.Generic)
                                    assertEquals(
                                        size.height / 2f,
                                        (button as RoundedRectangularShape).corners(size, LayoutDirection.Ltr, density).topLeft,
                                        0.01f,
                                    )
                                    assertEquals(
                                        16f * densityValue,
                                        (field as RoundedRectangularShape).corners(size, LayoutDirection.Ltr, density).topLeft,
                                        0.01f,
                                    )
                                    observed = true
                                }
                            }
                        }
                        assertEquals(true, observed)
                    } finally {
                        composition.dispose()
                        recomposer.close()
                    }
                }
            }
        }
    }

    private class NoNodesApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = error("This test must not emit UI nodes")
        override fun insertBottomUp(index: Int, instance: Unit) = error("This test must not emit UI nodes")
        override fun remove(index: Int, count: Int) = error("This test must not emit UI nodes")
        override fun move(from: Int, to: Int, count: Int) = error("This test must not emit UI nodes")
        override fun onClear() = Unit
    }
}
