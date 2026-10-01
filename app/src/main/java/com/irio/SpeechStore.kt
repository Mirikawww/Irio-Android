package com.irio

import android.content.Context

object SpeechStore {
    private const val PREFS = "speech_settings"
    private const val AUTO_READ = "auto_read"
    private const val VOICE_ID = "voice_id"

    fun loadAutoRead(context: Context): Boolean =
        preferences(context).getBoolean(AUTO_READ, false)

    fun saveAutoRead(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(AUTO_READ, enabled).apply()
    }

    /** 空串表示系统默认音色。 */
    fun loadVoiceId(context: Context): String =
        preferences(context).getString(VOICE_ID, "").orEmpty()

    fun saveVoiceId(context: Context, id: String) {
        preferences(context).edit().putString(VOICE_ID, id).apply()
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
