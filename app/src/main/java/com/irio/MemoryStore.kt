package com.irio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 记忆为单个整块文本，注入时作为一整段长期用户信息。
 * 旧版本按条目（items 数组）存储；首次读取时自动把已有条目按行合并迁移为 content。
 */
object MemoryStore {

    const val MAX_CHARS = 5000

    private const val FILE = "memory.json"

    fun loadEnabled(context: Context): Boolean = loadRoot(context).optBoolean("enabled", true)

    fun saveEnabled(context: Context, enabled: Boolean) {
        val root = loadRoot(context)
        root.put("enabled", enabled)
        writeRoot(context, root)
    }

    fun loadContent(context: Context): String {
        val root = loadRoot(context)
        val content = root.optString("content")
        if (content.isNotEmpty()) return content
        // 迁移旧条目：每条一行合并
        val legacy = root.optJSONArray("items") ?: JSONArray()
        val merged = buildString {
            for (index in 0 until legacy.length()) {
                val item = legacy.optJSONObject(index) ?: continue
                val line = item.optString("content").trim()
                if (line.isEmpty()) continue
                if (isNotEmpty()) append('\n')
                append(line)
            }
        }
        if (merged.isNotEmpty()) {
            root.put("content", merged)
            root.remove("items")
            writeRoot(context, root)
        }
        return merged
    }

    fun saveContent(context: Context, text: String) {
        val root = loadRoot(context)
        root.put("content", text.take(MAX_CHARS))
        root.remove("items")
        writeRoot(context, root)
    }

    /** 在现有记忆末尾追加一段；空内容忽略。返回写入后的全文。 */
    fun appendContent(context: Context, addition: String): String {
        val trimmed = addition.trim()
        if (trimmed.isEmpty()) return loadContent(context)
        val current = loadContent(context).trim()
        val merged = if (current.isEmpty()) trimmed else "$current\n$trimmed"
        saveContent(context, merged)
        return loadContent(context)
    }

    fun systemPrompt(context: Context): String? {
        if (!loadEnabled(context)) return null
        val content = loadContent(context).trim()
        if (content.isEmpty()) return null
        return buildString {
            append("以下是你应当记住的关于用户的长期信息。请在对话中自然地参考它们，不要主动把整份记忆复述给用户。")
            append('\n').append(content)
        }
    }

    private fun loadRoot(context: Context): JSONObject {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return JSONObject().put("enabled", true)
        return runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject().put("enabled", true))
    }

    private fun writeRoot(context: Context, root: JSONObject) {
        if (!root.has("enabled")) root.put("enabled", true)
        File(context.filesDir, FILE).writeText(root.toString())
    }
}
