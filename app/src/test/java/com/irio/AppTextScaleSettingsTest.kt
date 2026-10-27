package com.irio

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AppTextScaleSettingsTest {
    private fun reload(context: Context) {
        ThemeStore::class.java.getDeclaredField("loaded").apply { isAccessible = true }.setBoolean(ThemeStore, false)
        ThemeStore.ensureLoaded(context)
    }

    @Test fun defaultsToSystemAndOneHundredAndPersistsModeAndClampedPercent() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("appearance_settings", Context.MODE_PRIVATE)
        val oldMode = prefs.getString("text_scale_mode", null)
        val oldPercent = if (prefs.contains("text_scale_percent")) prefs.getInt("text_scale_percent", 100) else null
        try {
            prefs.edit().remove("text_scale_mode").remove("text_scale_percent").commit()
            reload(context)
            assertEquals(TextScaleMode.SYSTEM, ThemeStore.textScaleMode)
            assertEquals(100, ThemeStore.textScalePercent)
            assertEquals(TextScaleMode.SYSTEM, TextScaleMode.fromStored("unknown"))
            assertEquals(TextScaleMode.CUSTOM, TextScaleMode.fromStored("CUSTOM"))
            ThemeStore.saveTextScaleMode(context, TextScaleMode.CUSTOM)
            for ((input, expected) in listOf(50 to 50, 100 to 100, 137 to 137, 200 to 200, -1 to 50, 201 to 200)) {
                ThemeStore.saveTextScalePercent(context, input)
                reload(context)
                assertEquals(TextScaleMode.CUSTOM, ThemeStore.textScaleMode)
                assertEquals(expected, ThemeStore.textScalePercent)
                assertEquals(expected, prefs.getInt("text_scale_percent", -1))
            }
            ThemeStore.saveTextScalePercent(context, 137)
            ThemeStore.saveTextScaleMode(context, TextScaleMode.SYSTEM)
            reload(context)
            assertEquals(TextScaleMode.SYSTEM, ThemeStore.textScaleMode)
            assertEquals(137, ThemeStore.textScalePercent)
            ThemeStore.saveTextScaleMode(context, TextScaleMode.CUSTOM)
            assertEquals(137, ThemeStore.textScalePercent)
            for ((stored, expected) in listOf(-100 to 50, 999 to 200)) {
                prefs.edit().putString("text_scale_mode", "invalid").putInt("text_scale_percent", stored).commit()
                reload(context)
                assertEquals(TextScaleMode.SYSTEM, ThemeStore.textScaleMode)
                assertEquals(expected, ThemeStore.textScalePercent)
            }
        } finally {
            prefs.edit().apply {
                if (oldMode == null) remove("text_scale_mode") else putString("text_scale_mode", oldMode)
                if (oldPercent == null) remove("text_scale_percent") else putInt("text_scale_percent", oldPercent)
            }.commit()
            reload(context)
        }
    }
}
