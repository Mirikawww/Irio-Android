package com.irio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import org.json.JSONObject

enum class ResolvedTheme {
    LIGHT,
    DARK,
    AMOLED;

    val isDark: Boolean get() = this != LIGHT
}

enum class ThemePreference(val label: String, val stored: String) {
    SYSTEM("跟随系统", "system"),
    LIGHT("浅色", "light"),
    DARK("深色", "dark"),
    AMOLED("AMOLED", "amoled");

    fun resolve(systemDark: Boolean, autoAmoled: Boolean): ResolvedTheme = when (this) {
        SYSTEM -> when {
            !systemDark -> ResolvedTheme.LIGHT
            autoAmoled -> ResolvedTheme.AMOLED
            else -> ResolvedTheme.DARK
        }
        LIGHT -> ResolvedTheme.LIGHT
        DARK -> ResolvedTheme.DARK
        AMOLED -> ResolvedTheme.AMOLED
    }

    fun isDark(systemDark: Boolean): Boolean = resolve(systemDark, autoAmoled = false).isDark

    companion object {
        fun fromStored(value: String?): ThemePreference =
            entries.firstOrNull { it.stored.equals(value, ignoreCase = true) } ?: SYSTEM
    }
}

enum class AccentColor(val label: String, val stored: String, val color: Color) {
    BLACK("黑色", "black", Color.Black),
    BLUE("蓝色", "blue", Color(0xFF3B82F6));

    fun onContent(): Color {
        val luminance = color.red * 0.299f + color.green * 0.587f + color.blue * 0.114f
        return if (luminance > 0.6f) Color.Black else Color.White
    }

    /** Pale solid tint for user bubbles. Not the raw accent. */
    fun softened(): Color {
        val mix = 0.22f
        return Color(
            red = color.red * mix + (1f - mix),
            green = color.green * mix + (1f - mix),
            blue = color.blue * mix + (1f - mix),
        )
    }

    companion object {
        fun fromStored(value: String?): AccentColor =
            entries.firstOrNull { it.stored.equals(value, ignoreCase = true) } ?: BLACK
    }
}

object ThemeStore {
    private const val KEY = "theme_preference"
    private const val AUTO_AMOLED_KEY = "auto_amoled"
    private const val ACCENT_KEY = "accent_color"
    private const val CONTEXT_RING_KEY = "context_ring"

    private val initial = loadInitial()
    var preference by mutableStateOf(initial.first)
        private set
    var autoAmoled by mutableStateOf(initial.second)
        private set
    var accent by mutableStateOf(initial.third)
        private set
    var contextRing by mutableStateOf(false)
        private set
    val contextRingEnabled: Boolean = false

    fun save(value: ThemePreference) {
        if (preference == value) return
        preference = value
        updateAppearance { it.put(KEY, value.stored) }
    }

    fun saveAutoAmoled(enabled: Boolean) {
        if (autoAmoled == enabled) return
        autoAmoled = enabled
        updateAppearance { it.put(AUTO_AMOLED_KEY, enabled) }
    }

    fun saveAccent(value: AccentColor) {
        if (accent == value) return
        accent = value
        updateAppearance { it.put(ACCENT_KEY, value.stored) }
    }

    fun saveContextRing(enabled: Boolean) {
        contextRing = false
        updateAppearance { it.put(CONTEXT_RING_KEY, false) }
    }

    private fun updateAppearance(update: (JSONObject) -> Unit) {
        val appearance = runCatching { JSONObject(AppPaths.appearanceFile.readText()) }
            .getOrElse { JSONObject() }
        update(appearance)
        AppPaths.appearanceFile.writeText(appearance.toString())
    }

    private fun loadInitial(): Triple<ThemePreference, Boolean, AccentColor> {
        val appearance = runCatching { JSONObject(AppPaths.appearanceFile.readText()) }.getOrNull()
        val fromAppearance = appearance?.optString(KEY)?.takeIf { it.isNotBlank() }
        val autoAmoled = appearance?.optBoolean(AUTO_AMOLED_KEY, false) ?: false
        val accent = AccentColor.fromStored(appearance?.optString(ACCENT_KEY)?.takeIf { it.isNotBlank() })
        if (fromAppearance != null) {
            return Triple(ThemePreference.fromStored(fromAppearance), autoAmoled, accent)
        }
        val legacy = runCatching {
            JSONObject(AppPaths.providersFile.readText()).optString(KEY)
        }.getOrNull()?.takeIf { it.isNotBlank() }
        val preference = ThemePreference.fromStored(legacy)
        if (legacy != null) {
            updateAppearance { it.put(KEY, preference.stored) }
        }
        return Triple(preference, autoAmoled, accent)
    }
}
