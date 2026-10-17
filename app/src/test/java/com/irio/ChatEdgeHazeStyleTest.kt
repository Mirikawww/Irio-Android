package com.irio

import android.view.View
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.blur.HazeBlurStyle
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
class ChatEdgeHazeStyleTest {
    @Test
    fun toolbarBlurMirrorsTheUnchangedComposerAtEveryDistanceFromTheScreenEdge() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val previousPreference = ThemeStore.preference
        try {
            for (preference in listOf(ThemePreference.LIGHT, ThemePreference.DARK, ThemePreference.AMOLED)) {
                ThemeStore.save(context, preference)
                val recomposer = Recomposer(coroutineContext)
                val composition = Composition(NoNodesApplier(), recomposer)
                var observed = false
                try {
                    composition.setContent {
                        CompositionLocalProvider(
                            LocalView provides View(context),
                            LocalConfiguration provides context.resources.configuration,
                            LocalDensity provides Density(1f),
                        ) {
                            AppTheme {
                                val toolbarStyle = resolve(chatToolbarHazeStyle())
                                val composerStyle = resolve(chatComposerHazeStyle())
                                val toolbar = property(toolbarStyle, "getProgressive") as HazeProgressive.LinearGradient
                                val composer = property(composerStyle, "getProgressive") as HazeProgressive.LinearGradient
                                assertEquals(1f, toolbar.startIntensity, 0f)
                                assertEquals(0f, toolbar.endIntensity, 0f)
                                assertEquals(0f, composer.startIntensity, 0f)
                                assertEquals(1f, composer.endIntensity, 0f)
                                for (sample in 0..100) {
                                    val distance = sample / 100f
                                    assertEquals(
                                        "Toolbar blur must mirror the composer in $preference at distance $distance",
                                        intensity(composer, 1f - distance),
                                        intensity(toolbar, distance),
                                        0.0001f,
                                    )
                                    assertEquals(
                                        "The existing composer curve must remain unchanged",
                                        IrioEaseOut.transform(distance),
                                        intensity(composer, distance),
                                        0.0001f,
                                    )
                                }
                                for (name in listOf("getBlurRadius", "getBackgroundColor", "getColorEffects")) {
                                    assertEquals("Both edges must retain the same $name", property(composerStyle, name), property(toolbarStyle, name))
                                }
                                observed = true
                            }
                        }
                    }
                    assertTrue("Style assertions must execute for $preference", observed)
                } finally {
                    composition.dispose()
                    recomposer.close()
                }
            }
        } finally {
            ThemeStore.save(context, previousPreference)
        }
    }

    private fun intensity(gradient: HazeProgressive.LinearGradient, position: Float): Float =
        gradient.startIntensity + (gradient.endIntensity - gradient.startIntensity) * gradient.easing.transform(position)

    // Resolve the actual recorded style without rendering AGSL on Robolectric's Skia.
    private fun resolve(style: HazeBlurStyle): Any =
        Class.forName("dev.chrisbanes.haze.blur.HazeBlurStyleKt")
            .getDeclaredMethod("resolveHazeBlurStyle", HazeBlurStyle::class.java, HazeBlurStyle::class.java)
            .invoke(null, HazeBlurStyle {}, style)!!

    private fun property(style: Any, prefix: String): Any? =
        style.javaClass.methods.single { it.name.startsWith(prefix) }.invoke(style)

    private class NoNodesApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = error("This test must not emit UI nodes")
        override fun insertBottomUp(index: Int, instance: Unit) = error("This test must not emit UI nodes")
        override fun remove(index: Int, count: Int) = error("This test must not emit UI nodes")
        override fun move(from: Int, to: Int, count: Int) = error("This test must not emit UI nodes")
        override fun onClear() = Unit
    }
}
