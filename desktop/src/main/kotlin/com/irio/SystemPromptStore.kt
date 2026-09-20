package com.irio

import org.json.JSONObject
import java.io.File

/** User-configured system prompt shared by all provider protocols. */
object SystemPromptStore {
    const val MAX_CHARS = 4000
    private const val FILE = "system_prompt.json"

    fun load(): String = readRoot().optString("content")

    fun save(text: String) {
        AppPaths.systemPromptFile.writeText(JSONObject().put("content", text.take(MAX_CHARS)).toString())
    }

    private fun readRoot(): JSONObject {
        val file = AppPaths.systemPromptFile
        if (!file.exists()) return JSONObject()
        return runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
    }
}
