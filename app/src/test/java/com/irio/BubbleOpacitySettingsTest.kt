package com.irio

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class BubbleOpacitySettingsTest {
    private fun reload(context: Context) {
        ThemeStore::class.java.getDeclaredField("loaded").apply { isAccessible = true }.setBoolean(ThemeStore, false)
        ThemeStore.ensureLoaded(context)
    }

    @Test fun defaultsToTwentyFiveAndPersistsClampedValuesAcrossReloads() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("appearance_settings", Context.MODE_PRIVATE)
        val previous = if (prefs.contains("bubble_opacity")) prefs.getInt("bubble_opacity", 25) else null
        try {
            prefs.edit().remove("bubble_opacity").commit()
            reload(context)
            assertEquals(25, ThemeStore.bubbleOpacity)
            for ((input, expected) in listOf(0 to 0, 25 to 25, 37 to 37, 100 to 100, -1 to 0, 101 to 100)) {
                ThemeStore.saveBubbleOpacity(context, input)
                assertEquals(expected, ThemeStore.bubbleOpacity)
                assertEquals(expected, prefs.getInt("bubble_opacity", -1))
                reload(context)
                assertEquals(expected, ThemeStore.bubbleOpacity)
            }
            for ((stored, expected) in listOf(-20 to 0, 120 to 100)) {
                prefs.edit().putInt("bubble_opacity", stored).commit()
                reload(context)
                assertEquals(expected, ThemeStore.bubbleOpacity)
            }
        } finally {
            prefs.edit().apply {
                if (previous == null) remove("bubble_opacity") else putInt("bubble_opacity", previous)
            }.commit()
            reload(context)
        }
    }

    @Test fun searchableAndLocalizedInBothLanguages() {
        assertEquals("Bubble opacity", translate(AppLanguage.EN, "气泡透明度"))
        val chinese = searchSettings("气泡透明度") { it }.single()
        assertEquals("appearance.bubbleOpacity", chinese.id)
        assertEquals(AppearanceSettingsActivity::class.java, chinese.destination)
        val english = searchSettings("Bubble opacity") { translate(AppLanguage.EN, it) }.single()
        assertEquals(chinese.id, english.id)
    }
}
