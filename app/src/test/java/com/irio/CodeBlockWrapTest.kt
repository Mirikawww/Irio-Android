package com.irio

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class CodeBlockWrapTest {
    @Test
    fun codeBlockWrapPersistsAndCanStayOff() {
        val context = RuntimeEnvironment.getApplication()
        val previous = ThemeStore.codeBlockWrap
        val prefs = context.getSharedPreferences("appearance_settings", Context.MODE_PRIVATE)
        try {
            ThemeStore.saveCodeBlockWrap(context, true)
            assertTrue(ThemeStore.codeBlockWrap)
            assertTrue(prefs.getBoolean("code_block_wrap", false))
            ThemeStore.saveCodeBlockWrap(context, false)
            assertFalse(ThemeStore.codeBlockWrap)
            assertFalse(prefs.getBoolean("code_block_wrap", true))
        } finally {
            ThemeStore.saveCodeBlockWrap(context, previous)
        }
    }

    @Test
    fun glassSurfacesDefaultOffAndPersist() {
        val context = RuntimeEnvironment.getApplication()
        val previous = ThemeStore.glassSurfaces
        try {
            ThemeStore.saveGlassSurface(context, GlassSurface.COMPOSER, true)
            assertTrue(GlassSurface.COMPOSER in ThemeStore.glassSurfaces)
            ThemeStore.saveGlassSurface(context, GlassSurface.COMPOSER, false)
            assertFalse(GlassSurface.COMPOSER in ThemeStore.glassSurfaces)
        } finally {
            previous.forEach { ThemeStore.saveGlassSurface(context, it, true) }
            GlassSurface.entries
                .filter { it !in previous }
                .forEach { ThemeStore.saveGlassSurface(context, it, false) }
        }
    }

    @Test
    fun englishLabel() {
        assertEquals("Wrap code blocks", translate(AppLanguage.EN, "代码块自动换行"))
    }
}
