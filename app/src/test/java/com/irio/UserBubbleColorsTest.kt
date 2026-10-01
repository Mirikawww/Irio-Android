package com.irio

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserBubbleColorsTest {
    private val panel = Color(0xFFF3F3F3)
    private val onPanel = Color.White

    @Test
    fun lightNonBlackBubbleUsesTheResolvedAccent() {
        val blue = AccentColor.BLUE.color
        val colors = userBubbleColors(
            dark = false,
            accent = AccentColor.BLUE,
            accentColor = blue,
            panel = panel,
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
            accent = AccentColor.MONET,
            accentColor = dynamic,
            panel = panel,
            onPanel = onPanel,
            editing = false,
        )
        assertEquals(dynamic.softened(), colors.background)
        assertNotEquals(AccentColor.MONET.color.softened(), colors.background)
    }

    @Test
    fun darkNonBlackBubblesFollowAccentInsteadOfOneGray() {
        val neutral = Color(0xFF2A2A2A)
        val pink = userBubbleColors(true, AccentColor.PINK, AccentColor.PINK.color, panel, onPanel, false)
        val blue = userBubbleColors(true, AccentColor.BLUE, AccentColor.BLUE.color, panel, onPanel, false)
        assertNotEquals(neutral, pink.background)
        assertNotEquals(neutral, blue.background)
        assertNotEquals(pink.background, blue.background)
        assertTrue(pink.background.red > pink.background.blue)
        assertTrue(blue.background.blue > blue.background.red)
        assertEquals(onPanel, pink.content)
        assertEquals(onPanel, blue.content)
    }

    @Test
    fun blackAccentStaysNeutral() {
        val light = userBubbleColors(false, AccentColor.BLACK, Color.Black, panel, Color.Black, false)
        assertEquals(panel, light.background)
        assertEquals(Color.Black, light.content)
        val dark = userBubbleColors(true, AccentColor.BLACK, Color.Black, panel, onPanel, false)
        assertEquals(Color(0xFF2A2A2A), dark.background)
        assertEquals(onPanel, dark.content)
    }

    @Test
    fun editingUsesTheAccentOnBothThemes() {
        val blue = AccentColor.BLUE.color
        val light = userBubbleColors(false, AccentColor.BLUE, blue, panel, onPanel, true)
        val dark = userBubbleColors(true, AccentColor.BLUE, blue, panel, onPanel, true)
        assertEquals(blue.copy(alpha = 0.16f), light.background)
        assertEquals(blue, light.content)
        assertEquals(light, dark)
    }
}
