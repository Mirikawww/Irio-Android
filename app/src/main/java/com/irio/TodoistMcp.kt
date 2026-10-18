package com.irio

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.coroutineContext

internal data class TodoistCredential(
    val accessToken: String,
    val expiresAt: Long = 0,
    val refreshToken: String? = null,
) {
    fun shouldRefresh(now: Long = System.currentTimeMillis()): Boolean =
        !refreshToken.isNullOrBlank() && expiresAt > 0 && expiresAt <= now + REFRESH_EARLY_MS

    companion object { const val REFRESH_EARLY_MS = 5 * 60 * 1000L }
}

internal data class TodoistMcpInstallation(
    val clientId: String,
    val credential: TodoistCredential,
    val tools: List<McpTool>,
    val enabled: Boolean = true,
    val account: TodoistMcpAccount? = null,
) {
    fun usable(): Boolean = enabled && clientId.isNotBlank() && credential.accessToken.isNotBlank() &&
        tools.isNotEmpty() && (credential.expiresAt == 0L || credential.expiresAt > System.currentTimeMillis())
}

internal data class TodoistOAuthResult(val clientId: String, val credential: TodoistCredential)

/** OAuth 2.1 public-client flow for Todoist's hosted MCP server, using DCR + PKCE + loopback. */
internal class TodoistMcpOAuth(
    private val http: OkHttpClient = client,
    private val resourceMetadataUrl: HttpUrl = "https://ai.todoist.net/.well-known/oauth-protected-resource/mcp".toHttpUrl(),
    private val authMetadataUrl: HttpUrl = "https://todoist.com/.well-known/oauth-authorization-server".toHttpUrl(),
    private val authorizationHost: String = "todoist.com",
    private val allowInsecureHttpForTests: Boolean = false,
) {
    suspend fun authorize(openBrowser: (String) -> Unit): TodoistOAuthResult {
        val server = withContext(Dispatchers.IO) { ServerSocket(0, 1, InetAddress.getByName("localhost")).apply { soTimeout = 1_000 } }
        try {
            val redirectUri = "http://localhost:${server.localPort}/oauth/callback"
            val resourceMetadata = getJson(resourceMetadataUrl)
            val resource = resourceMetadata.getString("resource")
            require(resource == "https://ai.todoist.net/mcp") { tr("Todoist MCP 地址无效") }
            val issuer = resourceMetadata.getJSONArray("authorization_servers").getString(0).toHttpUrl()
            require(isTrustedOAuthEndpoint(issuer)) { tr("Todoist OAuth 地址无效") }
            val authMetadata = getJson(authMetadataUrl.takeIf { it.host == issuer.host } ?: metadataUrl(issuer))
            val registrationEndpoint = authMetadata.getString("registration_endpoint").toHttpUrl()
            val tokenEndpoint = authMetadata.getString("token_endpoint").toHttpUrl()
            val authorizationEndpoint = authMetadata.getString("authorization_endpoint").toHttpUrl()
            require(listOf(registrationEndpoint, tokenEndpoint, authorizationEndpoint).all(::isTrustedOAuthEndpoint)) {
                tr("Todoist OAuth 地址无效")
            }
            require("S256" in authMetadata.getJSONArray("code_challenge_methods_supported").strings()) {
                tr("Todoist OAuth 不支持安全的 PKCE")
            }
            val registration = postJson(registrationEndpoint, JSONObject()
                .put("client_name", "Irio")
                .put("redirect_uris", JSONArray().put(redirectUri))
                .put("scope", "data:read_write")
                .put("grant_types", JSONArray().put("authorization_code").put("refresh_token"))
                .put("response_types", JSONArray().put("code"))
                .put("token_endpoint_auth_method", "none"))
            val clientId = registration.getString("client_id")
            val verifier = randomUrlSafe(32)
            val challenge = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
            val state = randomUrlSafe(24)
            val authorizeUrl = authorizationEndpoint.newBuilder()
                .addQueryParameter("response_type", "code")
                .addQueryParameter("client_id", clientId)
                .addQueryParameter("redirect_uri", redirectUri)
                .addQueryParameter("scope", "data:read_write")
                .addQueryParameter("state", state)
                .addQueryParameter("code_challenge", challenge)
                .addQueryParameter("code_challenge_method", "S256")
                .addQueryParameter("resource", resource)
                .build().toString()
            openBrowser(authorizeUrl)
            val code = awaitAuthorizationCode(server, state)
            val token = postForm(tokenEndpoint, mapOf(
                "grant_type" to "authorization_code",
                "client_id" to clientId,
                "code" to code,
                "redirect_uri" to redirectUri,
                "code_verifier" to verifier,
                "resource" to resource,
            ))
            return TodoistOAuthResult(clientId, credential(token))
        } finally {
            runCatching { server.close() }
        }
    }

    suspend fun refresh(clientId: String, credential: TodoistCredential): TodoistCredential {
        val refreshToken = credential.refreshToken?.takeIf(String::isNotBlank)
            ?: throw IOException(tr("Todoist 登录已过期，请重新连接"))
        val metadata = getJson(authMetadataUrl)
        val tokenEndpoint = metadata.getString("token_endpoint").toHttpUrl()
        require(isTrustedOAuthEndpoint(tokenEndpoint)) { tr("Todoist OAuth 地址无效") }
        val token = postForm(tokenEndpoint, mapOf(
            "grant_type" to "refresh_token",
            "client_id" to clientId,
            "refresh_token" to refreshToken,
            "resource" to "https://ai.todoist.net/mcp",
        ))
        val result = credential(token)
        if (result.refreshToken.isNullOrBlank() || result.refreshToken == refreshToken) {
            throw IOException(tr("Todoist 刷新令牌无效，请重新连接"))
        }
        return result
    }

    private suspend fun awaitAuthorizationCode(server: ServerSocket, expectedState: String): String =
        withContext(Dispatchers.IO) {
            val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5)
            while (System.nanoTime() < deadline) {
                coroutineContext.ensureActive()
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val requestLine = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII)).readLine().orEmpty()
                        val target = requestLine.split(' ').getOrNull(1)
                        val callback = target?.let { runCatching { URI("http://localhost$it") }.getOrNull() }
                        val query = callback?.rawQuery.orEmpty().split('&').filter(String::isNotBlank).associate { pair ->
                            val parts = pair.split('=', limit = 2)
                            URLDecoder.decode(parts[0], Charsets.UTF_8.name()) to
                                URLDecoder.decode(parts.getOrElse(1) { "" }, Charsets.UTF_8.name())
                        }
                        val state = query["state"]
                        val code = query["code"]
                        val error = query["error"]
                        val validPath = callback?.path == "/oauth/callback"
                        val valid = requestLine.startsWith("GET ") && validPath && state == expectedState
                        val page = if (valid && code != null) "Todoist 已连接 Irio，可以关闭此页面。" else "授权未完成，请返回 Irio 重试。"
                        val body = "<!doctype html><meta charset=utf-8><title>Todoist</title><p>$page</p>"
                        socket.getOutputStream().write((
                            "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                                "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\nConnection: close\r\n\r\n$body"
                            ).toByteArray(Charsets.UTF_8))
                        socket.getOutputStream().flush()
                        if (!valid) continue
                        if (!error.isNullOrBlank()) throw IOException(tr("Todoist 授权未完成，请返回 Irio 重试"))
                        return@withContext code?.takeIf(String::isNotBlank)
                            ?: throw IOException(tr("Todoist 授权响应无效"))
                    }
                } catch (_: SocketTimeoutException) {
                    // A local browser can take time to finish login; keep checking coroutine cancellation.
                }
            }
            throw IOException(tr("Todoist 授权已超时，请重试"))
        }

    private suspend fun getJson(url: HttpUrl): JSONObject = withContext(Dispatchers.IO) {
        val call = http.newCall(Request.Builder().url(url).get().build())
        call.execute().use { response ->
            if (!response.isSuccessful) throw IOException(tr("Todoist OAuth 服务发现失败（HTTP %d）").format(response.code))
            JSONObject(response.body?.string() ?: throw IOException(tr("Todoist OAuth 响应无效")))
        }
    }

    private suspend fun postJson(url: HttpUrl, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).post(body.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException(tr("Todoist OAuth 客户端注册失败（HTTP %d）").format(response.code))
            JSONObject(response.body?.string() ?: throw IOException(tr("Todoist OAuth 响应无效")))
        }
    }

    private suspend fun postForm(url: HttpUrl, values: Map<String, String>): JSONObject = withContext(Dispatchers.IO) {
        val form = FormBody.Builder().apply { values.forEach { (key, value) -> add(key, value) } }.build()
        http.newCall(Request.Builder().url(url).post(form).build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException(tr("Todoist OAuth 令牌请求失败（HTTP %d）").format(response.code))
            JSONObject(text)
        }
    }

    private fun credential(json: JSONObject): TodoistCredential {
        val accessToken = json.optString("access_token")
        require(accessToken.isNotBlank() && json.optString("token_type").equals("bearer", true)) {
            tr("Todoist OAuth 令牌响应无效")
        }
        val expiresIn = json.optLong("expires_in", 0)
        return TodoistCredential(accessToken, if (expiresIn > 0) System.currentTimeMillis() + expiresIn * 1000 else 0,
            json.optString("refresh_token").takeIf(String::isNotBlank))
    }

    private fun metadataUrl(issuer: HttpUrl): HttpUrl = issuer.newBuilder().addPathSegments(".well-known/oauth-authorization-server").build()

    private fun isTrustedOAuthEndpoint(url: HttpUrl): Boolean = url.host == authorizationHost &&
        (url.scheme == "https" || (allowInsecureHttpForTests && url.scheme == "http"))

    private fun randomUrlSafe(bytes: Int): String = ByteArray(bytes).also(SecureRandom()::nextBytes)
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    private fun JSONArray.strings(): List<String> = (0 until length()).map(::getString)

    companion object {
        private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    }
}

internal class TodoistMcpStore(
    private val file: File,
    private val secretKey: () -> SecretKey,
    private val refresh: suspend (String, TodoistCredential) -> TodoistCredential = { clientId, credential ->
        TodoistMcpOAuth().refresh(clientId, credential)
    },
) {
    fun save(installation: TodoistMcpInstallation) = synchronized(lock) {
        require(installation.clientId.isNotBlank() && installation.credential.accessToken.isNotBlank() && installation.tools.isNotEmpty())
        val payload = JSONObject().put("client_id", installation.clientId)
            .put("access_token", installation.credential.accessToken)
            .put("expires_at", installation.credential.expiresAt)
            .put("enabled", installation.enabled)
            .put("tools", JSONArray(installation.tools.map { tool -> JSONObject()
                .put("name", tool.name).put("description", tool.description).put("inputSchema", tool.inputSchema) }))
        installation.credential.refreshToken?.let { payload.put("refresh_token", it) }
        installation.account?.let { account ->
            payload.put("account", JSONObject().put("name", account.name)
                .put("avatar_url", account.avatarUrl).put("email", account.email))
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(payload.toString().toByteArray(Charsets.UTF_8))
        val json = JSONObject().put("iv", Base64.getEncoder().encodeToString(cipher.iv))
            .put("credential", Base64.getEncoder().encodeToString(encrypted))
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        try {
            temporary.outputStream().use { output -> output.write(json.toString().toByteArray(Charsets.UTF_8)); output.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { temporary.delete() }
    }

    fun load(): TodoistMcpInstallation? = synchronized(lock) {
        runCatching {
            if (!file.exists() || file.length() > MAX_STORE_BYTES) return@synchronized null
            val json = JSONObject(file.readText())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.getDecoder().decode(json.getString("iv"))))
            val payload = JSONObject(String(cipher.doFinal(Base64.getDecoder().decode(json.getString("credential"))), Charsets.UTF_8))
            val tools = payload.getJSONArray("tools").let { array -> (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                McpTool(item.getString("name"), item.optString("description"), item.getJSONObject("inputSchema"))
            } }
            val account = payload.optJSONObject("account")?.let {
                TodoistMcpAccount(it.getString("name"), it.getString("avatar_url"), it.getString("email"))
            }
            TodoistMcpInstallation(payload.getString("client_id"), TodoistCredential(
                payload.getString("access_token"), payload.optLong("expires_at"),
                payload.optString("refresh_token").takeIf(String::isNotBlank)), tools, payload.optBoolean("enabled", true), account)
        }.getOrNull()
    }

    fun setEnabled(enabled: Boolean) = synchronized(lock) { load()?.let { save(it.copy(enabled = enabled)) } }

    fun remove() = synchronized(lock) { check(!file.exists() || file.delete()) { tr("无法移除 Todoist 集成，请重试") } }

    fun updateAccount(expectedToken: String, account: TodoistMcpAccount): TodoistMcpInstallation? = synchronized(lock) {
        val current = load()?.takeIf { it.credential.accessToken == expectedToken } ?: return@synchronized null
        val updated = current.copy(account = account)
        save(updated)
        updated
    }

    suspend fun current(): TodoistMcpInstallation? {
        if (load()?.enabled != true) return null
        return refreshIfNeeded()?.takeIf { it.usable() }
    }

    /** Account details remain readable when the integration is hidden from conversations. */
    suspend fun refreshIfNeeded(): TodoistMcpInstallation? {
        return refreshMutex.withLock {
            val current = load() ?: return@withLock null
            if (!current.credential.shouldRefresh()) return@withLock current
            val replacement = try {
                refresh(current.clientId, current.credential)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@withLock null
            }
            synchronized(lock) {
                val latest = load()?.takeIf { it.credential.accessToken == current.credential.accessToken }
                    ?: return@synchronized null
                save(latest.copy(credential = replacement))
                load()
            }
        }
    }

    suspend fun toolProvider(cancellation: ChatRequestCancellation): ToolProvider? = withContext(Dispatchers.IO) {
        val installation = current()?.takeIf { it.usable() } ?: return@withContext null
        TodoistMcpToolProvider(installation.tools) { name, arguments ->
            withContext(Dispatchers.IO) {
                val latest = current()?.takeIf { it.usable() } ?: error(tr("Todoist 集成已关闭或登录已失效"))
                TodoistMcpClient(latest.credential.accessToken, cancellation = cancellation).callTool(name, arguments)
            }
        }
    }

    companion object {
        private const val MAX_STORE_BYTES = 4 * 1024 * 1024L
        private const val KEY_ALIAS = "irio_mcp_todoist"
        private val lock = Any()
        private val refreshMutex = Mutex()
        fun of(context: Context): TodoistMcpStore = TodoistMcpStore(
            File(context.noBackupFilesDir, "mcp/todoist.json"), secretKey = {
                val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                (keyStore.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
                    init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
                }.generateKey()
            })
    }
}

internal class TodoistMcpClient(
    private val token: String,
    private val endpoint: HttpUrl = "https://ai.todoist.net/mcp".toHttpUrl(),
    private val cancellation: ChatRequestCancellation = ChatRequestCancellation(),
    private val http: OkHttpClient = client,
) {
    private var nextId = 0L
    private var sessionId: String? = null
    private var protocolVersion: String? = null

    fun listTools(): List<McpTool> {
        initialize()
        val tools = linkedMapOf<String, McpTool>()
        var cursor = ""
        val seen = mutableSetOf<String>()
        do {
            val params = JSONObject().apply { if (cursor.isNotBlank()) put("cursor", cursor) }
            val result = request("tools/list", params)
            val array = result.getJSONArray("tools")
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val name = item.getString("name")
                require(name.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { tr("Todoist MCP 工具名称无效") }
                tools[name] = McpTool(name, item.optString("description"), item.getJSONObject("inputSchema"))
            }
            cursor = result.optString("nextCursor").takeUnless { it == "null" }.orEmpty()
            if (cursor.isNotBlank() && (!seen.add(cursor) || seen.size > 100)) throw IOException(tr("Todoist MCP 工具列表分页无效"))
        } while (cursor.isNotBlank())
        if (tools.isEmpty()) throw IOException(tr("Todoist MCP 未提供工具"))
        return tools.values.toList()
    }

    fun callTool(name: String, arguments: JSONObject): String {
        initialize()
        val result = request("tools/call", JSONObject().put("name", name).put("arguments", arguments))
        return buildString {
            if (result.optBoolean("isError")) append("Todoist MCP tool error:\n")
            val content = result.optJSONArray("content") ?: JSONArray()
            for (i in 0 until content.length()) {
                val item = content.optJSONObject(i) ?: continue
                when (item.optString("type")) {
                    "text" -> append(item.optString("text"))
                    "resource" -> append(item.optJSONObject("resource")?.optString("text").orEmpty())
                    else -> append("[${item.optString("type")} content omitted]")
                }
                append('\n')
            }
            result.optJSONObject("structuredContent")?.let { append(it.toString()) }
        }.replace(token, "[redacted]").let { if (it.length > MAX_RESULT_CHARS) it.take(MAX_RESULT_CHARS) + "\n[Todoist MCP result truncated]" else it }
    }

    private fun initialize() {
        if (protocolVersion != null) return
        val result = request("initialize", JSONObject().put("protocolVersion", "2025-06-18")
            .put("capabilities", JSONObject()).put("clientInfo", JSONObject().put("name", "Irio").put("version", "0.9.3")))
        val negotiated = result.getString("protocolVersion")
        if (negotiated !in setOf("2025-06-18", "2025-03-26")) throw IOException(tr("Todoist MCP 协议版本不兼容"))
        if (result.optJSONObject("capabilities")?.has("tools") != true) throw IOException(tr("Todoist MCP 未提供工具"))
        protocolVersion = negotiated
        post(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"), null)
    }

    private fun request(method: String, params: JSONObject): JSONObject {
        val id = ++nextId
        val envelope = post(JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params), id)
            ?: throw IOException(tr("Todoist MCP 响应无效"))
        if (envelope.has("error")) throw IOException(tr("Todoist MCP 请求失败"))
        return envelope.getJSONObject("result")
    }

    private fun post(message: JSONObject, id: Long?): JSONObject? {
        val builder = Request.Builder().url(endpoint).header("Authorization", "Bearer $token")
            .header("Accept", "application/json, text/event-stream")
            .post(message.toString().toRequestBody("application/json".toMediaType()))
        sessionId?.let { builder.header("Mcp-Session-Id", it) }
        protocolVersion?.let { builder.header("MCP-Protocol-Version", it) }
        val call = http.newCall(builder.build())
        cancellation.attach(call)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException(when (response.code) {
                    401, 403 -> tr("Todoist 登录已失效或权限不足，请重新连接")
                    else -> tr("Todoist MCP 连接失败（HTTP %d）").format(response.code)
                })
                if (message.optString("method") == "initialize") sessionId = response.header("Mcp-Session-Id")
                if (id == null) return null
                val body = response.body ?: throw IOException(tr("Todoist MCP 响应无效"))
                val envelope = if (response.header("Content-Type").orEmpty().startsWith("text/event-stream")) {
                    val source = body.source()
                    val data = StringBuilder()
                    var consumed = 0L
                    var found: JSONObject? = null
                    while (!source.exhausted()) {
                        val line = source.readUtf8LineStrict(limit = MAX_RESPONSE_BYTES)
                        consumed += line.length + 1
                        if (consumed > MAX_RESPONSE_BYTES) throw IOException(tr("Todoist MCP 响应过大"))
                        if (line.isEmpty() && data.isNotEmpty()) {
                            val event = JSONObject(data.toString()); data.setLength(0)
                            if (event.optLong("id", -1) == id && (event.has("result") || event.has("error"))) { found = event; break }
                        } else if (line.startsWith("data:")) {
                            if (data.isNotEmpty()) data.append('\n')
                            data.append(line.substring(5).removePrefix(" "))
                        }
                    }
                    found ?: throw IOException(tr("Todoist MCP 响应无效"))
                } else {
                    val bytes = body.byteStream().use { it.readNBytes(MAX_RESPONSE_BYTES.toInt() + 1) }
                    if (bytes.size > MAX_RESPONSE_BYTES) throw IOException(tr("Todoist MCP 响应过大"))
                    JSONObject(bytes.toString(Charsets.UTF_8))
                }
                if (envelope.optString("jsonrpc") != "2.0" || envelope.optLong("id", -1) != id) throw IOException(tr("Todoist MCP 响应无效"))
                return envelope
            }
        } finally { cancellation.detach(call) }
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 4L * 1024 * 1024
        private const val MAX_RESULT_CHARS = 32_000
        private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    }
}

internal class TodoistMcpToolProvider(
    private val tools: List<McpTool>,
    private val invoke: suspend (String, JSONObject) -> String,
) : ToolProvider {
    override val id = "todoist-mcp"
    override fun definitions(protocol: ProviderProtocol): List<JSONObject> = tools.map { tool ->
        val name = PREFIX + tool.name
        val schema = JSONObject(tool.inputSchema.toString())
        when (protocol) {
            ProviderProtocol.CHAT_COMPLETIONS -> JSONObject().put("type", "function").put("function",
                JSONObject().put("name", name).put("description", tool.description).put("parameters", schema))
            ProviderProtocol.RESPONSES -> JSONObject().put("type", "function").put("name", name)
                .put("description", tool.description).put("parameters", schema).put("strict", false)
            ProviderProtocol.ANTHROPIC -> JSONObject().put("name", name).put("description", tool.description).put("input_schema", schema)
            ProviderProtocol.GEMINI -> GeminiProtocol.declaration(name, tool.description, schema)
            ProviderProtocol.GEMINI_INTERACTIONS -> GeminiInteractionsProtocol.declaration(name, tool.description, schema)
        }
    }

    override fun systemInstructions(enabledTools: Set<String>): String? = tools
        .filter { PREFIX + it.name in enabledTools }.takeIf { it.isNotEmpty() }?.let { selected ->
            "Todoist tools are available through Irio's configured Todoist integration. Available tool names: " +
                selected.joinToString(", ") { PREFIX + it.name } +
                ". For relevant Todoist tasks, use the listed tools and report permission or connection failures truthfully. " +
                "Irio handles authentication; do not ask the user to provide a token in chat."
        }

    override fun handles(name: String): Boolean = tools.any { PREFIX + it.name == name }

    override suspend fun execute(call: ChatToolCall, context: ToolExecutionContext): ToolExecutionResult {
        if (context.cancellation.isCancelled()) return ToolExecutionResult(tr("Todoist 工具已停止"))
        require(handles(call.name)) { "Unknown Todoist MCP tool" }
        val args = runCatching { JSONObject(call.arguments) }.getOrNull()
            ?: return ToolExecutionResult(tr("Todoist 工具参数无效"))
        context.onUpdate(ChatStreamUpdate(toolActive = true, toolName = call.name))
        var result = ""
        try {
            result = invoke(call.name.removePrefix(PREFIX), args)
            return ToolExecutionResult(result)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            result = if (context.cancellation.isCancelled()) tr("Todoist 工具已停止") else tr("Todoist 工具执行失败，请检查登录和权限")
            return ToolExecutionResult(result)
        } finally {
            context.onUpdate(ChatStreamUpdate(toolActive = false, toolName = call.name, toolContent = result))
        }
    }

    companion object {
        const val PREFIX = "todoist_mcp_"

        private val knownToolTitles = mapOf(
            // Hosted MCP tools use plural names and hyphens; normalize separators for cached variants.
            "add_comments" to "添加 Todoist 评论",
            "add_filters" to "创建 Todoist 筛选器",
            "add_labels" to "创建 Todoist 标签",
            "add_projects" to "创建 Todoist 项目",
            "add_reminders" to "添加 Todoist 提醒",
            "add_sections" to "创建 Todoist 分区",
            "add_tasks" to "创建 Todoist 任务",
            "analyze_project_health" to "分析 Todoist 项目健康状况",
            "complete_tasks" to "完成 Todoist 任务",
            "delete_object" to "删除 Todoist 对象",
            "export_project_template" to "导出 Todoist 项目模板",
            "fetch" to "读取 Todoist 任务或项目内容",
            "fetch_object" to "读取 Todoist 对象详情",
            "find_activity" to "查询 Todoist 活动记录",
            "find_comments" to "查询 Todoist 评论",
            "find_completed_tasks" to "查询 Todoist 已完成任务",
            "find_filters" to "查询 Todoist 筛选器",
            "find_labels" to "查询 Todoist 标签",
            "find_project_collaborators" to "查询 Todoist 项目协作者",
            "find_reminders" to "查询 Todoist 提醒",
            "find_sections" to "搜索 Todoist 分区",
            "find_tasks_by_date" to "按日期查询 Todoist 任务",
            "get_overview" to "读取 Todoist 项目概览",
            "get_project_activity_stats" to "读取 Todoist 项目活动统计",
            "get_project_health" to "读取 Todoist 项目健康状况",
            "get_workspace_insights" to "读取 Todoist 工作区概况",
            "import_project_template" to "导入 Todoist 项目模板",
            "list_workspaces" to "读取 Todoist 工作区列表",
            "manage_assignments" to "调整 Todoist 任务分配",
            "project_management" to "归档或恢复 Todoist 项目",
            "project_move" to "移动 Todoist 项目",
            "reorder_objects" to "调整 Todoist 项目或分区顺序",
            "reschedule_tasks" to "调整 Todoist 任务日期",
            "uncomplete_tasks" to "重新打开 Todoist 任务",
            "update_comments" to "更新 Todoist 评论",
            "update_filters" to "更新 Todoist 筛选器",
            "update_labels" to "更新 Todoist 标签",
            "update_projects" to "更新 Todoist 项目",
            "update_reminders" to "更新 Todoist 提醒",
            "update_sections" to "更新 Todoist 分区",
            "update_tasks" to "更新 Todoist 任务",
            "user_info" to "读取 Todoist 用户资料",
            "view_attachment" to "查看 Todoist 评论附件",
            // Preserve labels for tools cached by older server versions.
            "quick_add" to "快速添加 Todoist 任务",
            "add_task" to "创建 Todoist 任务",
            "create_task" to "创建 Todoist 任务",
            "get_task" to "读取 Todoist 任务详情",
            "get_tasks" to "读取 Todoist 任务列表",
            "find_tasks" to "搜索 Todoist 任务",
            "search_tasks" to "搜索 Todoist 任务",
            "update_task" to "更新 Todoist 任务",
            "complete_task" to "完成 Todoist 任务",
            "reopen_task" to "重新打开 Todoist 任务",
            "delete_task" to "删除 Todoist 任务",
            "get_project" to "读取 Todoist 项目详情",
            "get_projects" to "读取 Todoist 项目列表",
            "find_projects" to "搜索 Todoist 项目",
            "create_project" to "创建 Todoist 项目",
            "update_project" to "更新 Todoist 项目",
            "delete_project" to "删除 Todoist 项目",
            "get_section" to "读取 Todoist 分区详情",
            "get_sections" to "读取 Todoist 分区列表",
            "create_section" to "创建 Todoist 分区",
            "update_section" to "更新 Todoist 分区",
            "delete_section" to "删除 Todoist 分区",
            "get_label" to "读取 Todoist 标签详情",
            "get_labels" to "读取 Todoist 标签列表",
            "create_label" to "创建 Todoist 标签",
            "update_label" to "更新 Todoist 标签",
            "delete_label" to "删除 Todoist 标签",
            "get_comments" to "读取 Todoist 评论列表",
            "create_comment" to "添加 Todoist 评论",
            "update_comment" to "更新 Todoist 评论",
            "delete_comment" to "删除 Todoist 评论",
            "get_user" to "读取 Todoist 用户资料",
            "get_user_info" to "读取 Todoist 用户资料",
            "get_productivity_stats" to "读取 Todoist 效率统计",
            "get_productivity_statistics" to "读取 Todoist 效率统计",
            "search" to "搜索 Todoist 任务和项目",
        )

        fun displayName(remoteName: String): String = progressLabel(PREFIX + remoteName, completed = false)

        fun progressLabel(name: String, completed: Boolean): String {
            val toolName = name.removePrefix(PREFIX)
            val operation = knownToolTitles[toolName.lowercase().replace('-', '_')]
                ?: return tr(if (completed) "已调用 Todoist 工具 %s" else "调用 Todoist 工具 %s").format(toolName)
            return tr(if (completed) "已$operation" else operation)
        }
    }
}
