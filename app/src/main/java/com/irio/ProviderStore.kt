package com.irio

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.HttpURLConnection
import java.net.URL
import java.io.File
import java.security.KeyStore
import java.io.DataOutputStream
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class ProviderProtocol(val label: String, val defaultEndpoint: String) {
    CHAT_COMPLETIONS("OpenAI Chat Completions", "https://api.openai.com/v1"),
    RESPONSES("OpenAI Responses", "https://api.openai.com/v1"),
    ANTHROPIC("Anthropic Messages", "https://api.anthropic.com"),
    GEMINI("Google Gemini generateContent API", "https://generativelanguage.googleapis.com/v1beta"),
    GEMINI_INTERACTIONS("Google Gemini Interactions API", "https://generativelanguage.googleapis.com/v1beta"),
}

internal val ProviderProtocol.isGoogleGemini: Boolean
    get() = this == ProviderProtocol.GEMINI || this == ProviderProtocol.GEMINI_INTERACTIONS

enum class ReasoningEffort(val label: String, val apiValue: String?) {
    OFF("关闭", "none"),
    AUTO("自动", null), // 仅用于协议内部默认处理，不能作为用户设置保存。
    MINIMAL("极低", "minimal"),
    LOW("低", "low"),
    MEDIUM("中", "medium"),
    HIGH("高", "high"),
    XHIGH("极高", "xhigh"),
    MAX("超高", "max"),
    ULTRA("极致", "ultra"),
}

data class ProviderConfig(
    val id: String,
    val name: String,
    val endpointUrl: String,
    val apiKey: String,
    val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS,
    val models: List<String> = emptyList(),
    val disabledModels: Set<String> = emptySet(),
    val enabled: Boolean = true,
    val selectedModel: String = "",
    val customHeaders: List<ProviderHeader> = emptyList(),
)

data class ModelFetchResult(
    val models: List<String> = emptyList(),
    val error: String? = null,
)

/** 提供商详情里关掉的模型不进任何模型菜单。缺省集合表示全部开启。 */
fun ProviderConfig.enabledModels(): List<String> = models.filter { it !in disabledModels }

/** 更新列表不代表选择模型；仅唯一且开启的当前提供商允许补选被删除的模型。 */
internal fun selectedModelAfterProviderSave(
    providers: List<ProviderConfig>,
    activeProviderId: String,
    config: ProviderConfig,
): String {
    if (config.selectedModel.isNotBlank() && config.selectedModel in config.models) return config.selectedModel
    val previous = providers.firstOrNull { it.id == config.id }
    val initialSelection = previous == null && config.enabled
    val removedActiveSelection = providers.size == 1 && config.enabled && activeProviderId == config.id &&
        previous != null && previous.selectedModel.isNotBlank() &&
        config.selectedModel == previous.selectedModel && previous.selectedModel in previous.models &&
        previous.selectedModel !in config.models
    return if (initialSelection || removedActiveSelection) config.enabledModels().firstOrNull().orEmpty() else ""
}

enum class ModelDiffKind { Added, Unchanged, Removed }

data class ModelDiffEntry(
    val model: String,
    val kind: ModelDiffKind,
)

/** 一次刷新相对刷新前列表的差。详情页只放在内存里，离开页面即丢。 */
data class ModelRefreshDiff(
    val added: Set<String> = emptySet(),
    val removed: List<String> = emptyList(),
)

fun modelRefreshDiff(previous: List<String>, next: List<String>): ModelRefreshDiff {
    val previousSet = previous.toSet()
    val nextSet = next.toSet()
    return ModelRefreshDiff(
        added = next.filterTo(linkedSetOf()) { it !in previousSet },
        removed = previous.filter { it !in nextSet },
    )
}

/** 新增保持拉取顺序并排到最前，未变的留在中间，消失的保持原顺序排到最后。 */
fun modelDiffRows(
    current: List<String>,
    diff: ModelRefreshDiff,
    showRemoved: Boolean = true,
): List<ModelDiffEntry> {
    val added = ArrayList<ModelDiffEntry>(diff.added.size)
    val unchanged = ArrayList<ModelDiffEntry>(current.size)
    for (model in current) {
        if (model in diff.added) added += ModelDiffEntry(model, ModelDiffKind.Added)
        else unchanged += ModelDiffEntry(model, ModelDiffKind.Unchanged)
    }
    return added + unchanged + if (showRemoved) {
        diff.removed.map { ModelDiffEntry(it, ModelDiffKind.Removed) }
    } else {
        emptyList()
    }
}

object ProviderStore {
    private const val PREFS = "provider_settings"
    private const val PROVIDERS = "providers"
    private const val ACTIVE_ID = "active_id"
    private const val LAST_CHAT_MODEL = "last_chat_model"
    private const val REASONING_EFFORT = "reasoning_effort"
    private const val FAST_MODE = "fast_mode"
    private const val MERGE_MODELS = "merge_models"
    private const val TEX_SCALE = "tex_scale"
    private const val MODELS_DEV_CATALOG = "models_dev_catalog"
    private const val MODELS_DEV_REASONING = "models_dev_reasoning"
    private const val KEY_ALIAS = "irio_provider_key"

    private val listeners = mutableListOf<() -> Unit>()

    fun addChangeListener(listener: () -> Unit) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun removeChangeListener(listener: () -> Unit) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    private fun notifyChanged() {
        val snapshot = synchronized(listeners) { listeners.toList() }
        val dispatch = { snapshot.forEach { it() } }
        val main = android.os.Looper.getMainLooper()
        if (android.os.Looper.myLooper() == main) dispatch()
        else android.os.Handler(main).post(dispatch)
    }

    private const val LEGACY_NAME = "name"
    private const val LEGACY_ENDPOINT = "endpoint"
    private const val LEGACY_ENCRYPTED_KEY = "encrypted_key"
    private const val LEGACY_MODELS = "models"
    private const val LEGACY_SELECTED_MODEL = "selected_model"

    fun preferences(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadProviders(context: Context): List<ProviderConfig> {
        migrateLegacy(context)
        val raw = preferences(context).getString(PROVIDERS, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val apiKey = decrypt(item.optString("encrypted_key")) ?: continue
                    val protocol = runCatching {
                        ProviderProtocol.valueOf(item.optString("protocol"))
                    }.getOrDefault(ProviderProtocol.CHAT_COMPLETIONS)
                    val models = stringList(item.optJSONArray("models"))
                    val disabled = stringList(item.optJSONArray("disabled_models")).filter { it in models }.toSet()
                    val selected = item.optString("selected_model").takeIf { it in models }.orEmpty()
                    val headers = runCatching {
                        if (item.has("encrypted_headers")) {
                            val rawHeaders = requireNotNull(decrypt(item.getString("encrypted_headers")))
                            providerHeadersFromJson(JSONArray(rawHeaders))
                        } else emptyList()
                    }.getOrNull() ?: continue
                    add(
                        ProviderConfig(
                            id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                            name = item.optString("name"),
                            endpointUrl = item.optString("endpoint"),
                            apiKey = apiKey,
                            customHeaders = headers,
                            protocol = protocol,
                            models = models,
                            disabledModels = disabled,
                            enabled = item.optBoolean("enabled", true),
                            selectedModel = selected,
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun loadProvider(context: Context, id: String? = null): ProviderConfig? {
        val providers = loadProviders(context)
        val targetId = id ?: loadActiveProviderId(context)
        return providers.firstOrNull { it.id == targetId } ?: providers.firstOrNull()
    }

    fun loadActiveProviderId(context: Context): String {
        migrateLegacy(context)
        val ids = loadProviders(context).map { it.id }
        val saved = preferences(context).getString(ACTIVE_ID, null)
        return saved?.takeIf { it in ids } ?: ids.firstOrNull().orEmpty()
    }

    fun saveActiveProviderId(context: Context, id: String) {
        preferences(context).edit().putString(ACTIVE_ID, id).apply()
    }

    /** 最近一次选中的「非图片」对话模型 id；标题生成在当前模型是图片模型时用它。 */
    fun loadLastChatModel(context: Context): String =
        preferences(context).getString(LAST_CHAT_MODEL, "").orEmpty()

    fun saveLastChatModel(context: Context, model: String) {
        preferences(context).edit().putString(LAST_CHAT_MODEL, model).apply()
    }

    fun saveProvider(context: Context, config: ProviderConfig) {
        val providers = loadProviders(context).toMutableList()
        val activeId = loadActiveProviderId(context)
        val index = providers.indexOfFirst { it.id == config.id }
        val selected = selectedModelAfterProviderSave(providers, activeId, config)
        val disabled = config.disabledModels.intersect(config.models.toSet())
        val stored = config.copy(selectedModel = selected, disabledModels = disabled)
        if (index >= 0) providers[index] = stored else providers.add(stored)
        persist(context, providers, activeId)
    }

    fun saveDisabledModels(context: Context, providerId: String, disabled: Set<String>) {
        val prefs = preferences(context)
        val array = runCatching { JSONArray(prefs.getString(PROVIDERS, "[]") ?: "[]") }.getOrElse { return }
        var found = false
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            if (item.optString("id") != providerId) continue
            val known = stringList(item.optJSONArray("models")).toSet()
            item.put("disabled_models", JSONArray().apply { disabled.filter { it in known }.forEach(::put) })
            found = true
            break
        }
        if (!found) return
        prefs.edit().putString(PROVIDERS, array.toString()).apply()
        notifyChanged()
    }

    fun saveProviderEnabled(context: Context, providerId: String, enabled: Boolean) {
        val prefs = preferences(context)
        val array = runCatching { JSONArray(prefs.getString(PROVIDERS, "[]") ?: "[]") }.getOrElse { return }
        var found = false
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            if (item.optString("id") != providerId) continue
            item.put("enabled", enabled)
            found = true
            break
        }
        if (!found) return
        prefs.edit().putString(PROVIDERS, array.toString()).apply()
        notifyChanged()
    }

    fun deleteProvider(context: Context, id: String) {
        val remaining = loadProviders(context).filterNot { it.id == id }
        val active = loadActiveProviderId(context).takeIf { it != id && remaining.any { provider -> provider.id == it } }
            ?: remaining.firstOrNull()?.id.orEmpty()
        persist(context, remaining, active)
    }

    fun saveSelectedModel(context: Context, model: String, providerId: String? = null) {
        val provider = loadProvider(context, providerId) ?: return
        if (model !in provider.models) return
        saveProvider(context, provider.copy(selectedModel = model))
    }

    fun loadReasoningEffort(context: Context): ReasoningEffort {
        val saved = preferences(context).getString(REASONING_EFFORT, null)
        return saved?.let { runCatching { ReasoningEffort.valueOf(it) }.getOrNull() }
            ?.takeUnless { it == ReasoningEffort.AUTO }
            ?: ReasoningEffort.OFF
    }

    fun saveReasoningEffort(context: Context, effort: ReasoningEffort) {
        val selected = effort.takeUnless { it == ReasoningEffort.AUTO } ?: ReasoningEffort.OFF
        preferences(context).edit().putString(REASONING_EFFORT, selected.name).apply()
        notifyChanged()
    }

    fun loadFastMode(context: Context): Boolean =
        preferences(context).getBoolean(FAST_MODE, false)

    fun saveFastMode(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(FAST_MODE, enabled).apply()
        notifyChanged()
    }

    fun loadMergeModels(context: Context): Boolean =
        preferences(context).getBoolean(MERGE_MODELS, true)

    fun saveMergeModels(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(MERGE_MODELS, enabled).apply()
        notifyChanged()
    }

    fun loadTexScale(context: Context): Float =
        preferences(context).getFloat(TEX_SCALE, 1.0f).coerceIn(0.6f, 1.6f)

    fun saveTexScale(context: Context, scale: Float) {
        preferences(context).edit().putFloat(TEX_SCALE, scale.coerceIn(0.6f, 1.6f)).apply()
        notifyChanged()
    }

    fun loadModelsDevCatalog(context: Context): Boolean =
        preferences(context).getBoolean(MODELS_DEV_CATALOG, true)

    fun saveModelsDevCatalog(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(MODELS_DEV_CATALOG, enabled).apply()
        notifyChanged()
    }

    /** 模型列表跟随 OpenRouter 思考数据，默认关闭。保留旧偏好键以兼容备份。 */
    fun loadModelsDevReasoning(context: Context): Boolean =
        preferences(context).getBoolean(MODELS_DEV_REASONING, false)

    fun saveModelsDevReasoning(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(MODELS_DEV_REASONING, enabled).apply()
        notifyChanged()
    }

    private fun persist(context: Context, providers: List<ProviderConfig>, activeId: String) {
        val array = JSONArray().apply {
            providers.forEach { provider ->
                put(
                    JSONObject().apply {
                        put("id", provider.id)
                        put("name", provider.name)
                        put("endpoint", provider.endpointUrl)
                        put("encrypted_key", encrypt(provider.apiKey))
                        put("encrypted_headers", encrypt(providerHeadersJson(provider.customHeaders).toString()))
                        put("protocol", provider.protocol.name)
                        put("models", JSONArray().apply { provider.models.forEach(::put) })
                        put("disabled_models", JSONArray().apply { provider.disabledModels.forEach(::put) })
                        put("enabled", provider.enabled)
                        put("selected_model", provider.selectedModel)
                    }
                )
            }
        }
        preferences(context).edit()
            .putString(PROVIDERS, array.toString())
            .putString(ACTIVE_ID, activeId)
            .remove(LEGACY_NAME)
            .remove(LEGACY_ENDPOINT)
            .remove(LEGACY_ENCRYPTED_KEY)
            .remove(LEGACY_MODELS)
            .remove(LEGACY_SELECTED_MODEL)
            .apply()
        notifyChanged()
    }

    fun snapshot(context: Context): JSONObject {
        return JSONObject().apply {
            put(
                "providers",
                JSONArray().apply {
                    loadProviders(context).forEach { provider ->
                        put(
                            JSONObject().apply {
                                put("id", provider.id)
                                put("name", provider.name)
                                put("endpoint", provider.endpointUrl)
                                put("apiKey", provider.apiKey)
                                put("customHeaders", providerHeadersJson(provider.customHeaders))
                                put("protocol", provider.protocol.name)
                                put("models", JSONArray().apply { provider.models.forEach(::put) })
                                put("disabled_models", JSONArray().apply { provider.disabledModels.forEach(::put) })
                                put("enabled", provider.enabled)
                                put("selected_model", provider.selectedModel)
                            }
                        )
                    }
                }
            )
            put("activeId", loadActiveProviderId(context))
            put("reasoning", loadReasoningEffort(context).name)
            put("fastMode", loadFastMode(context))
        }
    }

    fun applySnapshot(context: Context, json: JSONObject, overwrite: Boolean) {
        val incoming = parseSnapshotProviders(json)
        if (incoming.isEmpty() && json.optJSONArray("providers")?.length() ?: 0 > 0) return
        if (overwrite) {
            persist(context, incoming, json.optString("activeId"))
            json.optString("reasoning").takeIf { it.isNotBlank() }?.let { name ->
                runCatching { ReasoningEffort.valueOf(name) }.getOrNull()?.let { saveReasoningEffort(context, it) }
            }
            if (json.has("fastMode")) saveFastMode(context, json.optBoolean("fastMode"))
        } else {
            val local = loadProviders(context).toMutableList()
            incoming.forEach { remote ->
                if (local.none { it.id == remote.id }) local.add(remote)
            }
            persist(context, local, loadActiveProviderId(context))
        }
    }

    private fun parseSnapshotProviders(json: JSONObject): List<ProviderConfig> {
        val array = json.optJSONArray("providers") ?: JSONArray()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val apiKey = item.optString("apiKey")
                val protocol = runCatching {
                    ProviderProtocol.valueOf(item.optString("protocol"))
                }.getOrDefault(ProviderProtocol.CHAT_COMPLETIONS)
                val models = stringList(item.optJSONArray("models"))
                val disabled = stringList(item.optJSONArray("disabled_models")).filter { it in models }.toSet()
                val selected = item.optString("selected_model").takeIf { it in models }.orEmpty()
                val headers = runCatching {
                    providerHeadersFromJson(if (item.has("customHeaders")) item.getJSONArray("customHeaders") else null)
                }.getOrNull() ?: continue
                add(
                    ProviderConfig(
                        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                        name = item.optString("name"),
                        endpointUrl = item.optString("endpoint"),
                        apiKey = apiKey,
                        customHeaders = headers,
                        protocol = protocol,
                        models = models,
                        disabledModels = disabled,
                        enabled = item.optBoolean("enabled", true),
                        selectedModel = selected,
                    )
                )
            }
        }
    }

    private fun migrateLegacy(context: Context) {
        if (legacyMigrated) return
        synchronized(this) {
            if (legacyMigrated) return
            val prefs = preferences(context)
            if (!prefs.getString(PROVIDERS, null).isNullOrBlank() && prefs.getString(PROVIDERS, "[]") != "[]") {
                legacyMigrated = true
                return
            }
            val name = prefs.getString(LEGACY_NAME, null)
            val endpoint = prefs.getString(LEGACY_ENDPOINT, null)
            val encryptedKey = prefs.getString(LEGACY_ENCRYPTED_KEY, null)
            val apiKey = encryptedKey?.let { decrypt(it) }
            if (name != null && endpoint != null && apiKey != null) {
                val models = stringList(JSONArray(prefs.getString(LEGACY_MODELS, "[]") ?: "[]"))
                val selected = prefs.getString(LEGACY_SELECTED_MODEL, null)?.takeIf { it in models }
                    ?: models.firstOrNull().orEmpty()
                persist(
                    context,
                    listOf(
                        ProviderConfig(
                            id = UUID.randomUUID().toString(),
                            name = name,
                            endpointUrl = endpoint,
                            apiKey = apiKey,
                            models = models,
                            selectedModel = selected,
                        )
                    ),
                    activeId = "",
                )
            }
            legacyMigrated = true
        }
    }

    @Volatile
    private var legacyMigrated = false

    private fun stringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optString(index).takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }

    private fun secretKey(): SecretKey {
        cachedSecretKey?.let { return it }
        return synchronized(this) {
            cachedSecretKey?.let { return it }
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val key = (keyStore.getKey(KEY_ALIAS, null) as? SecretKey) ?: run {
                val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                generator.init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
                generator.generateKey()
            }
            cachedSecretKey = key
            key
        }
    }

    @Volatile
    private var cachedSecretKey: SecretKey? = null

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val payload = Base64.encodeToString(cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
        return "$iv:$payload"
    }

    private fun decrypt(value: String): String? = runCatching {
        val (iv, payload) = value.split(':', limit = 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
        )
        String(cipher.doFinal(Base64.decode(payload, Base64.NO_WRAP)))
    }.getOrNull()
}

data class ImageGenResult(
    val attachment: ChatAttachment? = null,
    val revisedPrompt: String = "",
    val error: String? = null,
    val retryable: Boolean = false,
    val text: String = "",
)

object ProviderApi {
    fun fetchModels(config: ProviderConfig): ModelFetchResult {
        if (config.protocol.isGoogleGemini) return fetchGeminiModels(config)
        val connection = runCatching {
            URL(modelsUrl(config.endpointUrl, config.protocol)).openConnection() as HttpURLConnection
        }.getOrElse { return ModelFetchResult(error = tr("端点 URL 无效")) }

        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/json")
            applyAuth(connection, config)
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                ModelFetchResult(error = apiError(body).ifBlank { tr("获取模型失败（HTTP %s）").format(status) })
            } else {
                val models = parseModels(body)
                if (models.isEmpty()) ModelFetchResult(error = tr("端点没有返回可用模型"))
                else ModelFetchResult(models = models)
            }
        } catch (error: Exception) {
            ModelFetchResult(error = error.message ?: tr("无法连接到端点"))
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchGeminiModels(config: ProviderConfig): ModelFetchResult = runCatching {
        val models = linkedSetOf<String>()
        val seenTokens = mutableSetOf<String>()
        var pageToken = ""
        do {
            // Resolve the page token as a query parameter, never as an arbitrary next-page URL.
            val base = (ProviderEndpoints.geminiBase(config.endpointUrl) + "/models").toHttpUrl()
            val pageUrl = base.newBuilder().apply {
                if (pageToken.isNotBlank()) addQueryParameter("pageToken", pageToken)
            }.build()
            val connection = URL(pageUrl.toString()).openConnection() as HttpURLConnection
            val root = try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 15_000
                connection.readTimeout = 20_000
                connection.setRequestProperty("Accept", "application/json")
                applyAuth(connection, config)
                val status = connection.responseCode
                val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (status !in 200..299) return ModelFetchResult(error = apiError(body).ifBlank { tr("获取模型失败（HTTP %s）").format(status) })
                JSONObject(body)
            } finally { connection.disconnect() }
            val items = root.optJSONArray("models") ?: JSONArray()
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val methods = item.optJSONArray("supportedGenerationMethods")
                val supportedMethods = (0 until (methods?.length() ?: 0)).map { methods!!.optString(it) }.toSet()
                val supportsProtocol = if (config.protocol == ProviderProtocol.GEMINI_INTERACTIONS) {
                    methods == null || methods.length() == 0 || supportedMethods.any {
                        it in setOf("generateContent", "interactions", "interactions.create")
                    }
                } else "generateContent" in supportedMethods
                if (!supportsProtocol) continue
                val name = item.optString("name").removePrefix("models/")
                if (name.isNotBlank()) models += name
            }
            pageToken = root.optString("nextPageToken")
            require(pageToken.isBlank() || seenTokens.add(pageToken)) { "Repeated Gemini model page token" }
        } while (pageToken.isNotBlank())
        if (models.isEmpty()) ModelFetchResult(error = tr("端点没有返回可用模型"))
        else ModelFetchResult(models = models.sorted())
    }.getOrElse { ModelFetchResult(error = it.message ?: tr("无法连接到端点")) }

    private fun parseModels(body: String): List<String> {
        val root = JSONObject(body)
        val data = root.optJSONArray("data") ?: root.optJSONArray("models") ?: JSONArray()
        return buildList {
            for (index in 0 until data.length()) {
                val item = data.optJSONObject(index)
                val id = item?.optString("id") ?: data.optString(index)
                id.takeIf(String::isNotBlank)?.let(::add)
            }
        }.distinct().sorted()
    }

    private fun apiError(body: String): String = runCatching {
        val response = JSONObject(body)
        when (val error = response.opt("error")) {
            is JSONObject -> error.optString("message")
            is String -> error
            null -> response.optString("message")
            else -> response.optString("message")
        }
    }.getOrDefault("")

    private fun modelsUrl(endpointUrl: String, protocol: ProviderProtocol): String {
        val clean = endpointUrl.trim().trimEnd('/')
        val base = when {
            clean.endsWith("/chat/completions") -> clean.removeSuffix("/chat/completions")
            clean.endsWith("/responses") -> clean.removeSuffix("/responses")
            clean.endsWith("/messages") -> clean.removeSuffix("/messages")
            clean.endsWith("/models") -> clean.removeSuffix("/models")
            else -> clean
        }.trimEnd('/')
        return if (protocol == ProviderProtocol.ANTHROPIC && !base.endsWith("/v1")) {
            "$base/v1/models"
        } else {
            "$base/models"
        }
    }

    /** 多轮生图上下文：上一轮的 prompt 与生成图（Images API 无状态，需回传参考）。 */
    data class ImageGenContext(
        val previousPrompt: String,
        val previousImages: List<ChatAttachment>,
    )

    /** OpenAI Images API 生成图片；成功返回落盘后的附件。
     *  [context] 非空时优先走 /images/edits 把上轮图回传作参考；端点不支持时回落 generations 并把
     *  上轮 prompt 拼进文本（模型至少能读到历史语义）。 */
    fun generateImage(
        config: ProviderConfig,
        model: String,
        prompt: String,
        context: ImageGenContext? = null,
        cancellation: ChatRequestCancellation? = null,
        options: ImageGenerationOptions? = null,
        streaming: Boolean = ThemeStore.imageGenerationStreaming,
        onProgress: (ImageGenerationProgress) -> Unit = {},
    ): ImageGenResult {
        if (cancellation?.isCancelled() == true) return ImageGenResult(error = tr("图片生成已停止"))
        val imageStream = ImageGenerationStream(onProgress, cancelled = { cancellation?.isCancelled() == true },
            download = { url, revised -> imageFromRemoteUrl(url, revised, config, cancellation) })
        imageStream.start()
        if (config.protocol.isGoogleGemini) return generateImagePlain(config, model, prompt, cancellation, context, options, imageStream, streaming)
        if (context != null && context.previousImages.isNotEmpty()) {
            val edited = generateImageWithEdits(config, model, prompt, context, cancellation, options, imageStream, streaming)
            if (edited != null) return edited
            // edits 不可用（端点 404/405/415 或网络层拒绝）→ 回落 generations，把上轮 prompt 织进文本
            val combined = buildString {
                append(tr("上一次生成请求："))
                append(context.previousPrompt.trim())
                append(tr("。参考图即上一轮的生成结果。本次要求："))
                append(prompt.trim())
            }
            return generateImagePlain(config, model, combined, cancellation, options = options, imageStream = imageStream, streaming = streaming)
        }
        return generateImagePlain(config, model, prompt, cancellation, options = options, imageStream = imageStream, streaming = streaming)
    }

    internal fun imageRequestBody(
        config: ProviderConfig,
        model: String,
        prompt: String,
        context: ImageGenContext? = null,
        options: ImageGenerationOptions? = null,
        streaming: Boolean = ThemeStore.imageGenerationStreaming,
    ): JSONObject {
        val messages = buildList {
            if (context != null && context.previousImages.isNotEmpty()) {
                add(ChatRequestMessage("user", context.previousPrompt))
                add(ChatRequestMessage("assistant", attachments = context.previousImages))
            }
            add(ChatRequestMessage("user", prompt))
        }
        return when (config.protocol) {
            ProviderProtocol.GEMINI_INTERACTIONS -> GeminiInteractionsProtocol.requestBody(model, messages, null).apply {
                if (options == null) put("response_modalities", JSONArray().put("IMAGE"))
                else put("response_format", JSONObject().put("type", "image").put("image_size", options.resolution.label).apply {
                    options.aspectRatio?.let { put("aspect_ratio", it.label) }
                })
            }
            ProviderProtocol.GEMINI -> GeminiProtocol.requestBody(messages, null).put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("TEXT").put("IMAGE")).apply {
                    if (options != null) put("imageConfig", JSONObject().put("imageSize", options.resolution.label).apply {
                        options.aspectRatio?.let { put("aspectRatio", it.label) }
                    })
                })
            else -> JSONObject().put("model", model).put("prompt", prompt).put("n", 1).put("stream", streaming).apply {
                if (streaming) put("partial_images", 3)
                (options?.openAiFields() ?: mapOf("size" to "1024x1024")).forEach { (name, value) -> put(name, value) }
            }
        }
    }

    private fun generateImagePlain(
        config: ProviderConfig,
        model: String,
        prompt: String,
        cancellation: ChatRequestCancellation?,
        context: ImageGenContext? = null,
        options: ImageGenerationOptions? = null,
        imageStream: ImageGenerationStream,
        streaming: Boolean,
    ): ImageGenResult {
        val connection = runCatching {
            val url = if (config.protocol.isGoogleGemini) ProviderEndpoints.chatUrl(config, model, streaming = false)
                else imagesUrl(config.endpointUrl)
            URL(url).openConnection() as HttpURLConnection
        }.getOrElse { return ImageGenResult(error = tr("端点 URL 无效")) }
        cancellation?.attach(connection)

        return try {
            if (cancellation?.isCancelled() == true) return ImageGenResult(error = tr("图片生成已停止"))
            connection.requestMethod = "POST"
            connection.connectTimeout = 30_000
            connection.readTimeout = 300_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", if (config.protocol.isGoogleGemini || !streaming) "application/json" else "text/event-stream, application/json")
            applyAuth(connection, config)
            val requestBody = imageRequestBody(config, model, prompt, context, options, streaming)
            connection.outputStream.use { output ->
                if (cancellation?.isCancelled() == true) return ImageGenResult(error = tr("图片生成已停止"))
                output.write(
                    requestBody.toString().toByteArray(Charsets.UTF_8)
                )
            }
            val status = connection.responseCode
            if (cancellation?.isCancelled() == true) return ImageGenResult(error = tr("图片生成已停止"))
            if (status in 200..299 && !config.protocol.isGoogleGemini) {
                return readImageResponse(connection, config, cancellation, imageStream)
            }
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (cancellation?.isCancelled() == true) return ImageGenResult(error = tr("图片生成已停止"))
            if (status !in 200..299) {
                ImageGenResult(error = apiError(body).ifBlank { tr("图片生成失败（HTTP %s）").format(status) },
                    retryable = retryableHttpStatus(status))
            } else {
                if (config.protocol.isGoogleGemini) {
                    val parsed = if (config.protocol == ProviderProtocol.GEMINI_INTERACTIONS) {
                        GeminiInteractionsProtocol.parseResponse(body)
                    } else GeminiProtocol.parse(body)
                    val image = parsed?.attachments?.firstOrNull { it.isImage }
                    parsed?.attachments?.filter { it != image }?.forEach { File(it.filePath).delete() }
                    if (image != null) ImageGenResult(attachment = image, text = parsed.delta).also {
                        imageStream.completeJson(it, "")
                    }
                    else ImageGenResult(error = parsed?.error ?: tr("图片端点没有返回图片"))
                } else parseImageResponse(body, config, cancellation)
                    ?: ImageGenResult(error = tr("图片端点没有返回图片"))
            }
        } catch (error: Exception) {
            if (cancellation?.isCancelled() == true) ImageGenResult(error = tr("图片生成已停止"))
            else ImageGenResult(error = error.message ?: tr("无法连接到图片端点"), retryable = retryableConnectionFailure(error))
        } finally {
            cancellation?.detach(connection)
            connection.disconnect()
        }
    }

    /** /v1/images/edits multipart：上轮图作 image[] 输入，本轮 prompt 作指令。端点不支持时返回 null。 */
    private fun generateImageWithEdits(
        config: ProviderConfig,
        model: String,
        prompt: String,
        context: ImageGenContext,
        cancellation: ChatRequestCancellation?,
        options: ImageGenerationOptions?,
        imageStream: ImageGenerationStream,
        streaming: Boolean,
    ): ImageGenResult? {
        val connection = runCatching {
            URL(imagesEditsUrl(config.endpointUrl)).openConnection() as HttpURLConnection
        }.getOrNull() ?: return null
        cancellation?.attach(connection)
        val boundary = "irio-${UUID.randomUUID()}"
        var accepted = false
        return try {
            if (cancellation?.isCancelled() == true) return ImageGenResult(error = tr("图片生成已停止"))
            connection.requestMethod = "POST"
            connection.connectTimeout = 30_000
            connection.readTimeout = 300_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.setRequestProperty("Accept", if (streaming) "text/event-stream, application/json" else "application/json")
            applyAuth(connection, config)
            DataOutputStream(connection.outputStream).use { output ->
                fun field(name: String, value: String) {
                    output.writeBytes("--$boundary\r\n")
                    output.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                    output.write(value.toByteArray(Charsets.UTF_8))
                    output.writeBytes("\r\n")
                }
                field("model", model)
                field("prompt", prompt)
                field("n", "1")
                if (streaming) field("partial_images", "3")
                field("stream", streaming.toString())
                (options?.openAiFields() ?: mapOf("size" to "1024x1024")).forEach { (name, value) -> field(name, value) }
                context.previousImages.forEach { image ->
                    val file = File(image.filePath)
                    if (!file.exists()) return@forEach
                    output.writeBytes("--$boundary\r\n")
                    output.writeBytes(
                        "Content-Disposition: form-data; name=\"image[]\"; filename=\"${image.name.ifBlank { "previous.png" }}\"\r\n",
                    )
                    output.writeBytes("Content-Type: ${image.mimeType.ifBlank { "image/png" }}\r\n\r\n")
                    output.write(file.readBytes())
                    output.writeBytes("\r\n")
                }
                output.writeBytes("--$boundary--\r\n")
            }
            val status = connection.responseCode
            if (cancellation?.isCancelled() == true) return ImageGenResult(error = tr("图片生成已停止"))
            if (status in 200..299) {
                accepted = true
                return readImageResponse(connection, config, cancellation, imageStream)
            }
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (cancellation?.isCancelled() == true) return ImageGenResult(error = tr("图片生成已停止"))
            when {
                status in 200..299 -> parseImageResponse(body, config, cancellation)
                    ?: ImageGenResult(error = tr("图片端点没有返回图片"))
                // 端点不支持 edits（路由不存在/方法不允许/类型不收）→ 让调用方回落 generations
                status == 404 || status == 405 || status == 415 -> null
                else -> ImageGenResult(error = apiError(body).ifBlank { tr("图片生成失败（HTTP %s）").format(status) },
                    retryable = retryableHttpStatus(status))
            }
        } catch (error: Exception) {
            if (cancellation?.isCancelled() == true) ImageGenResult(error = tr("图片生成已停止"))
            else if (accepted) ImageGenResult(error = error.message ?: tr("图片生成失败"), retryable = retryableConnectionFailure(error))
            else null
        } finally {
            cancellation?.detach(connection)
            connection.disconnect()
        }
    }

    private fun readImageResponse(
        connection: HttpURLConnection,
        config: ProviderConfig,
        cancellation: ChatRequestCancellation?,
        imageStream: ImageGenerationStream,
    ): ImageGenResult {
        connection.inputStream.bufferedReader().use { reader ->
            // Compatible gateways sometimes ignore stream=true and return the old JSON envelope.
            reader.mark(512)
            var first = reader.read()
            while (first >= 0 && first.toChar().isWhitespace()) first = reader.read()
            reader.reset()
            if (first == '{'.code) {
                val body = reader.readText()
                if (cancellation?.isCancelled() == true) return ImageGenResult(error = tr("图片生成已停止"))
                val result = parseImageResponse(body, config, cancellation)
                    ?: return ImageGenResult(error = tr("图片端点没有返回图片"))
                val size = runCatching { JSONObject(body).optString("size") }.getOrDefault("")
                imageStream.completeJson(result, size)
                return result
            }
            var error: String? = null
            var retryable = false
            readImageSse(reader, { cancellation?.isCancelled() == true }) { payload, event ->
                val update = imageStream.accept(payload, event)
                if (update?.error != null) { error = update.error; retryable = update.retryable }
                update?.done == true || update?.error != null || payload == "[DONE]"
            }
            if (cancellation?.isCancelled() == true) return ImageGenResult(error = tr("图片生成已停止"))
            if (error != null) return ImageGenResult(error = error, retryable = retryable, text = imageStream.text)
            return imageStream.attachments.firstOrNull()?.let {
                ImageGenResult(attachment = it, text = imageStream.text, revisedPrompt = imageStream.prompt)
            } ?: ImageGenResult(error = tr("图片端点没有返回图片"), text = imageStream.text)
        }
    }

    private fun imagesEditsUrl(endpointUrl: String): String =
        imagesUrl(endpointUrl).removeSuffix("/generations") + "/edits"

    private fun imagesUrl(endpointUrl: String): String {
        val clean = endpointUrl.trim().trimEnd('/')
        val base = when {
            clean.endsWith("/chat/completions") -> clean.removeSuffix("/chat/completions")
            clean.endsWith("/responses") -> clean.removeSuffix("/responses")
            clean.endsWith("/messages") -> clean.removeSuffix("/messages")
            clean.endsWith("/models") -> clean.removeSuffix("/models")
            clean.endsWith("/images/generations") -> clean.removeSuffix("/images/generations")
            else -> clean
        }.trimEnd('/')
        return "$base/images/generations"
    }

    private fun parseImageResponse(
        body: String,
        config: ProviderConfig,
        cancellation: ChatRequestCancellation?,
    ): ImageGenResult? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val data = root.optJSONArray("data") ?: return null
        for (index in 0 until data.length()) {
            val item = data.optJSONObject(index) ?: continue
            val revised = item.optString("revised_prompt")
            val b64 = item.optString("b64_json")
            if (b64.isNotBlank()) {
                val bytes = runCatching { java.util.Base64.getMimeDecoder().decode(b64) }.getOrNull()
                val saved = bytes?.let {
                    saveGeneratedImage(it, root.optString("mimeType").ifBlank { "image/png" }, revised)
                }
                if (saved != null) return saved
            }
            val url = item.optString("url").ifBlank {
                item.optJSONObject("image_url")?.optString("url").orEmpty()
            }
            if (url.isNotBlank()) return imageFromRemoteUrl(url, revised, config, cancellation)
        }
        return null
    }

    /** 部分图片端点只回 url（DALL·E 默认），不回 b64_json。下到本地，后续编辑才能读文件。 */
    internal fun imageFromRemoteUrl(
        url: String,
        revisedPrompt: String,
        config: ProviderConfig,
        cancellation: ChatRequestCancellation?,
    ): ImageGenResult {
        val trimmed = url.trim()
        if (trimmed.startsWith("data:", ignoreCase = true)) {
            val parsed = bytesFromDataUrl(trimmed) ?: return ImageGenResult(error = tr("图片地址无法下载"))
            return saveGeneratedImage(parsed.second, parsed.first, revisedPrompt)
                ?: ImageGenResult(error = tr("图片地址无法下载"))
        }
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return ImageGenResult(error = tr("图片地址无法下载"))
        }
        val connection = runCatching { URL(trimmed).openConnection() as HttpURLConnection }.getOrElse {
            return ImageGenResult(error = tr("图片地址无法下载"))
        }
        cancellation?.attach(connection)
        return try {
            if (cancellation?.isCancelled() == true) return ImageGenResult(error = tr("图片生成已停止"))
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.requestMethod = "GET"
            if (sameOrigin(trimmed, config.endpointUrl)) applyAuth(connection, config)
            val status = connection.responseCode
            if (cancellation?.isCancelled() == true) return ImageGenResult(error = tr("图片生成已停止"))
            if (status !in 200..299) return ImageGenResult(error = tr("图片地址无法下载"))
            val mime = mimeForDownloadedImage(connection.contentType, trimmed)
            val file = File.createTempFile("irio-img-", imageExtensionFor(mime))
            connection.inputStream.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            if (!file.exists() || file.length() <= 0L || file.length() > ChatApi.MAX_ATTACHMENT_BYTES_TOTAL) {
                file.delete()
                return ImageGenResult(error = tr("图片地址无法下载"))
            }
            val name = "image-${System.currentTimeMillis()}${imageExtensionFor(mime)}"
            ImageGenResult(
                attachment = ChatAttachment(name = name, mimeType = mime, filePath = file.absolutePath),
                revisedPrompt = revisedPrompt,
            )
        } catch (_: Exception) {
            if (cancellation?.isCancelled() == true) ImageGenResult(error = tr("图片生成已停止"))
            else ImageGenResult(error = tr("图片地址无法下载"))
        } finally {
            cancellation?.detach(connection)
            connection.disconnect()
        }
    }

    private fun bytesFromDataUrl(value: String): Pair<String, ByteArray>? {
        val comma = value.indexOf(',')
        if (comma <= 5) return null
        val mime = value.substring(5, comma).substringBefore(';').ifBlank { "image/png" }
        val bytes = runCatching { Base64.decode(value.substring(comma + 1), Base64.DEFAULT) }.getOrNull()
            ?: return null
        if (bytes.isEmpty()) return null
        return mime to bytes
    }

    internal fun saveGeneratedImage(bytes: ByteArray, mimeType: String, revisedPrompt: String): ImageGenResult? {
        if (bytes.isEmpty()) return null
        val mime = mimeType.ifBlank { "image/png" }
        val file = File.createTempFile("irio-img-", imageExtensionFor(mime))
        file.writeBytes(bytes)
        return ImageGenResult(
            attachment = ChatAttachment(
                name = "image-${System.currentTimeMillis()}${imageExtensionFor(mime)}",
                mimeType = mime,
                filePath = file.absolutePath,
            ),
            revisedPrompt = revisedPrompt,
        )
    }

    private fun sameOrigin(imageUrl: String, endpointUrl: String): Boolean {
        val image = runCatching { URL(imageUrl) }.getOrNull() ?: return false
        val endpoint = runCatching { URL(endpointUrl.trim()) }.getOrNull() ?: return false
        return image.host.isNotEmpty() && image.host.equals(endpoint.host, ignoreCase = true) &&
            image.protocol.equals(endpoint.protocol, ignoreCase = true) &&
            (if (image.port < 0) image.defaultPort else image.port) ==
            (if (endpoint.port < 0) endpoint.defaultPort else endpoint.port)
    }

    private fun mimeForDownloadedImage(contentType: String?, url: String): String {
        val header = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (header.startsWith("image/")) return header
        val path = url.substringBefore('?').substringAfterLast('/').lowercase()
        return when {
            path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
            path.endsWith(".webp") -> "image/webp"
            path.endsWith(".gif") -> "image/gif"
            else -> "image/png"
        }
    }

    private fun imageExtensionFor(mimeType: String): String = when (mimeType.lowercase()) {
        "image/jpeg", "image/jpg" -> ".jpg"
        "image/webp" -> ".webp"
        "image/gif" -> ".gif"
        else -> ".png"
    }
}

internal fun applyAuth(connection: HttpURLConnection, config: ProviderConfig) {
    require(validateProviderHeaders(config.customHeaders) == null) { tr("自定义请求头无效") }
    // Arbitrary credential headers must not be forwarded by automatic redirects.
    if (config.customHeaders.isNotEmpty() || config.protocol.isGoogleGemini) connection.instanceFollowRedirects = false
    if (config.apiKey.isNotBlank()) {
        if (config.protocol.isGoogleGemini) connection.setRequestProperty("x-goog-api-key", config.apiKey)
        else connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
    }
    if (config.protocol == ProviderProtocol.ANTHROPIC) {
        if (config.apiKey.isNotBlank()) connection.setRequestProperty("x-api-key", config.apiKey)
        connection.setRequestProperty("anthropic-version", "2023-06-01")
    }
    config.customHeaders.forEach { connection.setRequestProperty(it.name, it.value) }
}

internal fun applyAuth(builder: okhttp3.Request.Builder, config: ProviderConfig) {
    require(validateProviderHeaders(config.customHeaders) == null) { tr("自定义请求头无效") }
    if (config.apiKey.isNotBlank()) {
        if (config.protocol.isGoogleGemini) builder.header("x-goog-api-key", config.apiKey)
        else builder.header("Authorization", "Bearer ${config.apiKey}")
    }
    if (config.protocol == ProviderProtocol.ANTHROPIC) {
        if (config.apiKey.isNotBlank()) builder.header("x-api-key", config.apiKey)
        builder.header("anthropic-version", "2023-06-01")
    }
    config.customHeaders.forEach { builder.header(it.name, it.value) }
}
