package com.irio

import android.view.View
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import com.composables.ui.theme.alphas
import com.composables.ui.theme.backgroundColor
import com.composables.ui.theme.colors
import com.composables.ui.theme.disabledAlpha
import com.composables.ui.theme.onPrimaryColor
import com.composables.ui.theme.onSecondaryColor
import com.composables.ui.theme.panelColor
import com.composables.ui.theme.primaryColor
import com.composables.ui.theme.secondaryColor
import com.composeunstyled.theme.Theme
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AmoledThemeTest {
    @Test
    fun legacyBlackAccentUsesBlueOnAmoledBackground() = withTheme(ThemePreference.AMOLED, AccentColor.fromStored("black")) {
        val background = Theme[colors][backgroundColor]
        val fill = Theme[colors][primaryColor]
        val ink = Theme[colors][onPrimaryColor]
        assertEquals(Color.Black, background)
        assertNotEquals("Primary button must not merge into the page", background, fill)
        assertEquals(AccentColor.BLUE.color, fill)
        assertEquals(AccentColor.BLUE.onContent(), ink)
    }

    @Test
    fun secondaryButtonStaysVisibleOnAmoledDialogPanel() = withTheme(ThemePreference.AMOLED, AccentColor.BLUE) {
        val fill = Theme[colors][secondaryColor]
        val panel = Theme[colors][panelColor]
        assertNotEquals("Secondary button must not merge into the dialog panel", panel, fill)
        assertNotEquals(Theme[colors][backgroundColor], fill)
        assertEquals(1f, fill.alpha)
        assertTrue(wcagContrastRatio(fill, Theme[colors][onSecondaryColor]) >= 4.5f)
    }

    @Test
    fun coloredAccentButtonsKeepTheirExistingColorsAcrossThemes() {
        for (preference in listOf(ThemePreference.LIGHT, ThemePreference.DARK, ThemePreference.AMOLED)) {
            for (accent in listOf(AccentColor.BLUE, AccentColor.PINK)) {
                withTheme(preference, accent) {
                    assertEquals(accent.color, Theme[colors][primaryColor])
                    assertEquals(accent.onContent(), Theme[colors][onPrimaryColor])
                }
            }
        }
    }

    @Test
    fun monetButtonsKeepTheirResolvedSystemColorsAcrossThemes() {
        for (preference in listOf(ThemePreference.LIGHT, ThemePreference.DARK, ThemePreference.AMOLED)) {
            withTheme(preference, AccentColor.MONET) {
                val context = LocalView.current.context
                val dynamic = if (preference == ThemePreference.LIGHT) {
                    dynamicLightColorScheme(context)
                } else {
                    dynamicDarkColorScheme(context)
                }
                assertEquals(dynamic.primary, Theme[colors][primaryColor])
                assertEquals(dynamic.onPrimary, Theme[colors][onPrimaryColor])
            }
        }
    }

    @Test
    fun disabledSecondaryButtonStillHasASeparateFill() = withTheme(ThemePreference.AMOLED, AccentColor.BLUE) {
        val panel = Theme[colors][panelColor]
        val fill = Theme[colors][secondaryColor].copy(alpha = Theme[alphas][disabledAlpha]).compositeOver(panel)
        assertNotEquals("Disabled styling must not erase the button fill", panel, fill)
    }

    @Test
    fun bubbleOpacityUsesRealThemeColorsAcrossLightDarkAmoledAndMonet() {
        val context = RuntimeEnvironment.getApplication()
        ThemeStore.ensureLoaded(context)
        val previousOpacity = ThemeStore.bubbleOpacity
        try {
            for (opacity in listOf(0, 25, 100)) {
                ThemeStore.saveBubbleOpacity(context, opacity)
                for (preference in listOf(ThemePreference.LIGHT, ThemePreference.DARK, ThemePreference.AMOLED)) {
                    for (accent in AccentColor.entries) {
                        withTheme(preference, accent) {
                            val bubble = currentUserBubbleColors(editing = false)
                            assertEquals(Theme[colors][primaryColor].copy(alpha = opacity / 100f), bubble.background)
                            assertEquals(1f, bubble.content.alpha)
                            if (opacity == 100) assertEquals(Theme[colors][onPrimaryColor], bubble.content)
                        }
                    }
                }
            }
        } finally {
            ThemeStore.saveBubbleOpacity(context, previousOpacity)
        }
    }

    private fun withTheme(preference: ThemePreference, accent: AccentColor, check: @Composable () -> Unit) = runTest {
        val context = RuntimeEnvironment.getApplication()
        val previousPreference = ThemeStore.preference
        val previousAccent = ThemeStore.accent
        val recomposer = Recomposer(coroutineContext)
        val composition = Composition(NoNodesApplier(), recomposer)
        var observed = false
        try {
            ThemeStore.save(context, preference)
            ThemeStore.saveAccent(context, accent)
            val view = View(context)
            composition.setContent {
                CompositionLocalProvider(
                    LocalView provides view,
                    LocalConfiguration provides context.resources.configuration,
                    LocalDensity provides Density(1f),
                ) {
                    AppTheme {
                        check()
                        observed = true
                    }
                }
            }
            assertTrue("Theme assertions must execute", observed)
        } finally {
            composition.dispose()
            recomposer.close()
            ThemeStore.save(context, previousPreference)
            ThemeStore.saveAccent(context, previousAccent)
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
