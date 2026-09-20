package com.irio

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

    fun loadEnabled(): Boolean = loadRoot().optBoolean("enabled", true)

    fun saveEnabled(enabled: Boolean) {
        val root = loadRoot()
        root.put("enabled", enabled)
        writeRoot(root)
    }

    fun loadContent(): String {
        val root = loadRoot()
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
            writeRoot(root)
        }
        return merged
    }

    fun saveContent(text: String) {
        val root = loadRoot()
        root.put("content", text.take(MAX_CHARS))
        root.remove("items")
        writeRoot(root)
    }

    /** 在现有记忆末尾追加一段；空内容忽略。返回写入后的全文。 */
    fun appendContent(addition: String): String {
        val trimmed = addition.trim()
        if (trimmed.isEmpty()) return loadContent()
        val current = loadContent().trim()
        val merged = if (current.isEmpty()) trimmed else "$current\n$trimmed"
        saveContent(merged)
        return loadContent()
    }

    fun systemPrompt(): String? {
        if (!loadEnabled()) return null
        val content = loadContent().trim()
        if (content.isEmpty()) return null
        return buildString {
            append("以下是你应当记住的关于用户的长期信息。请在对话中自然地参考它们，不要主动把整份记忆复述给用户。")
            append('\n').append(content)
        }
    }

    private fun loadRoot(): JSONObject {
        val file = AppPaths.memoryFile
        if (!file.exists()) return JSONObject().put("enabled", true)
        return runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject().put("enabled", true))
    }

    private fun writeRoot(root: JSONObject) {
        if (!root.has("enabled")) root.put("enabled", true)
        AppPaths.memoryFile.writeText(root.toString())
    }
}
