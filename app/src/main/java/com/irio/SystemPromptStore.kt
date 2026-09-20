package com.irio

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 用户自定义系统提示词：全局一条，注入请求体原生的 system 字段
 * （chat/completions 的 system 消息、responses 的 instructions、anthropic 的 system），
 * 与「记忆」无关——记忆是用户资料的自然语言描述，各自独立生效。
 */
object SystemPromptStore {

    const val MAX_CHARS = 4000

    private const val FILE = "system_prompt.json"

    fun load(context: Context): String = readRoot(context).optString("content")

    fun save(context: Context, text: String) {
        writeRoot(context, JSONObject().put("content", text.take(MAX_CHARS)))
    }

    private fun readRoot(context: Context): JSONObject {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return JSONObject()
        return runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
    }

    private fun writeRoot(context: Context, root: JSONObject) {
        File(context.filesDir, FILE).writeText(root.toString())
    }
}
