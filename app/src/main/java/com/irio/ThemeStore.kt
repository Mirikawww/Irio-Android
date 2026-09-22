package com.irio

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

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

    companion object {
        fun fromStored(value: String?): AccentColor =
            entries.firstOrNull { it.stored.equals(value, ignoreCase = true) } ?: BLACK
    }

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
}

object ThemeStore {
    private const val PREFS = "appearance_settings"
    private const val KEY = "theme_preference"
    private const val AUTO_AMOLED_KEY = "auto_amoled"
    private const val ACCENT_KEY = "accent_color"
    private const val CONTEXT_RING_KEY = "context_ring"
    private const val LEGACY_PREFS = "provider_settings"
    private const val LEGACY_KEY = "theme_preference"

    var preference by mutableStateOf(ThemePreference.SYSTEM)
        private set

    var autoAmoled by mutableStateOf(false)
        private set

    var accent by mutableStateOf(AccentColor.BLACK)
        private set

    /** 圆环暂未开放：始终关。 */
    var contextRing by mutableStateOf(false)
        private set
    val contextRingEnabled: Boolean = false

    @Volatile
    private var loaded = false

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val appearance = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val stored = appearance.getString(KEY, null)
                ?: context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE).getString(LEGACY_KEY, null)
            preference = ThemePreference.fromStored(stored)
            autoAmoled = appearance.getBoolean(AUTO_AMOLED_KEY, false)
            accent = AccentColor.fromStored(appearance.getString(ACCENT_KEY, null))
            contextRing = false
            if (appearance.getString(KEY, null) == null && stored != null) {
                appearance.edit().putString(KEY, stored).apply()
            }
            loaded = true
        }
    }

    fun save(context: Context, value: ThemePreference) {
        ensureLoaded(context)
        if (preference == value) return
        preference = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, value.stored)
            .apply()
    }

    fun saveAutoAmoled(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        if (autoAmoled == enabled) return
        autoAmoled = enabled
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(AUTO_AMOLED_KEY, enabled)
            .apply()
    }

    fun saveAccent(context: Context, value: AccentColor) {
        ensureLoaded(context)
        if (accent == value) return
        accent = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(ACCENT_KEY, value.stored)
            .apply()
    }

    fun saveContextRing(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        contextRing = false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(CONTEXT_RING_KEY, false)
            .apply()
    }
}
