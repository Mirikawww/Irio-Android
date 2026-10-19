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
    fun englishLabel() {
        assertEquals("Wrap code blocks", translate(AppLanguage.EN, "代码块自动换行"))
    }
}
