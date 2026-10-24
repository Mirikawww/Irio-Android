package com.irio

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserBubbleColorsTest {
    private fun palette(
        opacity: Int = 25,
        background: Color = Color.White,
        accent: Color = AccentColor.BLUE.color,
        onPanel: Color = Color.Black,
        onAccent: Color = Color.White,
        editing: Boolean = false,
    ) = userBubbleColors(background, accent, onPanel, onAccent, editing, opacity)

    @Test fun defaultIsTwentyFivePercentAccentNotAPaleOpaqueMix() {
        val colors = userBubbleColors(Color.White, AccentColor.BLUE.color, Color.Black, Color.White, false)
        assertEquals(AccentColor.BLUE.color.copy(alpha = 0.25f), colors.background)
        assertEquals(1f, colors.content.alpha)
    }

    @Test fun zeroRemovesOnlyTheFillAndKeepsPageTextVisible() {
        for ((background, ink) in listOf(Color.White to Color.Black, Color(0xFF141414) to Color.White, Color.Black to Color.White)) {
            val colors = palette(0, background, onPanel = ink)
            assertEquals(0f, colors.background.alpha)
            assertEquals(ink, colors.content)
            assertEquals(background, colors.background.compositeOver(background))
        }
    }

    @Test fun fullOpacityMatchesTheEditBannerIncludingDynamicAccentContent() {
        for ((accent, ink) in listOf(
            AccentColor.BLUE.color to Color.White,
            AccentColor.PINK.color to Color.White,
            Color(0xFF006B5E) to Color.White,
            Color(0xFF80D5C3) to Color(0xFF00382F),
        )) {
            val colors = palette(100, accent = accent, onAccent = ink)
            assertEquals(accent, colors.background)
            assertEquals(ink, colors.content)
        }
    }

    @Test fun everyPercentChangesOnlyTheAccentAlphaAndKeepsTextOpaque() {
        for (opacity in 0..100) {
            val colors = palette(opacity)
            assertEquals(AccentColor.BLUE.color.copy(alpha = opacity / 100f), colors.background)
            assertEquals(1f, colors.content.alpha)
        }
    }

    @Test fun intermediateTextContrastsWithTheCompositedFillAcrossThemesAndAccents() {
        for (background in listOf(Color.White, Color(0xFF141414), Color.Black)) {
            for (accent in listOf(AccentColor.BLUE.color, AccentColor.PINK.color, Color(0xFF006B5E), Color(0xFF80D5C3))) {
                for (opacity in 1..99) {
                    val colors = palette(opacity, background, accent)
                    assertTrue("$background / $accent / $opacity", wcagContrastRatio(
                        colors.content, colors.background.compositeOver(background),
                    ) >= 4.5f)
                }
            }
        }
    }

    @Test fun monetUsesResolvedColorAndLegacyBlackStillFallsBackToBlue() {
        val dynamic = Color(0xFF006B5E)
        assertEquals(dynamic.copy(alpha = 0.25f), palette(accent = dynamic).background)
        assertNotEquals(palette(accent = AccentColor.MONET.color).background, palette(accent = dynamic).background)
        assertEquals(palette().background, palette(accent = AccentColor.fromStored("black").color).background)
    }

    @Test fun outOfRangeValuesAreClamped() {
        assertEquals(palette(0), palette(Int.MIN_VALUE))
        assertEquals(palette(100), palette(Int.MAX_VALUE))
    }

    @Test fun editingHighlightIsIndependentOfBubbleOpacity() {
        for (opacity in listOf(0, 25, 100)) {
            val colors = palette(opacity, editing = true)
            assertEquals(AccentColor.BLUE.color.copy(alpha = 0.16f), colors.background)
            assertEquals(AccentColor.BLUE.color, colors.content)
        }
    }
}
