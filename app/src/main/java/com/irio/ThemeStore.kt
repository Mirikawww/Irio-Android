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

enum class GlassSurface(val stored: String) {
    USER_BUBBLE("user_bubble"),
    SIDEBAR_BUTTON("sidebar_button"),
    CONTEXT_RING("context_ring"),
    MODEL_SWITCHER("model_switcher"),
    COMPOSER("composer");

    companion object {
        fun fromStored(value: String?): GlassSurface? =
            entries.firstOrNull { it.stored == value }
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
    BLUE("蓝色", "blue", Color(0xFF3B82F6)),
    PINK("粉色", "pink", Color(0xFFEC4899)),
    MONET("莫奈", "monet", Color(0xFF6750A4));

    companion object {
        fun fromStored(value: String?): AccentColor =
            entries.firstOrNull { it.stored.equals(value, ignoreCase = true) } ?: BLUE
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
 * Light bubbles are a pale mix of the accent into white.
 * Dark bubbles mix that same accent into near-black — not a single navy/gray.
 * [accentColor] is the resolved accent (Monet's dynamic primary).
 */
internal fun userBubbleColors(
    dark: Boolean,
    accentColor: Color,
    onPanel: Color,
    editing: Boolean,
): UserBubbleColors {
    if (editing) return UserBubbleColors(accentColor.copy(alpha = 0.16f), accentColor)
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
    private const val MODEL_PICKER_ON_LEFT_KEY = "model_picker_on_left"
    private const val NEW_MESSAGE_SCROLL_KEY = "new_message_scroll"
    private const val COMPOSER_WRAP_ANIMATION_KEY = "composer_wrap_animation"
    private const val ENTER_TO_SEND_KEY = "enter_to_send"
    private const val LONG_TEXT_PASTE_KEY = "long_text_paste"
    private const val LONG_TEXT_PASTE_THRESHOLD_KEY = "long_text_paste_threshold"
    private const val GLASS_SURFACES_KEY = "glass_surfaces"
    private const val CODE_BLOCK_WRAP_KEY = "code_block_wrap"
    private const val CODE_BLOCK_LINE_NUMBERS_KEY = "code_block_line_numbers"
    private const val THINKING_BLOCK_KEY = "thinking_block"
    private const val MERGE_TOOL_CALLS_KEY = "merge_tool_calls"
    private const val SHOW_USER_AVATAR_KEY = "show_user_avatar"
    private const val LEGACY_PREFS = "provider_settings"
    private const val LEGACY_KEY = "theme_preference"

    var preference by mutableStateOf(ThemePreference.SYSTEM)
        private set

    var autoAmoled by mutableStateOf(false)
        private set

    var accent by mutableStateOf(AccentColor.BLUE)
        private set

    var language by mutableStateOf(AppLanguage.ZH_CN)
        private set

    /** 工具栏上的上下文占用圆环。默认关。 */
    var contextRing by mutableStateOf(false)
        private set

    /** 模型切换器位于侧边栏按钮右侧，菜单模型列在左。默认关。 */
    var modelPickerOnLeft by mutableStateOf(false)
        private set

    /** 流式回复的新字淡入。默认关。 */
    var glyphFade by mutableStateOf(false)
        private set

    /** 提供商详情里新增/移除模型的绿红底。默认关。 */
    var modelDiff by mutableStateOf(false)
        private set

    /** 发送新消息时用固定时长滑到底，并重新打开自动滚动。默认关。 */
    var newMessageScroll by mutableStateOf(false)
        private set

    /** 输入框换行时平滑延展。实验性，默认关。 */
    var composerWrapAnimation by mutableStateOf(false)
        private set

    /** 回车发送，而不是换行。默认关。 */
    var enterToSend by mutableStateOf(false)
        private set

    var longTextPaste by mutableStateOf(false)
        private set

    var longTextPasteThreshold by mutableStateOf(LongTextPaste.DEFAULT_THRESHOLD)
        private set

    /** 勾选后对应表面从纯色改为毛玻璃。默认全关。 */
    var glassSurfaces by mutableStateOf(emptySet<GlassSurface>())
        private set

    /** 代码块按气泡宽度自动换行。默认关，超长行横向滚动。 */
    var codeBlockWrap by mutableStateOf(false)
        private set

    /** 代码块左侧显示行号。折行仍算同一行。默认关。 */
    var codeBlockLineNumbers by mutableStateOf(false)
        private set

    /**
     * 为 true 时展开思考内容。设置项「默认折叠思考块」是它的反面，所以开关默认关。
     * 打开开关后进行中只显示「思考中」，结束后整行收起；内容仍保存。
     */
    var thinkingBlock by mutableStateOf(true)
        private set

    /** 将同一条回复中的工具结果合并为一行。默认关。 */
    var mergeToolCalls by mutableStateOf(false)
        private set

    /** 用户消息右侧显示头像，对齐气泡第一行。默认关。 */
    var showUserAvatar by mutableStateOf(false)
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
            contextRing = appearance.getBoolean(CONTEXT_RING_KEY, false)
            glyphFade = appearance.getBoolean(GLYPH_FADE_KEY, false)
            modelDiff = appearance.getBoolean(MODEL_DIFF_KEY, false)
            modelPickerOnLeft = appearance.getBoolean(MODEL_PICKER_ON_LEFT_KEY, false)
            newMessageScroll = appearance.getBoolean(NEW_MESSAGE_SCROLL_KEY, false)
            composerWrapAnimation = appearance.getBoolean(COMPOSER_WRAP_ANIMATION_KEY, false)
            enterToSend = appearance.getBoolean(ENTER_TO_SEND_KEY, false)
            longTextPaste = appearance.getBoolean(LONG_TEXT_PASTE_KEY, false)
            longTextPasteThreshold = LongTextPaste.normalizeThreshold(
                appearance.getInt(LONG_TEXT_PASTE_THRESHOLD_KEY, LongTextPaste.DEFAULT_THRESHOLD),
            )
            glassSurfaces = appearance.getStringSet(GLASS_SURFACES_KEY, emptySet())
                .orEmpty()
                .mapNotNull(GlassSurface::fromStored)
                .toSet()
            codeBlockWrap = appearance.getBoolean(CODE_BLOCK_WRAP_KEY, false)
            codeBlockLineNumbers = appearance.getBoolean(CODE_BLOCK_LINE_NUMBERS_KEY, false)
            thinkingBlock = appearance.getBoolean(THINKING_BLOCK_KEY, true)
            mergeToolCalls = appearance.getBoolean(MERGE_TOOL_CALLS_KEY, false)
            showUserAvatar = appearance.getBoolean(SHOW_USER_AVATAR_KEY, false)
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

    fun saveNewMessageScroll(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        if (newMessageScroll == enabled) return
        newMessageScroll = enabled
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(NEW_MESSAGE_SCROLL_KEY, enabled)
            .apply()
    }

    fun saveGlassSurface(context: Context, surface: GlassSurface, enabled: Boolean) {
        ensureLoaded(context)
        val next = if (enabled) glassSurfaces + surface else glassSurfaces - surface
        if (next == glassSurfaces) return
        glassSurfaces = next
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(GLASS_SURFACES_KEY, next.map { it.stored }.toSet())
            .apply()
    }

    fun saveComposerWrapAnimation(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        if (composerWrapAnimation == enabled) return
        composerWrapAnimation = enabled
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(COMPOSER_WRAP_ANIMATION_KEY, enabled)
            .apply()
    }

    fun saveEnterToSend(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        if (enterToSend == enabled) return
        enterToSend = enabled
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ENTER_TO_SEND_KEY, enabled)
            .apply()
    }

    fun saveLongTextPaste(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        longTextPaste = enabled
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(LONG_TEXT_PASTE_KEY, enabled).apply()
    }

    fun saveLongTextPasteThreshold(context: Context, value: Int) {
        ensureLoaded(context)
        longTextPasteThreshold = LongTextPaste.normalizeThreshold(value)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(LONG_TEXT_PASTE_THRESHOLD_KEY, longTextPasteThreshold).apply()
    }

    fun saveCodeBlockWrap(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        if (codeBlockWrap == enabled) return
        codeBlockWrap = enabled
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(CODE_BLOCK_WRAP_KEY, enabled)
            .apply()
    }

    fun saveCodeBlockLineNumbers(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        if (codeBlockLineNumbers == enabled) return
        codeBlockLineNumbers = enabled
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(CODE_BLOCK_LINE_NUMBERS_KEY, enabled)
            .apply()
    }

    fun saveThinkingBlock(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        if (thinkingBlock == enabled) return
        thinkingBlock = enabled
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(THINKING_BLOCK_KEY, enabled)
            .apply()
    }

    fun saveMergeToolCalls(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        if (mergeToolCalls == enabled) return
        mergeToolCalls = enabled
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(MERGE_TOOL_CALLS_KEY, enabled)
            .apply()
    }

    fun saveModelPickerOnLeft(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        if (modelPickerOnLeft == enabled) return
        modelPickerOnLeft = enabled
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(MODEL_PICKER_ON_LEFT_KEY, enabled)
            .apply()
    }

    fun saveContextRing(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        if (contextRing == enabled) return
        contextRing = enabled
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(CONTEXT_RING_KEY, enabled)
            .apply()
    }

    fun saveShowUserAvatar(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        if (showUserAvatar == enabled) return
        showUserAvatar = enabled
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(SHOW_USER_AVATAR_KEY, enabled)
            .apply()
    }
}
