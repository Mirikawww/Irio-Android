package com.irio

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ToolPreferencesStoreTest {
    @Test fun descriptionLookupPreservesEmptyOverrideAndUsesDefaultOnlyWhenMissing() {
        val preferences = ToolPreferences(descriptions = mapOf("empty" to "", "edited" to "custom"))
        assertEquals("default", preferences.description("missing", "default"))
        assertEquals("", preferences.description("empty", "default"))
        assertEquals("custom", preferences.description("edited", "default"))
    }

    @Test fun togglesAndDescriptionsRoundTripIndependentlyIncludingEmptyAndReset() {
        val context = RuntimeEnvironment.getApplication() as Context
        val name = "github_mcp_tool.with/odd:名字"
        ToolPreferencesStore.saveEnabled(context, name, false)
        ToolPreferencesStore.saveDescription(context, name, "  custom\n描述 \"quoted\"  ")
        ToolPreferencesStore.saveDescription(context, "other", "")
        val first = ToolPreferencesStore.load(context)
        assertFalse(first.isEnabled(name))
        assertEquals("  custom\n描述 \"quoted\"  ", first.descriptions[name])
        assertTrue(first.descriptions.containsKey("other"))
        assertEquals("", first.descriptions["other"])
        ToolPreferencesStore.saveEnabled(context, name, true)
        assertTrue(ToolPreferencesStore.load(context).isEnabled(name))
        assertEquals(first.descriptions, ToolPreferencesStore.load(context).descriptions)
        ToolPreferencesStore.resetDescription(context, name)
        assertFalse(ToolPreferencesStore.load(context).descriptions.containsKey(name))
        assertEquals("", ToolPreferencesStore.load(context).descriptions["other"])
        assertFalse("Already loaded requests are snapshots", first.isEnabled(name))
    }

    @Test fun missingPreferencesKeepEveryToolEnabled() {
        val context = RuntimeEnvironment.getApplication() as Context
        val preferences = ToolPreferencesStore.load(context)
        assertEquals(ToolPreferences(), preferences)
        assertTrue(preferences.isEnabled("future_tool"))
        assertEquals("original", preferences.description("future_tool", "original"))
    }
}
