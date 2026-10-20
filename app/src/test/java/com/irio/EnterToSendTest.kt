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
class EnterToSendTest {
    @Test
    fun loneNewlineSendsUnlessShiftAllowsIt() {
        assertEquals(NewlineEdit.Send, classifyNewlineEdit(1, "\n", allowNewline = false))
        assertEquals(NewlineEdit.KeepNewline, classifyNewlineEdit(1, "\n", allowNewline = true))
        assertEquals(NewlineEdit.Ignore, classifyNewlineEdit(1, "hello\n", allowNewline = false))
        assertEquals(NewlineEdit.Ignore, classifyNewlineEdit(1, "\n\n", allowNewline = false))
        assertEquals(NewlineEdit.Ignore, classifyNewlineEdit(2, "\n", allowNewline = false))
        assertEquals(NewlineEdit.Ignore, classifyNewlineEdit(1, "a", allowNewline = false))
    }

    @Test
    fun onlyTheLatestEnterSends() {
        val posted = mutableListOf<() -> Unit>()
        val session = EnterToSendSession { posted += it }
        var sends = 0
        session.request { sends++ }
        session.request { sends++ }
        assertEquals(0, sends)
        posted.forEach { it() }
        assertEquals(1, sends)
    }

    @Test
    fun enterToSendPersistsAndCanStayOff() {
        val context = RuntimeEnvironment.getApplication()
        val previous = ThemeStore.enterToSend
        val prefs = context.getSharedPreferences("appearance_settings", Context.MODE_PRIVATE)
        try {
            ThemeStore.saveEnterToSend(context, true)
            assertTrue(ThemeStore.enterToSend)
            assertTrue(prefs.getBoolean("enter_to_send", false))
            ThemeStore.saveEnterToSend(context, false)
            assertFalse(ThemeStore.enterToSend)
            assertFalse(prefs.getBoolean("enter_to_send", true))
        } finally {
            ThemeStore.saveEnterToSend(context, previous)
        }
    }

    @Test
    fun englishLabel() {
        assertEquals("Send with Enter", translate(AppLanguage.EN, "按 Enter 发送"))
    }
}
