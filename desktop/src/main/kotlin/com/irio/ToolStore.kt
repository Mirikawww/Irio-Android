package com.irio

import org.json.JSONObject

object ToolStore {
    const val MAX_ROUNDS_MIN = 1
    const val MAX_ROUNDS_MAX = 25
    const val MAX_ROUNDS_DEFAULT = 4

    private const val MAX_ROUNDS = "max_tool_rounds"
    private const val IMAGE_GEN_MODEL = "image_gen_model"
    private const val TITLE_MODEL = "title_model"
    private const val MESSAGE_TIMESTAMPS = "message_timestamps"

    fun loadMaxRounds(): Int =
        clampMaxRounds(loadRoot().optInt(MAX_ROUNDS, MAX_ROUNDS_DEFAULT))

    fun saveMaxRounds(value: Int) {
        val root = loadRoot()
        root.put(MAX_ROUNDS, clampMaxRounds(value))
        AppPaths.toolsFile.writeText(root.toString())
    }

    fun clampMaxRounds(value: Int): Int = value.coerceIn(MAX_ROUNDS_MIN, MAX_ROUNDS_MAX)

    /** 图片生成工具使用的默认模型 id；空串表示未配置。 */
    fun loadImageGenModel(): String =
        loadRoot().optString(IMAGE_GEN_MODEL)

    /** 标题生成使用的模型 id；空串表示跟随当前对话模型。 */
    fun loadTitleModel(): String =
        loadRoot().optString(TITLE_MODEL)

    fun saveTitleModel(model: String) {
        val root = loadRoot()
        root.put(TITLE_MODEL, model)
        AppPaths.toolsFile.writeText(root.toString())
    }

    fun saveImageGenModel(model: String) {
        val root = loadRoot()
        root.put(IMAGE_GEN_MODEL, model)
        AppPaths.toolsFile.writeText(root.toString())
    }

    /** 是否在发给模型的用户消息前加上发送时间。默认开启，保持现有行为。 */
    fun loadMessageTimestamps(): Boolean =
        loadRoot().optBoolean(MESSAGE_TIMESTAMPS, true)

    fun saveMessageTimestamps(enabled: Boolean) {
        val root = loadRoot()
        root.put(MESSAGE_TIMESTAMPS, enabled)
        AppPaths.toolsFile.writeText(root.toString())
    }

    private fun loadRoot(): JSONObject {
        val file = AppPaths.toolsFile
        if (!file.exists()) return JSONObject()
        return runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
    }
}
