package com.irio

import android.content.Context

object ToolStore {
    const val MAX_ROUNDS_MIN = 1
    const val MAX_ROUNDS_MAX = 25
    const val MAX_ROUNDS_DEFAULT = 4
    const val COMPRESSION_PERCENT_MIN = 1
    const val COMPRESSION_PERCENT_MAX = 100
    const val COMPRESSION_TRIGGER_DEFAULT = 90
    const val COMPRESSION_TARGET_DEFAULT = 10

    private const val PREFS = "tool_settings"
    private const val MAX_ROUNDS = "max_tool_rounds"
    private const val IMAGE_GEN_MODEL = "image_gen_model"
    private const val TITLE_MODEL = "title_model"
    private const val MESSAGE_TIMESTAMPS = "message_timestamps"
    private const val CONTEXT_COMPRESSION = "context_compression"
    private const val COMPRESSION_TRIGGER = "context_compression_trigger"
    private const val COMPRESSION_TARGET = "context_compression_target"
    private const val COMPRESSION_MODEL = "context_compression_model"

    fun loadMaxRounds(context: Context): Int =
        clampMaxRounds(preferences(context).getInt(MAX_ROUNDS, MAX_ROUNDS_DEFAULT))

    fun saveMaxRounds(context: Context, value: Int) {
        preferences(context).edit().putInt(MAX_ROUNDS, clampMaxRounds(value)).apply()
    }

    fun clampMaxRounds(value: Int): Int = value.coerceIn(MAX_ROUNDS_MIN, MAX_ROUNDS_MAX)

    /** 图片生成工具使用的默认模型 id；空串表示未配置。 */
    fun loadImageGenModel(context: Context): String =
        preferences(context).getString(IMAGE_GEN_MODEL, "").orEmpty()

    fun saveImageGenModel(context: Context, model: String) {
        preferences(context).edit().putString(IMAGE_GEN_MODEL, model).apply()
    }

    /** 标题生成使用的模型 id；空串表示跟随当前对话模型。 */
    fun loadTitleModel(context: Context): String =
        preferences(context).getString(TITLE_MODEL, "").orEmpty()

    fun saveTitleModel(context: Context, model: String) {
        preferences(context).edit().putString(TITLE_MODEL, model).apply()
    }

    /** 是否在发给模型的用户消息前加上发送时间。默认关闭。 */
    fun loadMessageTimestamps(context: Context): Boolean =
        preferences(context).getBoolean(MESSAGE_TIMESTAMPS, false)

    fun saveMessageTimestamps(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(MESSAGE_TIMESTAMPS, enabled).apply()
    }

    /** 是否压缩对话上下文。默认关闭。 */
    fun loadContextCompression(context: Context): Boolean =
        preferences(context).getBoolean(CONTEXT_COMPRESSION, false)

    fun saveContextCompression(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(CONTEXT_COMPRESSION, enabled).apply()
    }

    /** 上下文占用达到该百分比时触发压缩。缺键为 90。 */
    fun loadCompressionTrigger(context: Context): Int =
        clampCompressionPercent(preferences(context).getInt(COMPRESSION_TRIGGER, COMPRESSION_TRIGGER_DEFAULT))

    fun saveCompressionTrigger(context: Context, value: Int) {
        preferences(context).edit().putInt(COMPRESSION_TRIGGER, clampCompressionPercent(value)).apply()
    }

    /** 压缩后希望保留的上下文百分比。缺键为 10。 */
    fun loadCompressionTarget(context: Context): Int =
        clampCompressionPercent(preferences(context).getInt(COMPRESSION_TARGET, COMPRESSION_TARGET_DEFAULT))

    fun saveCompressionTarget(context: Context, value: Int) {
        preferences(context).edit().putInt(COMPRESSION_TARGET, clampCompressionPercent(value)).apply()
    }

    /** 压缩使用的模型 id。空串表示跟随当前对话模型。 */
    fun loadCompressionModel(context: Context): String =
        preferences(context).getString(COMPRESSION_MODEL, "").orEmpty()

    fun saveCompressionModel(context: Context, model: String) {
        preferences(context).edit().putString(COMPRESSION_MODEL, model).apply()
    }

    fun clampCompressionPercent(value: Int): Int =
        value.coerceIn(COMPRESSION_PERCENT_MIN, COMPRESSION_PERCENT_MAX)

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
