package com.irio

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserBubbleColorsTest {
    private val onPanel = Color.White

    @Test
    fun lightBubbleUsesTheResolvedAccent() {
        val blue = AccentColor.BLUE.color
        val colors = userBubbleColors(
            dark = false,
            accentColor = blue,
            onPanel = onPanel,
            editing = false,
        )
        assertEquals(blue.softened(), colors.background)
        assertEquals(Color(0xFF1A1A1A), colors.content)
    }

    @Test
    fun monetBubbleUsesTheProvidedColorNotTheStaticFallback() {
        val dynamic = Color(0xFF006B5E)
        val colors = userBubbleColors(
            dark = false,
            accentColor = dynamic,
            onPanel = onPanel,
            editing = false,
        )
        assertEquals(dynamic.softened(), colors.background)
        assertNotEquals(AccentColor.MONET.color.softened(), colors.background)
    }

    @Test
    fun darkBubblesFollowAccentInsteadOfOneGray() {
        val neutral = Color(0xFF2A2A2A)
        val pink = userBubbleColors(true, AccentColor.PINK.color, onPanel, false)
        val blue = userBubbleColors(true, AccentColor.BLUE.color, onPanel, false)
        assertNotEquals(neutral, pink.background)
        assertNotEquals(neutral, blue.background)
        assertNotEquals(pink.background, blue.background)
        assertTrue(pink.background.red > pink.background.blue)
        assertTrue(blue.background.blue > blue.background.red)
        assertEquals(onPanel, pink.content)
        assertEquals(onPanel, blue.content)
    }

    @Test
    fun legacyBlackAccentUsesBlueTint() {
        val accent = AccentColor.fromStored("black").color
        val light = userBubbleColors(false, accent, Color.Black, false)
        assertEquals(AccentColor.BLUE.color.softened(), light.background)
        assertEquals(Color(0xFF1A1A1A), light.content)
        val dark = userBubbleColors(true, accent, onPanel, false)
        assertEquals(AccentColor.BLUE.color.mixOver(Color(0xFF141414), 0.28f), dark.background)
        assertEquals(onPanel, dark.content)
    }

    @Test
    fun editingUsesTheAccentOnBothThemes() {
        val blue = AccentColor.BLUE.color
        val light = userBubbleColors(false, blue, onPanel, true)
        val dark = userBubbleColors(true, blue, onPanel, true)
        assertEquals(blue.copy(alpha = 0.16f), light.background)
        assertEquals(blue, light.content)
        assertEquals(light, dark)
    }
}
