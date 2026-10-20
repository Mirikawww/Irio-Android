package com.irio

import android.content.Context
import kotlin.math.roundToInt

object SpeechStore {
    private const val PREFS = "speech_settings"
    private const val AUTO_READ = "auto_read"
    private const val VOICE_ID = "voice_id"
    private const val SPEECH_RATE = "speech_rate"
    const val DEFAULT_SPEECH_RATE = 1f
    const val MIN_SPEECH_RATE = 0.5f
    const val MAX_SPEECH_RATE = 3f

    internal fun normalizeSpeechRate(value: Float): Float =
        if (!value.isFinite()) DEFAULT_SPEECH_RATE
        else (value.coerceIn(MIN_SPEECH_RATE, MAX_SPEECH_RATE) * 10).roundToInt() / 10f

    fun loadSpeechRate(context: Context): Float =
        normalizeSpeechRate(preferences(context).getFloat(SPEECH_RATE, DEFAULT_SPEECH_RATE))

    fun saveSpeechRate(context: Context, value: Float) {
        preferences(context).edit().putFloat(SPEECH_RATE, normalizeSpeechRate(value)).apply()
    }

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
