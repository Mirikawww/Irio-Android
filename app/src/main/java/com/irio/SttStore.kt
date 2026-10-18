package com.irio

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class SttProviderKind(val label: String) {
    GROQ("Groq"),
    DEEPGRAM("Deepgram"),
    GLADIA("Gladia"),
    ASSEMBLYAI("AssemblyAI"),
}

data class SttProviderConfig(
    val id: String,
    val kind: SttProviderKind,
    val name: String,
    val apiKey: String,
    val model: String,
    val enabled: Boolean = true,
    val language: String = "en",
)

object SttStore {
    private const val PREFS = "stt_settings"
    private const val PROVIDERS = "providers"
    private const val ACTIVE_ID = "active_id"
    private const val KEY_ALIAS = "irio_stt_key"

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadProviders(context: Context): List<SttProviderConfig> {
        val raw = preferences(context).getString(PROVIDERS, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val key = decrypt(item.optString("encrypted_key")) ?: continue
                    val kind = runCatching { SttProviderKind.valueOf(item.optString("kind")) }
                        .getOrDefault(SttProviderKind.GROQ)
                    add(
                        SttProviderConfig(
                            id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                            kind = kind,
                            name = item.optString("name").ifBlank { kind.label },
                            apiKey = key,
                            model = item.optString("model").ifBlank { defaultModel(kind) },
                            enabled = item.optBoolean("enabled", true),
                            language = item.optString("language").ifBlank { "en" },
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun loadActiveProvider(context: Context): SttProviderConfig? {
        val providers = loadProviders(context)
        val activeId = preferences(context).getString(ACTIVE_ID, null)
        return providers.firstOrNull { it.id == activeId && it.enabled && it.apiKey.isNotBlank() }
            ?: providers.firstOrNull { it.enabled && it.apiKey.isNotBlank() }
    }

    fun saveProvider(context: Context, config: SttProviderConfig) {
        val normalized = config.copy(apiKey = normalizeSttApiKey(config.apiKey))
        val providers = loadProviders(context).toMutableList()
        val index = providers.indexOfFirst { it.id == normalized.id }
        if (index >= 0) providers[index] = normalized else providers += normalized
        val active = preferences(context).getString(ACTIVE_ID, null)
            ?.takeIf { id -> providers.any { it.id == id } }
            ?: normalized.id
        persist(context, providers, active)
    }

    fun saveProviderEnabled(context: Context, id: String, enabled: Boolean) {
        val providers = loadProviders(context).map {
            if (it.id == id) it.copy(enabled = enabled) else it
        }
        val active = loadActiveProvider(context)?.id
            ?: providers.firstOrNull { it.enabled }?.id.orEmpty()
        persist(context, providers, active)
    }

    fun saveActiveProvider(context: Context, id: String) {
        if (loadProviders(context).any { it.id == id && it.enabled }) {
            preferences(context).edit().putString(ACTIVE_ID, id).apply()
        }
    }

    fun deleteProvider(context: Context, id: String) {
        val remaining = loadProviders(context).filterNot { it.id == id }
        val active = remaining.firstOrNull { it.enabled }?.id.orEmpty()
        persist(context, remaining, active)
    }

    fun snapshot(context: Context): JSONObject = JSONObject().apply {
        put("providers", JSONArray().apply {
            loadProviders(context).forEach { provider ->
                put(
                    JSONObject().apply {
                        put("id", provider.id)
                        put("kind", provider.kind.name)
                        put("name", provider.name)
                        put("apiKey", provider.apiKey)
                        put("model", provider.model)
                        put("enabled", provider.enabled)
                        put("language", provider.language)
                    },
                )
            }
        })
        put("activeId", preferences(context).getString(ACTIVE_ID, "").orEmpty())
    }

    fun applySnapshot(context: Context, json: JSONObject, overwrite: Boolean) {
        val incoming = parseSnapshot(json)
        if (incoming.isEmpty() && json.optJSONArray("providers")?.length() ?: 0 > 0) return
        if (overwrite) {
            persist(context, incoming, json.optString("activeId"))
        } else {
            val local = loadProviders(context).toMutableList()
            incoming.forEach { provider ->
                if (local.none { it.id == provider.id }) local += provider
            }
            persist(context, local, preferences(context).getString(ACTIVE_ID, "").orEmpty())
        }
    }

    private fun parseSnapshot(json: JSONObject): List<SttProviderConfig> {
        val array = json.optJSONArray("providers") ?: JSONArray()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val apiKey = normalizeSttApiKey(item.optString("apiKey"))
                if (apiKey.isBlank()) continue
                val kind = runCatching { SttProviderKind.valueOf(item.optString("kind")) }
                    .getOrDefault(SttProviderKind.GROQ)
                add(
                    SttProviderConfig(
                        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                        kind = kind,
                        name = item.optString("name").ifBlank { kind.label },
                        apiKey = apiKey,
                        model = item.optString("model").ifBlank { defaultModel(kind) },
                        enabled = item.optBoolean("enabled", true),
                        language = item.optString("language").ifBlank { "en" },
                    ),
                )
            }
        }
    }

    private fun persist(context: Context, providers: List<SttProviderConfig>, activeId: String) {
        val array = JSONArray().apply {
            providers.forEach { provider ->
                put(
                    JSONObject().apply {
                        put("id", provider.id)
                        put("kind", provider.kind.name)
                        put("name", provider.name)
                        put("encrypted_key", encrypt(provider.apiKey))
                        put("model", provider.model)
                        put("enabled", provider.enabled)
                        put("language", provider.language)
                    },
                )
            }
        }
        preferences(context).edit()
            .putString(PROVIDERS, array.toString())
            .putString(ACTIVE_ID, activeId)
            .apply()
    }

    fun models(kind: SttProviderKind): List<String> = when (kind) {
        SttProviderKind.GROQ -> listOf("whisper-large-v3-turbo", "whisper-large-v3")
        SttProviderKind.DEEPGRAM -> listOf(
            "nova-3", "nova-3-medical", "nova-3-pharma",
            "nova-2", "nova-2-meeting", "nova-2-phonecall", "nova-2-finance",
            "nova-2-conversationalai", "nova-2-voicemail", "nova-2-video",
            "nova-2-medical", "nova-2-drivethru", "nova-2-automotive", "nova-2-atc",
            "nova", "nova-phonecall", "nova-medical",
            "enhanced", "enhanced-meeting", "enhanced-phonecall", "enhanced-finance",
            "base", "base-meeting", "base-phonecall", "base-finance",
            "base-conversationalai", "base-voicemail", "base-video",
            "whisper-tiny", "whisper-base", "whisper-small", "whisper-medium", "whisper-large",
        )
        SttProviderKind.GLADIA -> listOf("solaria-1", "solaria-3")
        SttProviderKind.ASSEMBLYAI -> listOf("universal-3-6-pro", "universal-3-5-pro", "universal-2")
    }

    fun defaultModel(kind: SttProviderKind): String = models(kind).first()

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String? {
        if (value.isBlank()) return null
        return runCatching {
            val parts = value.split(':', limit = 2)
            require(parts.size == 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(128, Base64.decode(parts[0], Base64.DEFAULT)),
            )
            String(cipher.doFinal(Base64.decode(parts[1], Base64.DEFAULT)), Charsets.UTF_8)
        }.getOrNull()
    }
}

internal fun normalizeSttApiKey(value: String): String {
    val trimmed = value.trim()
    return if (trimmed.startsWith("Bearer ", ignoreCase = true)) {
        trimmed.substring(7).trim()
    } else {
        trimmed
    }
}
