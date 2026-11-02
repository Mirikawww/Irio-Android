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
    fun glassMasterSwitchPreservesSelectedSurfacesAndPersists() {
        val context = RuntimeEnvironment.getApplication()
        ThemeStore.ensureLoaded(context)
        val previousSurfaces = ThemeStore.glassSurfaces
        val previousEnabled = ThemeStore.glassEnabled
        val prefs = context.getSharedPreferences("appearance_settings", Context.MODE_PRIVATE)
        try {
            GlassSurface.entries.forEach { ThemeStore.saveGlassSurface(context, it, it == GlassSurface.COMPOSER) }
            ThemeStore.saveGlassEnabled(context, true)
            assertTrue(ThemeStore.isGlassEnabled(GlassSurface.COMPOSER))
            assertFalse(ThemeStore.isGlassEnabled(GlassSurface.USER_BUBBLE))
            ThemeStore.saveGlassEnabled(context, false)
            assertFalse(ThemeStore.isGlassEnabled(GlassSurface.COMPOSER))
            assertTrue(GlassSurface.COMPOSER in ThemeStore.glassSurfaces)
            assertFalse(prefs.getBoolean("glass_enabled", true))
            ThemeStore.saveGlassEnabled(context, true)
            assertTrue(ThemeStore.isGlassEnabled(GlassSurface.COMPOSER))
            assertTrue(prefs.getBoolean("glass_enabled", false))
        } finally {
            GlassSurface.entries.forEach { ThemeStore.saveGlassSurface(context, it, it in previousSurfaces) }
            ThemeStore.saveGlassEnabled(context, previousEnabled)
        }
    }

    @Test
    fun enablingGlassWithoutAnySelectedSurfaceEnablesAllSurfaces() {
        val context = RuntimeEnvironment.getApplication()
        ThemeStore.ensureLoaded(context)
        val previousSurfaces = ThemeStore.glassSurfaces
        val previousEnabled = ThemeStore.glassEnabled
        try {
            ThemeStore.saveGlassEnabled(context, false)
            GlassSurface.entries.forEach { ThemeStore.saveGlassSurface(context, it, false) }
            ThemeStore.saveGlassEnabled(context, true)
            assertEquals(GlassSurface.entries.toSet(), ThemeStore.glassSurfaces)
            GlassSurface.entries.forEach { assertTrue(ThemeStore.isGlassEnabled(it)) }
        } finally {
            GlassSurface.entries.forEach { ThemeStore.saveGlassSurface(context, it, it in previousSurfaces) }
            ThemeStore.saveGlassEnabled(context, previousEnabled)
        }
    }

    @Test
    fun codeBlockLineNumbersPersistAndCanStayOff() {
        val context = RuntimeEnvironment.getApplication()
        val previous = ThemeStore.codeBlockLineNumbers
        val prefs = context.getSharedPreferences("appearance_settings", Context.MODE_PRIVATE)
        try {
            ThemeStore.saveCodeBlockLineNumbers(context, true)
            assertTrue(ThemeStore.codeBlockLineNumbers)
            assertTrue(prefs.getBoolean("code_block_line_numbers", false))
            ThemeStore.saveCodeBlockLineNumbers(context, false)
            assertFalse(ThemeStore.codeBlockLineNumbers)
            assertFalse(prefs.getBoolean("code_block_line_numbers", true))
        } finally {
            ThemeStore.saveCodeBlockLineNumbers(context, previous)
        }
    }

    @Test
    fun englishLabel() {
        assertEquals("Wrap code blocks", translate(AppLanguage.EN, "代码块自动换行"))
        assertEquals("Code block line numbers", translate(AppLanguage.EN, "代码块行号"))
    }
}
