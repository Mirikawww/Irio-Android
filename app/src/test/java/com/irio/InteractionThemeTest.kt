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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class InteractionThemeTest {
    @Test
    fun messageOnlyCornerScopePreservesTheCustomRadiusAndNormalFieldDefaults() = runTest {
        val context = RuntimeEnvironment.getApplication()
        ThemeStore.ensureLoaded(context)
        val previousRadius = ThemeStore.inputCornerRadius
        val previousScope = ThemeStore.inputCornersMessageOnly
        val preferences = context.getSharedPreferences("appearance_settings", android.content.Context.MODE_PRIVATE)
        try {
            ThemeStore.saveInputCornerRadius(context, 24)
            for (messageOnly in listOf(true, false)) {
                ThemeStore.saveInputCornersMessageOnly(context, messageOnly)
                assertEquals(messageOnly, preferences.getBoolean("input_corners_message_only", !messageOnly))
                assertEquals("Changing scope must retain the chosen radius", 24, ThemeStore.inputCornerRadius)
                val recomposer = Recomposer(coroutineContext)
                val composition = Composition(NoNodesApplier(), recomposer)
                var observed = false
                try {
                    composition.setContent {
                        CompositionLocalProvider(LocalDensity provides Density(2f)) {
                            IrioComponentTheme {
                                val size = Size(400f, 160f)
                                val normal = Theme[shapes][fieldShape] as RoundedRectangularShape
                                val composer = rememberComposerFieldShape() as RoundedRectangularShape
                                assertEquals("Message box keeps its custom corners in both scopes", 48f,
                                    composer.corners(size, LayoutDirection.Ltr, Density(2f)).topLeft, 0.01f)
                                assertEquals("Only ordinary inputs revert to their original corners", if (messageOnly) 32f else 48f,
                                    normal.corners(size, LayoutDirection.Ltr, Density(2f)).topLeft, 0.01f)
                                observed = true
                            }
                        }
                    }
                    assertTrue(observed)
                } finally {
                    composition.dispose()
                    recomposer.close()
                }
            }
        } finally {
            ThemeStore.saveInputCornerRadius(context, previousRadius)
            ThemeStore.saveInputCornersMessageOnly(context, previousScope)
        }
    }

    @Test
    fun inputFieldCornerRadiusIsEditableAndPersistedWithoutChangingButtons() = runTest {
        val context = RuntimeEnvironment.getApplication()
        ThemeStore.ensureLoaded(context)
        val original = ThemeStore.inputCornerRadius
        val preferences = context.getSharedPreferences("appearance_settings", android.content.Context.MODE_PRIVATE)
        try {
            for (radius in listOf(0, 8, 16, 32)) {
                ThemeStore.saveInputCornerRadius(context, radius)
                assertEquals(radius, preferences.getInt("input_corner_radius", -1))
                val recomposer = Recomposer(coroutineContext)
                val composition = Composition(NoNodesApplier(), recomposer)
                var observed = false
                try {
                    composition.setContent {
                        CompositionLocalProvider(LocalDensity provides Density(2f)) {
                            IrioComponentTheme {
                                // Taller than the largest radius, so corner capping cannot hide the chosen value.
                                val size = Size(400f, 160f)
                                val field = Theme[shapes][fieldShape] as RoundedRectangularShape
                                val button = Theme[shapes][buttonShape] as RoundedRectangularShape
                                assertEquals(radius * 2f, field.corners(size, LayoutDirection.Ltr, Density(2f)).topLeft, 0.01f)
                                assertEquals(80f, button.corners(size, LayoutDirection.Ltr, Density(2f)).topLeft, 0.01f)
                                observed = true
                            }
                        }
                    }
                    assertTrue("Theme was composed at radius $radius", observed)
                } finally {
                    composition.dispose()
                    recomposer.close()
                }
            }
            ThemeStore.saveInputCornerRadius(context, 100)
            assertEquals(32, ThemeStore.inputCornerRadius)
            ThemeStore.saveInputCornerRadius(context, -5)
            assertEquals(0, ThemeStore.inputCornerRadius)
        } finally {
            ThemeStore.saveInputCornerRadius(context, original)
        }
    }

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
