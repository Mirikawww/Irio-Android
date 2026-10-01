package com.irio

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
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

    companion object {
        fun fromStored(value: String?): AccentColor =
            entries.firstOrNull { it.stored.equals(value, ignoreCase = true) } ?: BLACK
    }

    fun onContent(): Color {
        val luminance = color.red * 0.299f + color.green * 0.587f + color.blue * 0.114f
        return if (luminance > 0.6f) Color.Black else Color.White
    }

    /** Pale solid tint for user bubbles. Not the raw accent. */
    fun softened(): Color = color.softened()
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
    private const val PREFS = "appearance_settings"
    private const val KEY = "theme_preference"
    private const val AUTO_AMOLED_KEY = "auto_amoled"
    private const val ACCENT_KEY = "accent_color"
    private const val LANGUAGE_KEY = "language"
    private const val CONTEXT_RING_KEY = "context_ring"
    private const val GLYPH_FADE_KEY = "glyph_fade"
    private const val MODEL_DIFF_KEY = "model_diff"
    private const val LEGACY_PREFS = "provider_settings"
    private const val LEGACY_KEY = "theme_preference"

    var preference by mutableStateOf(ThemePreference.SYSTEM)
        private set

    var autoAmoled by mutableStateOf(false)
        private set

    var accent by mutableStateOf(AccentColor.BLACK)
        private set

    var language by mutableStateOf(AppLanguage.ZH_CN)
        private set

    /** 圆环暂未开放：始终关。 */
    var contextRing by mutableStateOf(false)
        private set
    val contextRingEnabled: Boolean = false

    /** 流式回复的新字淡入。默认开。 */
    var glyphFade by mutableStateOf(true)
        private set

    /** 提供商详情里新增/移除模型的绿红底。默认开。 */
    var modelDiff by mutableStateOf(true)
        private set

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
            language = AppLanguage.fromStored(appearance.getString(LANGUAGE_KEY, null))
            contextRing = false
            glyphFade = appearance.getBoolean(GLYPH_FADE_KEY, true)
            modelDiff = appearance.getBoolean(MODEL_DIFF_KEY, true)
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

    fun saveLanguage(context: Context, value: AppLanguage) {
        ensureLoaded(context)
        if (language == value) return
        language = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(LANGUAGE_KEY, value.stored)
            .apply()
    }

    fun saveGlyphFade(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        if (glyphFade == enabled) return
        glyphFade = enabled
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(GLYPH_FADE_KEY, enabled)
            .apply()
    }

    fun saveModelDiff(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        if (modelDiff == enabled) return
        modelDiff = enabled
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(MODEL_DIFF_KEY, enabled)
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
