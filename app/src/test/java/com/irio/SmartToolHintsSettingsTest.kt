package com.irio

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class SmartToolHintsSettingsTest {
    @Test
    fun missingPreferenceIsDisabledAndExplicitChoiceSurvivesReload() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("tool_settings", Context.MODE_PRIVATE).edit().clear().commit()
        assertFalse(ToolStore.loadSmartToolHints(context))
        ToolStore.saveSmartToolHints(context, true)
        assertTrue(ToolStore.loadSmartToolHints(context))
        ToolStore.saveSmartToolHints(context, false)
        assertFalse(ToolStore.loadSmartToolHints(context))
    }
}
