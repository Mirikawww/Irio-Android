package com.irio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import org.json.JSONObject
import kotlin.math.pow

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

enum class AppLanguage(val label: String, val stored: String) {
    ZH_CN("简体中文", "zh-CN"),
    EN("English", "en");

    companion object {
        fun fromStored(value: String?): AppLanguage =
            entries.firstOrNull { it.stored.equals(value, ignoreCase = true) } ?: ZH_CN
    }
}

enum class AccentColor(val label: String, val stored: String, val color: Color) {
    BLACK("黑色", "black", Color.Black),
    BLUE("蓝色", "blue", Color(0xFF3B82F6)),
    PINK("粉色", "pink", Color(0xFFEC4899)),
    MONET("莫奈", "monet", Color(0xFF6750A4));

    fun onContent(): Color {
        val luminance = color.red * 0.299f + color.green * 0.587f + color.blue * 0.114f
        return if (luminance > 0.6f) Color.Black else Color.White
    }

    /** Pale solid tint for user bubbles. Not the raw accent. */
    fun softened(): Color = color.softened()

    companion object {
        fun fromStored(value: String?): AccentColor =
            entries.firstOrNull { it.stored.equals(value, ignoreCase = true) } ?: BLACK
    }
}

internal fun Color.softened(mix: Float = 0.22f): Color = Color(
    red = red * mix + (1f - mix),
    green = green * mix + (1f - mix),
    blue = blue * mix + (1f - mix),
)

/** [fraction] of this color over [base]. Used for dark user bubbles. */
internal fun Color.mixOver(base: Color, fraction: Float): Color {
    val keep = 1f - fraction
    return Color(
        red = red * fraction + base.red * keep,
        green = green * fraction + base.green * keep,
        blue = blue * fraction + base.blue * keep,
    )
}

internal data class UserBubbleColors(val background: Color, val content: Color)

/**
 * Light non-black bubbles are a pale mix of the accent into white.
 * Dark non-black bubbles mix that same accent into near-black — not a single navy/gray.
 * Black accent stays neutral. [accentColor] is the resolved accent (Monet's dynamic primary).
 */
internal fun userBubbleColors(
    dark: Boolean,
    accent: AccentColor,
    accentColor: Color,
    panel: Color,
    onPanel: Color,
    editing: Boolean,
): UserBubbleColors {
    if (editing) return UserBubbleColors(accentColor.copy(alpha = 0.16f), accentColor)
    if (accent == AccentColor.BLACK) {
        return if (dark) UserBubbleColors(Color(0xFF2A2A2A), onPanel) else UserBubbleColors(panel, onPanel)
    }
    return if (dark) {
        UserBubbleColors(accentColor.mixOver(Color(0xFF141414), 0.28f), onPanel)
    } else {
        UserBubbleColors(accentColor.softened(), Color(0xFF1A1A1A))
    }
}

/** Dark destructive buttons keep white text at WCAG AA contrast (4.5:1 or higher). */
internal val darkDestructiveButtonColor = Color(0xFFD32F2F)
internal val darkDestructiveButtonContentColor = Color.White
internal val darkErrorTextColor = Color(0xFFEF5350)

internal fun wcagContrastRatio(first: Color, second: Color): Float {
    fun relativeLuminance(color: Color): Float {
        fun linear(channel: Float): Float =
            if (channel <= 0.04045f) channel / 12.92f else ((channel + 0.055f) / 1.055f).pow(2.4f)
        return 0.2126f * linear(color.red) + 0.7152f * linear(color.green) + 0.0722f * linear(color.blue)
    }
    val firstLuminance = relativeLuminance(first)
    val secondLuminance = relativeLuminance(second)
    return (maxOf(firstLuminance, secondLuminance) + 0.05f) /
        (minOf(firstLuminance, secondLuminance) + 0.05f)
}

object ThemeStore {
    private const val KEY = "theme_preference"
    private const val AUTO_AMOLED_KEY = "auto_amoled"
    private const val ACCENT_KEY = "accent_color"
    private const val LANGUAGE_KEY = "language"
    private const val CONTEXT_RING_KEY = "context_ring"
    private const val GLYPH_FADE_KEY = "glyph_fade"
    private const val MODEL_DIFF_KEY = "model_diff"
    private const val NEW_MESSAGE_SCROLL_KEY = "new_message_scroll"

    private val initial = loadInitial()
    var preference by mutableStateOf(initial.preference)
        private set
    var autoAmoled by mutableStateOf(initial.autoAmoled)
        private set
    var accent by mutableStateOf(initial.accent)
        private set
    var language by mutableStateOf(initial.language)
        private set
    var contextRing by mutableStateOf(false)
        private set
    val contextRingEnabled: Boolean = false
    var glyphFade by mutableStateOf(initial.glyphFade)
        private set
    var modelDiff by mutableStateOf(initial.modelDiff)
        private set
    var newMessageScroll by mutableStateOf(initial.newMessageScroll)
        private set

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

    fun saveLanguage(value: AppLanguage) {
        if (language == value) return
        language = value
        updateAppearance { it.put(LANGUAGE_KEY, value.stored) }
    }

    fun saveGlyphFade(enabled: Boolean) {
        if (glyphFade == enabled) return
        glyphFade = enabled
        updateAppearance { it.put(GLYPH_FADE_KEY, enabled) }
    }

    fun saveModelDiff(enabled: Boolean) {
        if (modelDiff == enabled) return
        modelDiff = enabled
        updateAppearance { it.put(MODEL_DIFF_KEY, enabled) }
    }

    fun saveNewMessageScroll(enabled: Boolean) {
        if (newMessageScroll == enabled) return
        newMessageScroll = enabled
        updateAppearance { it.put(NEW_MESSAGE_SCROLL_KEY, enabled) }
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

    private fun loadInitial(): LoadedAppearance {
        val appearance = runCatching { JSONObject(AppPaths.appearanceFile.readText()) }.getOrNull()
        val fromAppearance = appearance?.optString(KEY)?.takeIf { it.isNotBlank() }
        val autoAmoled = appearance?.optBoolean(AUTO_AMOLED_KEY, false) ?: false
        val accent = AccentColor.fromStored(appearance?.optString(ACCENT_KEY)?.takeIf { it.isNotBlank() })
        val language = AppLanguage.fromStored(appearance?.optString(LANGUAGE_KEY)?.takeIf { it.isNotBlank() })
        val glyphFade = appearance?.optBoolean(GLYPH_FADE_KEY, true) ?: true
        val modelDiff = appearance?.optBoolean(MODEL_DIFF_KEY, true) ?: true
        val newMessageScroll = appearance?.optBoolean(NEW_MESSAGE_SCROLL_KEY, true) ?: true
        if (fromAppearance != null) {
            return LoadedAppearance(
                ThemePreference.fromStored(fromAppearance),
                autoAmoled,
                accent,
                language,
                glyphFade,
                modelDiff,
                newMessageScroll,
            )
        }
        val legacy = runCatching {
            JSONObject(AppPaths.providersFile.readText()).optString(KEY)
        }.getOrNull()?.takeIf { it.isNotBlank() }
        val preference = ThemePreference.fromStored(legacy)
        if (legacy != null) {
            updateAppearance { it.put(KEY, preference.stored) }
        }
        return LoadedAppearance(preference, autoAmoled, accent, language, glyphFade, modelDiff, newMessageScroll)
    }

    private data class LoadedAppearance(
        val preference: ThemePreference,
        val autoAmoled: Boolean,
        val accent: AccentColor,
        val language: AppLanguage,
        val glyphFade: Boolean,
        val modelDiff: Boolean,
        val newMessageScroll: Boolean,
    )
}
