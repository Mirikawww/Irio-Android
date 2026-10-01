package com.irio

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.util.Base64
import java.io.DataOutputStream
import java.util.UUID
import javax.crypto.Cipher
import javax.swing.SwingUtilities
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

enum class ProviderProtocol(val label: String, val defaultEndpoint: String) {
    CHAT_COMPLETIONS("OpenAI Chat Completions", "https://api.openai.com/v1"),
    RESPONSES("OpenAI Responses", "https://api.openai.com/v1"),
    ANTHROPIC("Anthropic Messages", "https://api.anthropic.com"),
}

enum class ReasoningEffort(val label: String, val apiValue: String?) {
    OFF("关闭", "none"),
    AUTO("自动", null),
    MINIMAL("最低", "minimal"),
    LOW("低", "low"),
    MEDIUM("中", "medium"),
    HIGH("高", "high"),
    XHIGH("最高", "xhigh"),
    MAX("超高", "max"),
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
)

data class ModelFetchResult(
    val models: List<String> = emptyList(),
    val error: String? = null,
)

/** 提供商详情里关掉的模型不进任何模型菜单。缺省集合表示全部开启。 */
fun ProviderConfig.enabledModels(): List<String> = models.filter { it !in disabledModels }

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
    private const val PROVIDERS = "providers"
    private const val ACTIVE_ID = "active_id"
    private const val LAST_CHAT_MODEL = "last_chat_model"
    private const val REASONING_EFFORT = "reasoning_effort"
    private const val FAST_MODE = "fast_mode"
    private const val MERGE_MODELS = "merge_models"
    private const val TEX_SCALE = "tex_scale"

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
        if (SwingUtilities.isEventDispatchThread()) dispatch() else SwingUtilities.invokeLater(dispatch)
    }

    fun loadProviders(): List<ProviderConfig> {
        val root = loadRoot()
        val array = root.optJSONArray(PROVIDERS) ?: JSONArray()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val apiKey = decrypt(item.optString("encrypted_key")) ?: continue
                val protocol = runCatching {
                    ProviderProtocol.valueOf(item.optString("protocol"))
                }.getOrDefault(ProviderProtocol.CHAT_COMPLETIONS)
                val models = stringList(item.optJSONArray("models"))
                val disabled = stringList(item.optJSONArray("disabled_models")).filter { it in models }.toSet()
                val selected = item.optString("selected_model").takeIf { it in models }
                    ?: models.firstOrNull().orEmpty()
                add(
                    ProviderConfig(
                        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                        name = item.optString("name"),
                        endpointUrl = item.optString("endpoint"),
                        apiKey = apiKey,
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

    fun loadProvider(id: String? = null): ProviderConfig? {
        val providers = loadProviders()
        val targetId = id ?: loadActiveProviderId()
        return providers.firstOrNull { it.id == targetId } ?: providers.firstOrNull()
    }

    fun loadActiveProviderId(): String {
        val ids = loadProviders().map { it.id }
        val saved = loadRoot().optString(ACTIVE_ID).takeIf { it.isNotBlank() }
        return saved?.takeIf { it in ids } ?: ids.firstOrNull().orEmpty()
    }

    fun saveActiveProviderId(id: String) {
        val root = loadRoot()
        root.put(ACTIVE_ID, id)
        persistRoot(root)
    }

    /** 最近一次选中的「非图片」对话模型 id；标题生成在当前模型是图片模型时用它。 */
    fun loadLastChatModel(): String = loadRoot().optString(LAST_CHAT_MODEL)

    fun saveLastChatModel(model: String) {
        val root = loadRoot()
        root.put(LAST_CHAT_MODEL, model)
        persistRoot(root)
    }

    fun saveProvider(config: ProviderConfig) {
        val providers = loadProviders().toMutableList()
        val index = providers.indexOfFirst { it.id == config.id }
        val selected = config.selectedModel.takeIf { it in config.models }
            ?: config.models.firstOrNull().orEmpty()
        val disabled = config.disabledModels.intersect(config.models.toSet())
        val stored = config.copy(selectedModel = selected, disabledModels = disabled)
        if (index >= 0) providers[index] = stored else providers.add(stored)
        persist(providers, stored.id)
    }

    fun saveDisabledModels(providerId: String, disabled: Set<String>) {
        val root = loadRoot()
        val array = root.optJSONArray(PROVIDERS) ?: return
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
        root.put(PROVIDERS, array)
        persistRoot(root)
    }

    fun saveProviderEnabled(providerId: String, enabled: Boolean) {
        val root = loadRoot()
        val array = root.optJSONArray(PROVIDERS) ?: return
        var found = false
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            if (item.optString("id") != providerId) continue
            item.put("enabled", enabled)
            found = true
            break
        }
        if (!found) return
        root.put(PROVIDERS, array)
        persistRoot(root)
    }

    fun deleteProvider(id: String) {
        val remaining = loadProviders().filterNot { it.id == id }
        val active = loadActiveProviderId().takeIf { it != id && remaining.any { provider -> provider.id == it } }
            ?: remaining.firstOrNull()?.id.orEmpty()
        persist(remaining, active)
    }

    fun saveSelectedModel(model: String, providerId: String? = null) {
        val provider = loadProvider(providerId) ?: return
        if (model !in provider.models) return
        saveProvider(provider.copy(selectedModel = model))
    }

    fun loadReasoningEffort(): ReasoningEffort {
        val saved = loadRoot().optString(REASONING_EFFORT).takeIf { it.isNotBlank() }
        return saved?.let { runCatching { ReasoningEffort.valueOf(it) }.getOrNull() }
            ?: ReasoningEffort.OFF
    }

    fun saveReasoningEffort(effort: ReasoningEffort) {
        val root = loadRoot()
        root.put(REASONING_EFFORT, effort.name)
        persistRoot(root)
    }

    fun loadFastMode(): Boolean = loadRoot().optBoolean(FAST_MODE, false)

    fun saveFastMode(enabled: Boolean) {
        val root = loadRoot()
        root.put(FAST_MODE, enabled)
        persistRoot(root)
    }

    fun loadMergeModels(): Boolean = loadRoot().optBoolean(MERGE_MODELS, true)

    fun saveMergeModels(enabled: Boolean) {
        val root = loadRoot()
        root.put(MERGE_MODELS, enabled)
        persistRoot(root)
    }

    fun loadTexScale(): Float =
        loadRoot().optDouble(TEX_SCALE, 1.0).toFloat().coerceIn(0.6f, 1.6f)

    fun saveTexScale(scale: Float) {
        val root = loadRoot()
        root.put(TEX_SCALE, scale.toDouble().coerceIn(0.6, 1.6))
        persistRoot(root)
    }

    private fun persist(providers: List<ProviderConfig>, activeId: String) {
        val array = JSONArray().apply {
            providers.forEach { provider ->
                put(
                    JSONObject().apply {
                        put("id", provider.id)
                        put("name", provider.name)
                        put("endpoint", provider.endpointUrl)
                        put("encrypted_key", encrypt(provider.apiKey))
                        put("protocol", provider.protocol.name)
                        put("models", JSONArray().apply { provider.models.forEach(::put) })
                        put("disabled_models", JSONArray().apply { provider.disabledModels.forEach(::put) })
                        put("enabled", provider.enabled)
                        put("selected_model", provider.selectedModel)
                    }
                )
            }
        }
        val root = loadRoot()
        root.put(PROVIDERS, array)
        root.put(ACTIVE_ID, activeId)
        persistRoot(root)
    }

    private fun loadRoot(): JSONObject {
        val file = AppPaths.providersFile
        if (!file.exists()) return JSONObject()
        return runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
    }

    private fun persistRoot(root: JSONObject) {
        AppPaths.providersFile.writeText(root.toString())
        notifyChanged()
    }

    fun snapshot(): JSONObject {
        return JSONObject().apply {
            put(
                "providers",
                JSONArray().apply {
                    loadProviders().forEach { provider ->
                        put(
                            JSONObject().apply {
                                put("id", provider.id)
                                put("name", provider.name)
                                put("endpoint", provider.endpointUrl)
                                put("apiKey", provider.apiKey)
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
            put("activeId", loadActiveProviderId())
            put("reasoning", loadReasoningEffort().name)
            put("fastMode", loadFastMode())
        }
    }

    fun applySnapshot(json: JSONObject, overwrite: Boolean) {
        val incoming = parseSnapshotProviders(json)
        if (incoming.isEmpty() && json.optJSONArray("providers")?.length() ?: 0 > 0) return
        if (overwrite) {
            persist(incoming, json.optString("activeId"))
            json.optString("reasoning").takeIf { it.isNotBlank() }?.let { name ->
                runCatching { ReasoningEffort.valueOf(name) }.getOrNull()?.let { saveReasoningEffort(it) }
            }
            if (json.has("fastMode")) saveFastMode(json.optBoolean("fastMode"))
        } else {
            val local = loadProviders().toMutableList()
            incoming.forEach { remote ->
                if (local.none { it.id == remote.id }) local.add(remote)
            }
            persist(local, loadActiveProviderId())
        }
    }

    private fun parseSnapshotProviders(json: JSONObject): List<ProviderConfig> {
        val array = json.optJSONArray("providers") ?: JSONArray()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val apiKey = item.optString("apiKey")
                if (apiKey.isBlank()) continue
                val protocol = runCatching {
                    ProviderProtocol.valueOf(item.optString("protocol"))
                }.getOrDefault(ProviderProtocol.CHAT_COMPLETIONS)
                val models = stringList(item.optJSONArray("models"))
                val disabled = stringList(item.optJSONArray("disabled_models")).filter { it in models }.toSet()
                val selected = item.optString("selected_model").takeIf { it in models }
                    ?: models.firstOrNull().orEmpty()
                add(
                    ProviderConfig(
                        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                        name = item.optString("name"),
                        endpointUrl = item.optString("endpoint"),
                        apiKey = apiKey,
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

    private fun stringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optString(index).takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }

    private fun secretKey(): SecretKey {
        val file = AppPaths.keyFile
        if (file.exists()) {
            return SecretKeySpec(file.readBytes(), "AES")
        }
        val generator = KeyGenerator.getInstance("AES")
        generator.init(256, SecureRandom())
        val key = generator.generateKey()
        file.parentFile.mkdirs()
        file.writeBytes(key.encoded)
        runCatching {
            Files.setPosixFilePermissions(
                file.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
        return key
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = Base64.getEncoder().encodeToString(cipher.iv)
        val payload = Base64.getEncoder().encodeToString(cipher.doFinal(value.toByteArray()))
        return "$iv:$payload"
    }

    private fun decrypt(value: String): String? = runCatching {
        val (iv, payload) = value.split(':', limit = 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(128, Base64.getDecoder().decode(iv))
        )
        String(cipher.doFinal(Base64.getDecoder().decode(payload)))
    }.getOrNull()
}

data class ImageGenResult(
    val attachment: ChatAttachment? = null,
    val revisedPrompt: String = "",
    val error: String? = null,
)

object ProviderApi {
    fun fetchModels(config: ProviderConfig): ModelFetchResult {
        val connection = runCatching {
            URL(modelsUrl(config.endpointUrl, config.protocol)).openConnection() as HttpURLConnection
        }.getOrElse { return ModelFetchResult(error = tr("端点 URL 无效")) }

        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            applyAuth(connection, config)
            connection.setRequestProperty("Accept", "application/json")
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
    ): ImageGenResult {
        if (cancellation?.isCancelled() == true) return ImageGenResult(error = tr("图片生成已停止"))
        if (context != null && context.previousImages.isNotEmpty()) {
            val edited = runCatching { generateImageWithEdits(config, model, prompt, context, cancellation) }.getOrNull()
            if (edited != null) return edited
            // edits 不可用（端点 404/405/415 或网络层拒绝）→ 回落 generations，把上轮 prompt 织进文本
            val combined = buildString {
                append(tr("上一次生成请求："))
                append(context.previousPrompt.trim())
                append(tr("。参考图即上一轮的生成结果。本次要求："))
                append(prompt.trim())
            }
            return generateImagePlain(config, model, combined, cancellation)
        }
        return generateImagePlain(config, model, prompt, cancellation)
    }

    private fun generateImagePlain(
        config: ProviderConfig,
        model: String,
        prompt: String,
        cancellation: ChatRequestCancellation?,
    ): ImageGenResult {
        val connection = runCatching {
            URL(imagesUrl(config.endpointUrl)).openConnection() as HttpURLConnection
        }.getOrElse { return ImageGenResult(error = tr("端点 URL 无效")) }
        cancellation?.attach(connection)

        return try {
            if (cancellation?.isCancelled() == true) return ImageGenResult(error = tr("图片生成已停止"))
            connection.requestMethod = "POST"
            connection.connectTimeout = 30_000
            connection.readTimeout = 300_000
            connection.doOutput = true
            applyAuth(connection, config)
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { output ->
                if (cancellation?.isCancelled() == true) return ImageGenResult(error = tr("图片生成已停止"))
                output.write(
                    JSONObject()
                        .put("model", model)
                        .put("prompt", prompt)
                        .put("n", 1)
                        .put("size", "1024x1024")
                        .toString()
                        .toByteArray(Charsets.UTF_8)
                )
            }
            val status = connection.responseCode
            if (cancellation?.isCancelled() == true) return ImageGenResult(error = tr("图片生成已停止"))
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (cancellation?.isCancelled() == true) return ImageGenResult(error = tr("图片生成已停止"))
            if (status !in 200..299) {
                ImageGenResult(error = apiError(body).ifBlank { tr("图片生成失败（HTTP %s）").format(status) })
            } else {
                parseImageResponse(body, config, cancellation)
                    ?: ImageGenResult(error = tr("图片端点没有返回图片"))
            }
        } catch (error: Exception) {
            if (cancellation?.isCancelled() == true) ImageGenResult(error = tr("图片生成已停止"))
            else ImageGenResult(error = error.message ?: tr("无法连接到图片端点"))
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
    ): ImageGenResult? {
        val connection = runCatching {
            URL(imagesEditsUrl(config.endpointUrl)).openConnection() as HttpURLConnection
        }.getOrNull() ?: return null
        cancellation?.attach(connection)
        val boundary = "irio-${UUID.randomUUID()}"
        return try {
            if (cancellation?.isCancelled() == true) return ImageGenResult(error = tr("图片生成已停止"))
            connection.requestMethod = "POST"
            connection.connectTimeout = 30_000
            connection.readTimeout = 300_000
            connection.doOutput = true
            applyAuth(connection, config)
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.setRequestProperty("Accept", "application/json")
            DataOutputStream(connection.outputStream).use { output ->
                if (cancellation?.isCancelled() == true) return ImageGenResult(error = tr("图片生成已停止"))
                fun field(name: String, value: String) {
                    output.writeBytes("--$boundary\r\n")
                    output.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                    output.write(value.toByteArray(Charsets.UTF_8))
                    output.writeBytes("\r\n")
                }
                field("model", model)
                field("prompt", prompt)
                field("n", "1")
                field("size", "1024x1024")
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
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (cancellation?.isCancelled() == true) return ImageGenResult(error = tr("图片生成已停止"))
            when {
                status in 200..299 -> parseImageResponse(body, config, cancellation)
                    ?: ImageGenResult(error = tr("图片端点没有返回图片"))
                // 端点不支持 edits（路由不存在/方法不允许/类型不收）→ 让调用方回落 generations
                status == 404 || status == 405 || status == 415 -> null
                else -> ImageGenResult(error = apiError(body).ifBlank { tr("图片生成失败（HTTP %s）").format(status) })
            }
        } catch (error: Exception) {
            if (cancellation?.isCancelled() == true) ImageGenResult(error = tr("图片生成已停止"))
            else null
        } finally {
            cancellation?.detach(connection)
            connection.disconnect()
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
                val bytes = runCatching { Base64.getMimeDecoder().decode(b64) }.getOrNull()
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
    private fun imageFromRemoteUrl(
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
            if (sameHost(trimmed, config.endpointUrl)) applyAuth(connection, config)
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
        val bytes = runCatching { Base64.getMimeDecoder().decode(value.substring(comma + 1)) }.getOrNull()
            ?: return null
        if (bytes.isEmpty()) return null
        return mime to bytes
    }

    private fun saveGeneratedImage(bytes: ByteArray, mimeType: String, revisedPrompt: String): ImageGenResult? {
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

    private fun sameHost(imageUrl: String, endpointUrl: String): Boolean {
        val imageHost = runCatching { URL(imageUrl).host }.getOrNull()?.lowercase() ?: return false
        val endpointHost = runCatching { URL(endpointUrl.trim()).host }.getOrNull()?.lowercase() ?: return false
        return imageHost.isNotEmpty() && imageHost == endpointHost
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
    connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
    if (config.protocol == ProviderProtocol.ANTHROPIC) {
        connection.setRequestProperty("x-api-key", config.apiKey)
        connection.setRequestProperty("anthropic-version", "2023-06-01")
    }
}

internal fun applyAuth(builder: okhttp3.Request.Builder, config: ProviderConfig) {
    builder.header("Authorization", "Bearer ${config.apiKey}")
    if (config.protocol == ProviderProtocol.ANTHROPIC) {
        builder.header("x-api-key", config.apiKey)
        builder.header("anthropic-version", "2023-06-01")
    }
}
