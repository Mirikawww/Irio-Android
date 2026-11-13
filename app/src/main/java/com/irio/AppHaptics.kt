package com.irio

import android.content.Context
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings

/** Every app-authored feedback location has its own persistent choice. */
internal enum class HapticEvent(val stored: String, val label: String, val legacyStored: String? = null) {
    SEND_MESSAGE("send", "发送消息"),
    STOP_REPLY("stop", "停止输出"),
    COPY_MESSAGE("copy", "复制消息"),
    QUOTE_MESSAGE("quote", "引用消息"),
    EDIT_MESSAGE("edit", "编辑消息"),
    RETRY_MESSAGE("retry", "重新生成回复"),
    REACTION("reaction", "消息回应"),
    MODEL_REACTION("model_reaction", "模型添加回应"),
    REPLY_COMPLETED("reply_completed", "模型完成回复"),
    SELECT_MODEL("model", "切换模型"),
    THINKING_LEVEL("thinking", "调节思考强度"),
    SWITCH_ON("switch_toggle_on", "普通开关开启", "switch_toggle"),
    SWITCH_OFF("switch_toggle_off", "普通开关关闭", "switch_toggle"),
    CHECKBOX_ON("checkbox_toggle_on", "复选框开启", "checkbox_toggle"),
    CHECKBOX_OFF("checkbox_toggle_off", "复选框关闭", "checkbox_toggle"),
    FAST_MODE_ON("fast_mode_on", "快速开关开启", "fast_mode"),
    FAST_MODE_OFF("fast_mode_off", "快速开关关闭", "fast_mode"),
    WEB_SEARCH_ON("web_search_on", "网络搜索开关开启", "web_search"),
    WEB_SEARCH_OFF("web_search_off", "网络搜索开关关闭", "web_search"),
    AUTO_READ_ON("auto_read_on", "自动朗读开关开启", "auto_read"),
    AUTO_READ_OFF("auto_read_off", "自动朗读开关关闭", "auto_read"),
    QUICK_LONG_PRESS("quick_long_press", "快捷开关长按展开"),
    QUICK_LONG_RELEASE("quick_long_release", "快捷开关松手收起"),
    SNACKBAR("snackbar", "Snackbar 弹出"),
    STREAM_OUTPUT("stream_output", "模型输出"),
    NEW_CONVERSATION("new_conversation", "新建对话"),
    SWITCH_CONVERSATION("switch_conversation", "切换对话"),
    CONVERSATION_MENU("conversation_menu", "长按对话"),
    START_RECORDING("record_start", "开始录音"),
    STOP_RECORDING("record_stop", "停止录音");

    val searchId: String get() = "laboratory.haptics.$stored"
}

internal data class HapticSettings(
    val enabled: Boolean,
    val disabledEvents: Set<HapticEvent>,
) {
    fun isEnabled(event: HapticEvent): Boolean = event !in disabledEvents
}

/** Shares the existing portable appearance preference namespace; no extra backup store. */
internal object HapticPreferences {
    private fun preferences(context: Context) = context.getSharedPreferences("appearance_settings", Context.MODE_PRIVATE)

    fun load(context: Context): HapticSettings {
        val preferences = preferences(context)
        return HapticSettings(
            enabled = preferences.getBoolean("haptics_enabled", false),
            disabledEvents = HapticEvent.entries.filterNot { eventEnabled(preferences, it) }.toSet(),
        )
    }

    fun saveEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean("haptics_enabled", enabled).apply()
    }

    fun isEnabled(context: Context, event: HapticEvent): Boolean {
        val preferences = preferences(context)
        return preferences.getBoolean("haptics_enabled", false) &&
            eventEnabled(preferences, event)
    }

    private fun eventEnabled(preferences: android.content.SharedPreferences, event: HapticEvent): Boolean =
        preferences.getBoolean("haptics_${event.stored}",
            event.legacyStored?.let { preferences.getBoolean("haptics_$it", true) } ?: true)

    fun saveEvent(context: Context, event: HapticEvent, enabled: Boolean) {
        preferences(context).edit().putBoolean("haptics_${event.stored}", enabled).apply()
    }
}

internal object AppHaptics {
    fun perform(context: Context, event: HapticEvent) {
        if (HapticPreferences.isEnabled(context, event)) vibrate(context)
    }

    private fun vibrate(context: Context) {
        // Touch usage, with no bypass flags: honor both the system preference and OEM policy.
        // Unsupported hardware or denied vibration must never interrupt the user's action.
        runCatching {
            if (Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) return
            val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
            if (!vibrator.hasVibrator()) return
            val amplitude = if (vibrator.hasAmplitudeControl()) 230 else VibrationEffect.DEFAULT_AMPLITUDE
            vibrator.vibrate(
                VibrationEffect.createOneShot(34L, amplitude),
                VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_TOUCH).build(),
            )
        }
    }
}
