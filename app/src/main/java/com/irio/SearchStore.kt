package com.irio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class SearchProvider(
    val id: String,
    val label: String,
    val maxResultsMin: Int,
    val maxResultsMax: Int,
    val maxResultsDefault: Int,
) {
    TAVILY("tavily", "Tavily", 1, 20, 5),
    EXA("exa", "Exa", 1, 100, 5);

    fun clampMaxResults(value: Int): Int = value.coerceIn(maxResultsMin, maxResultsMax)

    companion object {
        fun fromId(id: String?): SearchProvider =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: TAVILY
    }
}

object SearchStore {
    private const val PREFS = "search_settings"
    private const val WEB_SEARCH = "web_search_enabled"
    private const val PROVIDER = "search_provider"
    private const val TAVILY_ENCRYPTED_KEY = "tavily_encrypted_key"
    private const val EXA_ENCRYPTED_KEY = "exa_encrypted_key"
    private const val TAVILY_MAX_RESULTS = "max_results"
    private const val EXA_MAX_RESULTS = "exa_max_results"
    private const val KEY_ALIAS = "irio_tavily_key"

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
        val main = Looper.getMainLooper()
        if (Looper.myLooper() == main) dispatch()
        else Handler(main).post(dispatch)
    }

    fun loadProvider(context: Context): SearchProvider =
        SearchProvider.fromId(preferences(context).getString(PROVIDER, null))

    fun saveProvider(context: Context, provider: SearchProvider) {
        preferences(context).edit().putString(PROVIDER, provider.id).apply()
        notifyChanged()
    }

    fun loadApiKey(
        context: Context,
        provider: SearchProvider = loadProvider(context),
    ): String {
        val encrypted = preferences(context).getString(encryptedKeyName(provider), null) ?: return ""
        return decrypt(encrypted).orEmpty()
    }

    fun saveApiKey(
        context: Context,
        apiKey: String,
        provider: SearchProvider = loadProvider(context),
    ) {
        val trimmed = apiKey.trim()
        val editor = preferences(context).edit()
        val keyName = encryptedKeyName(provider)
        if (trimmed.isBlank()) {
            editor.remove(keyName)
        } else {
            editor.putString(keyName, encrypt(trimmed))
        }
        editor.apply()
        notifyChanged()
    }

    fun hasApiKey(context: Context): Boolean = loadApiKey(context).isNotBlank()

    fun loadWebSearchEnabled(context: Context): Boolean =
        preferences(context).getBoolean(WEB_SEARCH, true)

    fun saveWebSearchEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(WEB_SEARCH, enabled).apply()
        notifyChanged()
    }

    fun isWebSearchActive(context: Context): Boolean =
        hasApiKey(context) && loadWebSearchEnabled(context)

    fun loadMaxResults(
        context: Context,
        provider: SearchProvider = loadProvider(context),
    ): Int = provider.clampMaxResults(
        preferences(context).getInt(maxResultsName(provider), provider.maxResultsDefault),
    )

    fun saveMaxResults(
        context: Context,
        value: Int,
        provider: SearchProvider = loadProvider(context),
    ) {
        preferences(context).edit()
            .putInt(maxResultsName(provider), provider.clampMaxResults(value))
            .apply()
        notifyChanged()
    }

    /** 互连同步快照：明文密钥 + 当前提供商 + 条数设置。 */
    fun snapshot(context: Context): JSONObject = JSONObject().apply {
        put("provider", loadProvider(context).id)
        SearchProvider.entries.forEach { provider ->
            put("${provider.id}ApiKey", loadApiKey(context, provider))
            put("${provider.id}MaxResults", loadMaxResults(context, provider))
        }
        put("webSearchEnabled", loadWebSearchEnabled(context))
    }

    /** 应用对端搜索设置：覆盖=全量镜像；共存=本机没有可用密钥时才补密钥（提供商跟着密钥走）。 */
    fun applySnapshot(context: Context, json: JSONObject, overwrite: Boolean) {
        val remoteKeys = SearchProvider.entries.associate { provider ->
            provider to json.optString("${provider.id}ApiKey")
        }
        if (overwrite) {
            json.optString("provider").takeIf { it.isNotBlank() }?.let { id ->
                saveProvider(context, SearchProvider.fromId(id))
            }
            SearchProvider.entries.forEach { provider ->
                saveApiKey(context, remoteKeys[provider].orEmpty(), provider)
                saveMaxResults(
                    context,
                    json.optInt("${provider.id}MaxResults", provider.maxResultsDefault),
                    provider,
                )
            }
            if (json.has("webSearchEnabled")) saveWebSearchEnabled(context, json.optBoolean("webSearchEnabled"))
        } else if (SearchProvider.entries.none { loadApiKey(context, it).isNotBlank() }) {
            val preferred = json.optString("provider").takeIf { it.isNotBlank() }
                ?.let { SearchProvider.fromId(it) }
                ?.takeIf { remoteKeys[it].orEmpty().isNotBlank() }
                ?: SearchProvider.entries.firstOrNull { remoteKeys[it].orEmpty().isNotBlank() }
            if (preferred != null) {
                saveProvider(context, preferred)
                saveApiKey(context, remoteKeys[preferred].orEmpty(), preferred)
            }
        }
    }

    private fun encryptedKeyName(provider: SearchProvider): String = when (provider) {
        SearchProvider.TAVILY -> TAVILY_ENCRYPTED_KEY
        SearchProvider.EXA -> EXA_ENCRYPTED_KEY
    }

    private fun maxResultsName(provider: SearchProvider): String = when (provider) {
        SearchProvider.TAVILY -> TAVILY_MAX_RESULTS
        SearchProvider.EXA -> EXA_MAX_RESULTS
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

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
            GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
        )
        String(cipher.doFinal(Base64.decode(payload, Base64.NO_WRAP)))
    }.getOrNull()
}

data class WebSearchResult(
    val text: String,
    val pageCount: Int = 0,
)

object WebSearch {
    fun search(
        provider: SearchProvider,
        apiKey: String,
        query: String,
        cancellation: ChatRequestCancellation? = null,
        maxResults: Int = provider.maxResultsDefault,
    ): WebSearchResult = when (provider) {
        SearchProvider.TAVILY -> TavilySearch.search(apiKey, query, cancellation, maxResults)
        SearchProvider.EXA -> ExaSearch.search(apiKey, query, cancellation, maxResults)
    }
}

object WebFetch {
    fun fetch(provider: SearchProvider, apiKey: String, url: String, cancellation: ChatRequestCancellation? = null): WebSearchResult {
        val target = url.trim()
        if (!target.startsWith("http://") && !target.startsWith("https://")) return WebSearchResult(tr("网页地址必须以 http:// 或 https:// 开头"))
        val endpoint = when (provider) {
            SearchProvider.TAVILY -> "https://api.tavily.com/extract"
            SearchProvider.EXA -> "https://api.exa.ai/contents"
        }
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        cancellation?.attach(connection)
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            if (provider == SearchProvider.TAVILY) connection.setRequestProperty("Authorization", "Bearer $apiKey")
            else {
                connection.setRequestProperty("x-api-key", apiKey)
                connection.setRequestProperty("Authorization", "Bearer $apiKey")
            }
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val payload = if (provider == SearchProvider.TAVILY) JSONObject().put("urls", JSONArray().put(target))
            else JSONObject().put("urls", JSONArray().put(target)).put("text", true)
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (cancellation?.isCancelled() == true) WebSearchResult(tr("网页读取已停止"))
            else if (status !in 200..299) WebSearchResult(tr("网页读取失败（HTTP %s）").format(status))
            else {
                val item = JSONObject(body).optJSONArray("results")?.optJSONObject(0)
                val text = if (provider == SearchProvider.TAVILY) item?.optString("raw_content") else item?.optString("text")
                WebSearchResult(text.orEmpty().ifBlank { tr("网页没有可读取的正文") })
            }
        } catch (error: Exception) {
            if (cancellation?.isCancelled() == true) WebSearchResult(tr("网页读取已停止")) else WebSearchResult(error.message ?: tr("网页读取失败"))
        } finally {
            cancellation?.detach(connection)
            connection.disconnect()
        }
    }
}

object TavilySearch {
    const val ENDPOINT = "https://api.tavily.com"
    const val SEARCH_PATH = "/search"

    fun search(
        apiKey: String,
        query: String,
        cancellation: ChatRequestCancellation? = null,
        maxResults: Int = SearchProvider.TAVILY.maxResultsDefault,
    ): WebSearchResult {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return WebSearchResult(tr("缺少 query"))
        if (apiKey.isBlank()) return WebSearchResult(tr("未配置 Tavily 密钥"))
        val connection = runCatching {
            URL("$ENDPOINT$SEARCH_PATH").openConnection() as HttpURLConnection
        }.getOrElse { return WebSearchResult(it.message ?: tr("无法连接到 Tavily")) }
        // 连接挂到 cancellation 上：停止按钮能立即断开搜索中的阻塞 IO。
        cancellation?.attach(connection)
        return try {
            if (cancellation?.isCancelled() == true) return WebSearchResult(tr("搜索已停止"))
            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            val payload = JSONObject()
                .put("query", trimmed)
                .put("include_answer", true)
                .put("max_results", SearchProvider.TAVILY.clampMaxResults(maxResults))
                .toString()
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (cancellation?.isCancelled() == true) return WebSearchResult(tr("搜索已停止"))
            if (status !in 200..299) {
                WebSearchResult(tavilyError(body).ifBlank { tr("搜索失败（HTTP %s）").format(status) })
            } else {
                formatResults(body)
            }
        } catch (error: Exception) {
            if (cancellation?.isCancelled() == true) WebSearchResult(tr("搜索已停止"))
            else WebSearchResult(error.message ?: tr("无法连接到 Tavily"))
        } finally {
            cancellation?.detach(connection)
            connection.disconnect()
        }
    }

    private fun formatResults(body: String): WebSearchResult {
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: return WebSearchResult(body.ifBlank { tr("没有搜索结果") })
        val results = root.optJSONArray("results") ?: JSONArray()
        val text = StringBuilder()
        root.optString("answer").takeIf { it.isNotBlank() }?.let { text.append(it).append("\n\n") }
        for (index in 0 until results.length()) {
            val item = results.optJSONObject(index) ?: continue
            item.optString("title").takeIf { it.isNotBlank() }?.let { text.append(it).append('\n') }
            item.optString("url").takeIf { it.isNotBlank() }?.let { text.append(it).append('\n') }
            item.optString("content").takeIf { it.isNotBlank() }?.let { text.append(it).append("\n\n") }
        }
        return WebSearchResult(
            text = text.toString().trim().ifBlank { tr("没有搜索结果") },
            pageCount = results.length(),
        )
    }

    private fun tavilyError(body: String): String = runCatching {
        val response = JSONObject(body)
        response.optJSONObject("detail")?.optString("error")
            ?: response.optString("detail").ifBlank { response.optString("message") }
    }.getOrDefault("")
}

object ExaSearch {
    const val ENDPOINT = "https://api.exa.ai/search"

    fun search(
        apiKey: String,
        query: String,
        cancellation: ChatRequestCancellation? = null,
        maxResults: Int = SearchProvider.EXA.maxResultsDefault,
    ): WebSearchResult {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return WebSearchResult(tr("缺少 query"))
        if (apiKey.isBlank()) return WebSearchResult(tr("未配置 Exa 密钥"))
        val connection = runCatching {
            URL(ENDPOINT).openConnection() as HttpURLConnection
        }.getOrElse { return WebSearchResult(it.message ?: tr("无法连接到 Exa")) }
        cancellation?.attach(connection)
        return try {
            if (cancellation?.isCancelled() == true) return WebSearchResult(tr("搜索已停止"))
            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("x-api-key", apiKey)
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            val payload = JSONObject()
                .put("query", trimmed)
                .put("numResults", SearchProvider.EXA.clampMaxResults(maxResults))
                .put("contents", JSONObject().put("text", true))
                .toString()
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (cancellation?.isCancelled() == true) return WebSearchResult(tr("搜索已停止"))
            if (status !in 200..299) {
                WebSearchResult(exaError(body).ifBlank { tr("搜索失败（HTTP %s）").format(status) })
            } else {
                formatResults(body)
            }
        } catch (error: Exception) {
            if (cancellation?.isCancelled() == true) WebSearchResult(tr("搜索已停止"))
            else WebSearchResult(error.message ?: tr("无法连接到 Exa"))
        } finally {
            cancellation?.detach(connection)
            connection.disconnect()
        }
    }

    private fun formatResults(body: String): WebSearchResult {
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: return WebSearchResult(body.ifBlank { tr("没有搜索结果") })
        val results = root.optJSONArray("results") ?: JSONArray()
        val text = StringBuilder()
        root.optString("context").takeIf { it.isNotBlank() }?.let { text.append(it).append("\n\n") }
        for (index in 0 until results.length()) {
            val item = results.optJSONObject(index) ?: continue
            item.optString("title").takeIf { it.isNotBlank() }?.let { text.append(it).append('\n') }
            item.optString("url").takeIf { it.isNotBlank() }?.let { text.append(it).append('\n') }
            item.optString("text").takeIf { it.isNotBlank() }?.let { text.append(it).append("\n\n") }
                ?: item.optString("summary").takeIf { it.isNotBlank() }?.let { text.append(it).append("\n\n") }
        }
        return WebSearchResult(
            text = text.toString().trim().ifBlank { tr("没有搜索结果") },
            pageCount = results.length(),
        )
    }

    private fun exaError(body: String): String = runCatching {
        val response = JSONObject(body)
        val nested = response.optJSONObject("error")
        nested?.optString("message")?.takeIf { it.isNotBlank() }
            ?: response.optString("error").ifBlank { response.optString("message") }
    }.getOrDefault("")
}
