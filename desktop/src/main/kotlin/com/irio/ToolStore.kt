package com.irio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

object ToolStore {
    const val MAX_ROUNDS_MIN = 1
    const val MAX_ROUNDS_MAX = 25
    const val MAX_ROUNDS_DEFAULT = 4
    const val COMPRESSION_PERCENT_MIN = 1
    const val COMPRESSION_PERCENT_MAX = 100
    const val COMPRESSION_TRIGGER_DEFAULT = 90
    const val COMPRESSION_TARGET_DEFAULT = 10

    private const val MAX_ROUNDS = "max_tool_rounds"
    private const val IMAGE_GEN_MODEL = "image_gen_model"
    private const val TITLE_MODEL = "title_model"
    private const val MESSAGE_TIMESTAMPS = "message_timestamps"
    private const val CONTEXT_COMPRESSION = "context_compression"
    private const val COMPRESSION_TRIGGER = "context_compression_trigger"
    private const val COMPRESSION_TARGET = "context_compression_target"
    private const val COMPRESSION_MODEL = "context_compression_model"

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

    /** 是否在发给模型的用户消息前加上发送时间。默认关闭。 */
    fun loadMessageTimestamps(): Boolean =
        loadRoot().optBoolean(MESSAGE_TIMESTAMPS, false)

    fun saveMessageTimestamps(enabled: Boolean) {
        val root = loadRoot()
        root.put(MESSAGE_TIMESTAMPS, enabled)
        AppPaths.toolsFile.writeText(root.toString())
    }

    /**
     * 是否压缩对话上下文。默认关闭。
     * 聊天页直接读这个状态：设置页盖在上面，保存后菜单不用等重组别的东西。
     */
    var contextCompressionEnabled by mutableStateOf(loadContextCompression())
        private set

    fun loadContextCompression(): Boolean =
        loadRoot().optBoolean(CONTEXT_COMPRESSION, false)

    fun saveContextCompression(enabled: Boolean) {
        val root = loadRoot()
        root.put(CONTEXT_COMPRESSION, enabled)
        AppPaths.toolsFile.writeText(root.toString())
        contextCompressionEnabled = enabled
    }

    /** 上下文占用达到该百分比时触发压缩。缺键为 90。 */
    fun loadCompressionTrigger(): Int =
        clampCompressionPercent(loadRoot().optInt(COMPRESSION_TRIGGER, COMPRESSION_TRIGGER_DEFAULT))

    fun saveCompressionTrigger(value: Int) {
        val root = loadRoot()
        root.put(COMPRESSION_TRIGGER, clampCompressionPercent(value))
        AppPaths.toolsFile.writeText(root.toString())
    }

    /** 压缩后希望保留的上下文百分比。缺键为 10。 */
    fun loadCompressionTarget(): Int =
        clampCompressionPercent(loadRoot().optInt(COMPRESSION_TARGET, COMPRESSION_TARGET_DEFAULT))

    fun saveCompressionTarget(value: Int) {
        val root = loadRoot()
        root.put(COMPRESSION_TARGET, clampCompressionPercent(value))
        AppPaths.toolsFile.writeText(root.toString())
    }

    /** 压缩使用的模型 id。空串表示跟随当前对话模型。 */
    fun loadCompressionModel(): String =
        loadRoot().optString(COMPRESSION_MODEL)

    fun saveCompressionModel(model: String) {
        val root = loadRoot()
        root.put(COMPRESSION_MODEL, model)
        AppPaths.toolsFile.writeText(root.toString())
    }

    fun clampCompressionPercent(value: Int): Int =
        value.coerceIn(COMPRESSION_PERCENT_MIN, COMPRESSION_PERCENT_MAX)

    private fun loadRoot(): JSONObject {
        val file = AppPaths.toolsFile
        if (!file.exists()) return JSONObject()
        return runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
    }
}
