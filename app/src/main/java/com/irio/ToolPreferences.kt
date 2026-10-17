package com.irio

import android.content.Context

/** Request-scoped tool choices. Absent names stay enabled, including newly discovered MCP tools. */
data class ToolPreferences(
    val disabledTools: Set<String> = emptySet(),
    val descriptions: Map<String, String> = emptyMap(),
) {
    fun isEnabled(name: String): Boolean = name !in disabledTools

    fun description(name: String, default: String): String = descriptions[name] ?: default
}

object ToolPreferencesStore {
    private const val PREFS = "tool_preferences"
    private const val ENABLED = "enabled:"
    private const val DESCRIPTION = "description:"

    fun load(context: Context): ToolPreferences {
        val values = preferences(context).all
        return ToolPreferences(
            disabledTools = values.entries.mapNotNull { (key, value) ->
                key.removePrefix(ENABLED).takeIf { key.startsWith(ENABLED) && value == false }
            }.toSet(),
            descriptions = values.entries.mapNotNull { (key, value) ->
                if (key.startsWith(DESCRIPTION) && value is String) key.removePrefix(DESCRIPTION) to value else null
            }.toMap(),
        )
    }

    fun saveEnabled(context: Context, name: String, enabled: Boolean) {
        preferences(context).edit().apply {
            if (enabled) remove(ENABLED + name) else putBoolean(ENABLED + name, false)
        }.apply()
    }

    /** Empty strings are intentional overrides; only reset removes an override. */
    fun saveDescription(context: Context, name: String, description: String) {
        preferences(context).edit().putString(DESCRIPTION + name, description).apply()
    }

    fun resetDescription(context: Context, name: String) {
        preferences(context).edit().remove(DESCRIPTION + name).apply()
    }

    private fun preferences(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
