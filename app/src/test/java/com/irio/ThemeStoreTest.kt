package com.irio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeStoreTest {
    @Test
    fun resolvesSystemThemeWithAutomaticAmoled() {
        assertEquals(ResolvedTheme.LIGHT, ThemePreference.SYSTEM.resolve(false, false))
        assertEquals(ResolvedTheme.LIGHT, ThemePreference.SYSTEM.resolve(false, true))
        assertEquals(ResolvedTheme.DARK, ThemePreference.SYSTEM.resolve(true, false))
        assertEquals(ResolvedTheme.AMOLED, ThemePreference.SYSTEM.resolve(true, true))
    }

    @Test
    fun explicitThemesIgnoreAutomaticAmoled() {
        assertEquals(ResolvedTheme.LIGHT, ThemePreference.LIGHT.resolve(true, true))
        assertEquals(ResolvedTheme.DARK, ThemePreference.DARK.resolve(false, true))
        assertEquals(ResolvedTheme.AMOLED, ThemePreference.AMOLED.resolve(false, false))
    }

    @Test
    fun parsesStoredThemeCaseInsensitivelyAndDefaultsToSystem() {
        assertEquals(ThemePreference.SYSTEM, ThemePreference.fromStored(null))
        assertEquals(ThemePreference.SYSTEM, ThemePreference.fromStored("unknown"))
        assertEquals(ThemePreference.AMOLED, ThemePreference.fromStored("AMOLED"))
    }

    @Test
    fun resolvedAmoledIsDark() {
        assertTrue(ResolvedTheme.AMOLED.isDark)
        assertTrue(ResolvedTheme.DARK.isDark)
        assertFalse(ResolvedTheme.LIGHT.isDark)
    }
}
