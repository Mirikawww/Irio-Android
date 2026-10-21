package com.irio

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

internal data class McpTool(val name: String, val description: String, val inputSchema: JSONObject)

/** A remote tools/call failure, with already-redacted model-visible details. */
internal class McpToolFailure(val content: String) : Exception()

internal fun mcpToolFailed(result: JSONObject): Boolean {
    fun failedBatch(json: JSONObject): Boolean = json.optInt("failureCount") > 0 ||
        (json.optJSONArray("failures")?.length() ?: 0) > 0
    if (result.optBoolean("isError")) return true
    if (result.optJSONObject("structuredContent")?.let(::failedBatch) == true) return true
    val content = result.optJSONArray("content") ?: return false
    return (0 until content.length()).any { index ->
        val item = content.optJSONObject(index)
        item?.optString("type") == "text" &&
            runCatching { failedBatch(JSONObject(item.optString("text"))) }.getOrDefault(false)
    }
}

internal class GitHubMcpClient(
    private val token: String,
    private val endpoint: HttpUrl = "https://api.githubcopilot.com/mcp/".toHttpUrl(),
    private val readOnly: Boolean = false,
    private val cancellation: ChatRequestCancellation = ChatRequestCancellation(),
    private val http: OkHttpClient = client,
) {
    init { require(token.isNotBlank() && token.all { it.code in 33..126 }) { "Invalid GitHub credential" } }
    private var nextId = 0L
    private var sessionId: String? = null
    private var protocolVersion: String? = null

    fun listTools(): List<McpTool> {
        initialize()
        val tools = linkedMapOf<String, McpTool>()
        val cursors = mutableSetOf<String>()
        var cursor = ""
        do {
            val params = JSONObject().apply { if (cursor.isNotBlank()) put("cursor", cursor) }
            val result = request("tools/list", params)
            val array = result.getJSONArray("tools")
            for (index in 0 until array.length()) {
                val tool = array.getJSONObject(index)
                val name = tool.getString("name")
                require(name.matches(Regex("[A-Za-z0-9_-]{1,50}"))) { tr("GitHub MCP 工具名称无效") }
                tools[name] = McpTool(name, tool.optString("description").replace(token, "[redacted]"),
                    JSONObject(tool.getJSONObject("inputSchema").toString().replace(token, "[redacted]")))
            }
            cursor = result.optString("nextCursor").takeUnless { it == "null" }.orEmpty()
            if (cursor.isNotBlank() && (!cursors.add(cursor) || cursors.size > 100)) {
                throw IOException(tr("GitHub MCP 工具列表分页无效"))
            }
        } while (cursor.isNotBlank())
        if (tools.isEmpty()) throw IOException(tr("GitHub MCP 未提供工具"))
        return tools.values.toList()
    }

    fun callTool(name: String, arguments: JSONObject): String {
        initialize()
        val result = request("tools/call", JSONObject().put("name", name).put("arguments", arguments))
        val text = buildString {
            if (result.optBoolean("isError")) append("GitHub MCP tool error:\n")
            val content = result.optJSONArray("content") ?: JSONArray()
            for (index in 0 until content.length()) {
                val item = content.optJSONObject(index) ?: continue
                when (item.optString("type")) {
                    "text" -> append(item.optString("text"))
                    "resource" -> append(item.optJSONObject("resource")?.optString("text").orEmpty())
                    else -> append("[${item.optString("type")} content omitted]")
                }
                append('\n')
            }
            result.optJSONObject("structuredContent")?.let { append(it.toString()) }
        }
        // Never echo a credential even if a remote error or result happens to contain it.
        val safeText = text.replace(token, "[redacted]").let {
            if (it.length > 32_000) it.take(32_000) + "\n[GitHub MCP result truncated]" else it
        }
        if (mcpToolFailed(result)) throw McpToolFailure(safeText)
        return safeText
    }

    private fun initialize() {
        if (protocolVersion != null) return
        val result = request("initialize", JSONObject()
            .put("protocolVersion", "2025-06-18")
            .put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "Irio").put("version", "0.9.3")))
        val negotiated = result.getString("protocolVersion")
        if (negotiated !in setOf("2025-06-18", "2025-03-26")) {
            throw IOException(tr("GitHub MCP 协议版本不兼容"))
        }
        if (result.optJSONObject("capabilities")?.has("tools") != true) {
            throw IOException(tr("GitHub MCP 未提供工具"))
        }
        protocolVersion = negotiated
        post(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"), null)
    }

    private fun request(method: String, params: JSONObject): JSONObject {
        val id = ++nextId
        val envelope = post(JSONObject().put("jsonrpc", "2.0").put("id", id)
            .put("method", method).put("params", params), id)
            ?: throw IOException(tr("GitHub MCP 响应无效"))
        if (envelope.has("error")) throw IOException(tr("GitHub MCP 请求失败"))
        return envelope.getJSONObject("result")
    }

    private fun post(message: JSONObject, id: Long?): JSONObject? {
        val builder = Request.Builder().url(endpoint)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json, text/event-stream")
            .post(message.toString().toRequestBody("application/json".toMediaType()))
        sessionId?.let { builder.header("Mcp-Session-Id", it) }
        protocolVersion?.let { builder.header("MCP-Protocol-Version", it) }
        if (readOnly) builder.header("X-MCP-Readonly", "true")
        val call = http.newCall(builder.build())
        cancellation.attach(call)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException(when (response.code) {
                        401, 403 -> tr("GitHub 登录已失效或权限不足，请重新登录")
                        else -> tr("GitHub MCP 连接失败（HTTP %d）").format(response.code)
                    })
                }
                if (message.optString("method") == "initialize") {
                    sessionId = response.header("Mcp-Session-Id")
                }
                if (id == null) return null
                val body = response.body ?: throw IOException(tr("GitHub MCP 响应无效"))
                val result = if (response.header("Content-Type").orEmpty().startsWith("text/event-stream")) {
                    val source = body.source()
                    val data = StringBuilder()
                    var consumed = 0L
                    var envelope: JSONObject? = null
                    while (!source.exhausted()) {
                        val line = source.readUtf8LineStrict(limit = MAX_RESPONSE_BYTES)
                        consumed += line.length + 1
                        if (consumed > MAX_RESPONSE_BYTES) throw IOException(tr("GitHub MCP 响应过大"))
                        if (line.isEmpty()) {
                            if (data.isNotEmpty()) {
                                val event = JSONObject(data.toString())
                                data.setLength(0)
                                if (event.optLong("id", -1) == id && (event.has("result") || event.has("error"))) {
                                    envelope = event
                                    break
                                }
                            }
                        } else if (line.startsWith("data:")) {
                            if (data.isNotEmpty()) data.append('\n')
                            data.append(line.substring(5).removePrefix(" "))
                        }
                    }
                    envelope ?: throw IOException(tr("GitHub MCP 响应无效"))
                } else {
                    val bytes = body.byteStream().use { it.readNBytes(MAX_RESPONSE_BYTES.toInt() + 1) }
                    if (bytes.size > MAX_RESPONSE_BYTES) throw IOException(tr("GitHub MCP 响应过大"))
                    JSONObject(bytes.toString(Charsets.UTF_8))
                }
                if (result.optString("jsonrpc") != "2.0" || result.optLong("id", -1) != id) {
                    throw IOException(tr("GitHub MCP 响应无效"))
                }
                return result
            }
        } finally {
            cancellation.detach(call)
        }
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 4L * 1024 * 1024
        private val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).build()
    }
}
