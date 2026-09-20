package com.irio

import android.content.Context

object ToolStore {
    const val MAX_ROUNDS_MIN = 1
    const val MAX_ROUNDS_MAX = 25
    const val MAX_ROUNDS_DEFAULT = 4

    private const val PREFS = "tool_settings"
    private const val MAX_ROUNDS = "max_tool_rounds"
    private const val IMAGE_GEN_MODEL = "image_gen_model"
    private const val TITLE_MODEL = "title_model"

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

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
