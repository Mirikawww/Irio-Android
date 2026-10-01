package com.irio

import org.json.JSONObject

object SpeechStore {
    private const val AUTO_READ = "auto_read"
    private const val VOICE_ID = "voice_id"

    fun loadAutoRead(): Boolean = loadRoot().optBoolean(AUTO_READ, false)

    fun saveAutoRead(enabled: Boolean) {
        val root = loadRoot()
        root.put(AUTO_READ, enabled)
        AppPaths.speechFile.writeText(root.toString())
    }

    /** 空串表示系统默认音色。 */
    fun loadVoiceId(): String = loadRoot().optString(VOICE_ID)

    fun saveVoiceId(id: String) {
        val root = loadRoot()
        root.put(VOICE_ID, id)
        AppPaths.speechFile.writeText(root.toString())
    }

    private fun loadRoot(): JSONObject {
        val file = AppPaths.speechFile
        if (!file.exists()) return JSONObject()
        return runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
    }
}
