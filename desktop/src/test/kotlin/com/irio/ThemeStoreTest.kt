package com.irio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.graphics.Color

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
        assertEquals(AccentColor.BLUE, AccentColor.fromStored(null))
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
        assertEquals(AppLanguage.EN, AppLanguage.fromStored("en"))
        assertEquals(AppLanguage.EN, AppLanguage.fromStored("EN"))
        assertEquals("English", AppLanguage.EN.label)
        assertEquals("en", AppLanguage.EN.stored)
        assertEquals("Formula size", translate(AppLanguage.EN, "公式大小"))
        assertEquals("Text fade-in", translate(AppLanguage.EN, "文本淡入"))
        assertEquals("Model diff", translate(AppLanguage.EN, "模型差异"))
        assertEquals("New message scroll", translate(AppLanguage.EN, "新消息滚动"))
        assertEquals("Experimental", translate(AppLanguage.EN, "实验性"))
        assertEquals("Context compression", translate(AppLanguage.EN, "上下文压缩"))
        assertEquals("Compressing context", translate(AppLanguage.EN, "上下文压缩中"))
        assertEquals("Context compressed", translate(AppLanguage.EN, "已压缩上下文"))
        assertEquals("Context compression failed", translate(AppLanguage.EN, "上下文压缩失败"))
        assertEquals("Compression model", translate(AppLanguage.EN, "压缩模型"))
        assertEquals("Compressions", translate(AppLanguage.EN, "压缩次数"))
        assertEquals("Compress context", translate(AppLanguage.EN, "压缩上下文"))
        assertEquals("Summarize", translate(AppLanguage.EN, "摘要"))
        assertEquals("Refresh port", translate(AppLanguage.EN, "刷新端口"))
        assertEquals("Trigger threshold", translate(AppLanguage.EN, "触发时机"))
        assertEquals("Compression target", translate(AppLanguage.EN, "压缩目标"))
        assertEquals("Visualize context", translate(AppLanguage.EN, "可视化上下文"))
        assertEquals("%d messages", translate(AppLanguage.EN, "%d 条"))
        assertEquals("外观", translate(AppLanguage.ZH_CN, "外观"))
        assertEquals("New chat", translate(AppLanguage.EN, "新对话"))
    }

    @Test
    fun darkDestructiveButtonUsesWhiteTextWithWcagAaContrast() {
        assertEquals(Color.White, darkDestructiveButtonContentColor)
        assertTrue(
            wcagContrastRatio(darkDestructiveButtonContentColor, darkDestructiveButtonColor) >= 4.5f,
        )
    }
}
