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

    @Test
    fun parsesStoredLanguageAndDefaultsToSimplifiedChinese() {
        assertEquals(AppLanguage.ZH_CN, AppLanguage.fromStored(null))
        assertEquals(AppLanguage.ZH_CN, AppLanguage.fromStored("unknown"))
        assertEquals(AppLanguage.ZH_CN, AppLanguage.fromStored("ZH-CN"))
        assertEquals("简体中文", AppLanguage.ZH_CN.label)
        assertEquals("zh-CN", AppLanguage.ZH_CN.stored)
    }
}
