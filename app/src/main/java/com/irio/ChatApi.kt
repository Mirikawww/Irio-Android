package com.irio

import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class ChatAttachment(
    val name: String,
    val mimeType: String,
    val filePath: String,
) {
    val isImage: Boolean get() = mimeType.startsWith("image/") || mimeType in IMAGE_MIME_TYPES
    val isVideo: Boolean get() = mimeType.startsWith("video/")
    val slotCost: Float get() = if (isImage || isVideo) 0.5f else 1f
    val size: Long get() = File(filePath).takeIf { it.exists() }?.length() ?: 0L
    fun bytes(): ByteArray = File(filePath).readBytes()
}

data class ChatToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

data class ChatRequestMessage(
    val role: String,
    val text: String = "",
    val attachments: List<ChatAttachment> = emptyList(),
    val thinking: String = "",
    val toolCalls: List<ChatToolCall> = emptyList(),
    val toolCallId: String = "",
    val toolName: String = "",
    /** 消息发送时间戳（毫秒），仅用于模型侧提示，不渲染到 UI。 */
    val createdAt: Long = 0L,
)

data class TokenUsage(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
) {
    val usedTokens: Int
        get() = totalTokens ?: listOfNotNull(promptTokens, completionTokens).sum()
}

data class ChatCompletionResult(
    val content: String = "",
    val thinking: String = "",
    val attachments: List<ChatAttachment> = emptyList(),
    val model: String = "",
    val error: String? = null,
    val stopped: Boolean = false,
    val toolCalls: List<ChatToolCall> = emptyList(),
    val searchPages: Int = 0,
    val usage: TokenUsage? = null,
)

data class ChatStreamUpdate(
    val delta: String = "",
    val thinkingDelta: String = "",
    val attachments: List<ChatAttachment> = emptyList(),
    val model: String = "",
    val done: Boolean = false,
    val error: String? = null,
    val toolCallDeltas: List<ToolCallDelta> = emptyList(),
    val finishReason: String = "",
    val searching: Boolean? = null,
    val searchPagesDelta: Int = 0,
    val searchContent: String = "",
    /** 本地工具（get_time 等）调用开始；false 表示结束。 */
    val toolActive: Boolean? = null,
    /** 本地工具名，配合 [toolActive] 给过程行显示「读取记忆 / 写入记忆」等。 */
    val toolName: String = "",
    /** 工具完成后展示在可展开详情中的结果内容。 */
    val toolContent: String = "",
    /** 工具调用之后的新一轮。UI 保留已输出正文，下一轮正文接在后面，不要清掉上一轮。 */
    val newRound: Boolean = false,
    val usage: TokenUsage? = null,
    /** 进行中的工具输出快照。非 null 时刷新当前工具事件的内容，不结束它。 */
    val toolOutput: String? = null,
)

data class ToolCallDelta(
    val index: Int = 0,
    val key: String = "",
    val id: String = "",
    val name: String = "",
    val argumentsDelta: String = "",
)

class MutableToolCall(
    var index: Int = 0,
    var id: String = "",
    var name: String = "",
    val arguments: StringBuilder = StringBuilder(),
)

class ChatRequestCancellation {
    private val connections = mutableSetOf<HttpURLConnection>()
    private val calls = mutableSetOf<Call>()
    private var cancelled = false

    @Synchronized
    fun attach(connection: HttpURLConnection) {
        if (cancelled) connection.disconnect() else connections.add(connection)
    }

    @Synchronized
    fun attach(call: Call) {
        if (cancelled) call.cancel() else calls.add(call)
    }

    @Synchronized
    fun cancel() {
        cancelled = true
        connections.forEach { it.disconnect() }
        calls.forEach { it.cancel() }
        connections.clear()
        calls.clear()
    }

    @Synchronized fun isCancelled(): Boolean = cancelled

    @Synchronized
    fun detach(connection: HttpURLConnection) { connections.remove(connection) }

    @Synchronized
    fun detach(call: Call) { calls.remove(call) }
}

object ChatApi {
    const val MAX_ATTACHMENT_SLOTS = 15f
    const val MAX_ATTACHMENT_BYTES_TOTAL = 5L * 1024 * 1024 * 1024

    suspend fun streamChat(
        provider: ProviderConfig,
        model: String,
        messages: List<ChatRequestMessage>,
        reasoningEffort: ReasoningEffort,
        fastMode: Boolean,
        webSearch: Boolean = false,
        searchProvider: SearchProvider = SearchProvider.TAVILY,
        searchApiKey: String = "",
        searchMaxResults: Int = searchProvider.maxResultsDefault,
        maxToolRounds: Int = ToolStore.MAX_ROUNDS_DEFAULT,
        systemPrompt: String? = null,
        imageGenModel: String = "",
        toolRegistry: ToolRegistry = ToolRegistry.default(),
        connectionScope: String = "",
        conversationId: String = "",
        cancellation: ChatRequestCancellation,
        onUpdate: suspend (ChatStreamUpdate) -> Unit,
        appContext: android.content.Context? = null,
    ): ChatCompletionResult {
        val history = messages.toMutableList()
        var searchPages = 0
        var lastUsage: TokenUsage? = null
        val rounds = ToolStore.clampMaxRounds(maxToolRounds)
        val includeTimestamps = appContext?.let { ToolStore.loadMessageTimestamps(it) } ?: true
        // Snapshot all runtime capabilities once per request. Only the evolving history is copied
        // between rounds; providers therefore observe one coherent request context.
        val toolContext = ToolExecutionContext(
            provider = provider,
            model = model,
            webSearch = webSearch,
            searchProvider = searchProvider,
            searchApiKey = searchApiKey,
            searchMaxResults = searchMaxResults,
            imageGenModel = imageGenModel,
            cancellation = cancellation,
            onUpdate = onUpdate,
            appContext = appContext,
            conversationId = conversationId,
        )
        repeat(rounds) { roundIndex ->
            if (cancellation.isCancelled()) return ChatCompletionResult(stopped = true, searchPages = searchPages)
            if (roundIndex > 0) {
                onUpdate(ChatStreamUpdate(newRound = true))
            }
            val turn = streamOnce(
                provider = provider,
                model = model,
                messages = if (includeTimestamps) history else history.map { it.copy(createdAt = 0L) },
                reasoningEffort = reasoningEffort,
                fastMode = fastMode,
                systemPrompt = systemPrompt,
                toolRegistry = toolRegistry,
                connectionScope = connectionScope,
                cancellation = cancellation,
                onUpdate = onUpdate,
            )
            if (turn.usage != null) lastUsage = turn.usage
            if (turn.error != null || turn.stopped || turn.toolCalls.isEmpty()) {
                return turn.copy(searchPages = searchPages, usage = turn.usage ?: lastUsage)
            }
            history += ChatRequestMessage(
                role = "assistant",
                text = turn.content,
                thinking = turn.thinking,
                toolCalls = turn.toolCalls,
            )
            turn.toolCalls.forEach { call ->
                if (cancellation.isCancelled()) return ChatCompletionResult(stopped = true, searchPages = searchPages)
                val result = try {
                    toolRegistry.execute(
                        call = call,
                        context = toolContext.copy(history = history.toList()),
                    )
                } catch (cancelled: CancellationException) {
                    if (cancellation.isCancelled()) return ChatCompletionResult(stopped = true, searchPages = searchPages)
                    throw cancelled
                }
                if (cancellation.isCancelled()) return ChatCompletionResult(stopped = true, searchPages = searchPages)
                searchPages += result.searchPages
                history += ChatRequestMessage(
                    role = "tool",
                    text = result.text,
                    toolCallId = call.id,
                    toolName = call.name,
                )
            }
        }
        return ChatCompletionResult(
            stopped = cancellation.isCancelled(),
            error = if (cancellation.isCancelled()) null else tr("工具轮次过多"),
            searchPages = searchPages,
        )
    }

    /** 非流式生成对话标题；失败返回 null（调用方回落 titleFrom 截断方案）。 */
    suspend fun generateTitle(
        provider: ProviderConfig,
        model: String,
        firstUserMessage: String,
        firstAssistantMessage: String,
        connectionScope: String = "",
    ): String? = withContext(Dispatchers.IO) {
        runCatching {
            val prompt = buildString {
                append(tr("根据以下对话为这个会话生成一个简短的中文标题，不超过12个字，"))
                append(tr("只输出标题本身，不要任何标点、引号或解释。\n\n用户："))
                append(firstUserMessage.take(500))
                append(tr("\n助手："))
                append(firstAssistantMessage.take(500))
            }
            val body = when (provider.protocol) {
                ProviderProtocol.RESPONSES -> JSONObject().apply {
                    put("model", model)
                    put("stream", false)
                    put(
                        "input",
                        JSONArray().put(
                            JSONObject().put("role", "user").put("content", prompt),
                        ),
                    )
                }
                ProviderProtocol.ANTHROPIC -> JSONObject().apply {
                    put("model", model)
                    put("stream", false)
                    put("max_tokens", 64)
                    put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
                }
                ProviderProtocol.CHAT_COMPLETIONS -> JSONObject().apply {
                    put("model", model)
                    put("stream", false)
                    put(
                        "messages",
                        JSONArray().put(
                            JSONObject().put("role", "user").put("content", prompt),
                        ),
                    )
                }
            }
            if (provider.protocol != ProviderProtocol.ANTHROPIC) {
                body.putConversationKey(connectionScope)
            }
            val builder = Request.Builder()
                .url(requestUrl(provider))
                .post(body.toString().toByteArray(Charsets.UTF_8).toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("Accept", "application/json")
                .isolateConversation(connectionScope)
            applyAuth(builder, provider)
            val call = IrioHttp.clientFor(connectionScope).newCall(builder.build())
            var response: okhttp3.Response? = null
            try {
                response = call.execute()
                val status = response.code
                if (status !in 200..299) return@withContext null
                val text = response.body?.string().orEmpty()
                parseNonStreamText(provider.protocol, text)
                    ?.trim()
                    ?.removeSurrounding("\"")
                    ?.removeSurrounding("《")
                    ?.removePrefix("《")?.removeSuffix("》")
                    ?.take(20)
                    ?.takeIf { it.isNotBlank() }
            } finally {
                response?.close()
            }
        }.getOrNull()
    }

    /**
     * 单独写交接记录。不带工具、搜索和生图。取消或失败返回 null，调用方仍按旧对话发送。
     */
    suspend fun summarizeContext(
        provider: ProviderConfig,
        model: String,
        systemPrompt: String,
        messages: List<ChatRequestMessage>,
        connectionScope: String = "",
        cancellation: ChatRequestCancellation = ChatRequestCancellation(),
    ): String? = withContext(Dispatchers.IO) {
        if (model.isBlank() || cancellation.isCancelled()) return@withContext null
        runCatching {
            val body = summaryRequestBody(provider.protocol, model, systemPrompt, messages, connectionScope)
            val builder = Request.Builder()
                .url(requestUrl(provider))
                .post(body.toByteArray(Charsets.UTF_8).toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("Accept", "application/json")
                .isolateConversation(connectionScope)
            applyAuth(builder, provider)
            val call = IrioHttp.clientFor(connectionScope).newCall(builder.build())
            cancellation.attach(call)
            var response: okhttp3.Response? = null
            try {
                response = call.execute()
                if (cancellation.isCancelled()) return@withContext null
                if (response.code !in 200..299) return@withContext null
                parseNonStreamText(provider.protocol, response.body?.string().orEmpty())
                    ?.let(::stripContextSummary)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            } finally {
                cancellation.detach(call)
                response?.close()
            }
        }.getOrNull()
    }

    private fun summaryRequestBody(
        protocol: ProviderProtocol,
        model: String,
        systemPrompt: String,
        messages: List<ChatRequestMessage>,
        conversationId: String,
    ): String {
        return when (protocol) {
            ProviderProtocol.CHAT_COMPLETIONS -> JSONObject().apply {
                put("model", model)
                put("stream", false)
                put("messages", JSONArray().apply {
                    if (systemPrompt.isNotBlank()) {
                        put(JSONObject().put("role", "system").put("content", systemPrompt))
                    }
                    messages.forEach { put(it.toChatCompletionsJson()) }
                })
                putConversationKey(conversationId)
            }.toString()
            ProviderProtocol.RESPONSES -> JSONObject().apply {
                put("model", model)
                put("stream", false)
                if (systemPrompt.isNotBlank()) put("instructions", systemPrompt)
                put("input", JSONArray().apply {
                    messages.forEach { message -> message.toResponsesItems().forEach(::put) }
                })
                putConversationKey(conversationId)
            }.toString()
            ProviderProtocol.ANTHROPIC -> JSONObject().apply {
                put("model", model)
                put("stream", false)
                put("max_tokens", 4096)
                if (systemPrompt.isNotBlank()) put("system", systemPrompt)
                put("messages", JSONArray().apply {
                    messages.forEach { put(it.toAnthropicJson()) }
                })
            }.toString()
        }
    }

    /** 非流式响应里取纯文本。 */
    private fun parseNonStreamText(protocol: ProviderProtocol, body: String): String? {
        return runCatching {
            val root = JSONObject(body)
            when (protocol) {
                ProviderProtocol.CHAT_COMPLETIONS -> {
                    val content = root.optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("message")
                    when (val raw = content?.opt("content")) {
                        is String -> raw
                        is JSONArray -> buildString {
                            for (index in 0 until raw.length()) {
                                append(ChatProtocol.presentString(raw.optJSONObject(index)?.opt("text")))
                            }
                        }
                        else -> null
                    }
                }
                ProviderProtocol.RESPONSES -> root.optJSONArray("output")
                    ?.let { output ->
                        buildString {
                            for (index in 0 until output.length()) {
                                val item = output.optJSONObject(index) ?: continue
                                if (item.optString("type") != "message") continue
                                val parts = item.optJSONArray("content") ?: continue
                                for (partIndex in 0 until parts.length()) {
                                    append(ChatProtocol.presentString(parts.optJSONObject(partIndex)?.opt("text")))
                                }
                            }
                        }.ifBlank { null }
                    }
                ProviderProtocol.ANTHROPIC -> root.optJSONArray("content")
                    ?.let { content ->
                        buildString {
                            for (index in 0 until content.length()) {
                                val block = content.optJSONObject(index) ?: continue
                                if (block.optString("type") == "text") append(block.optString("text"))
                            }
                        }.ifBlank { null }
                    }
            }
        }.getOrNull()
    }

    private suspend fun streamOnce(
        provider: ProviderConfig,
        model: String,
        messages: List<ChatRequestMessage>,
        reasoningEffort: ReasoningEffort,
        fastMode: Boolean,
        systemPrompt: String?,
        toolRegistry: ToolRegistry,
        connectionScope: String,
        cancellation: ChatRequestCancellation,
        onUpdate: suspend (ChatStreamUpdate) -> Unit,
        http1Retried: Boolean = false,
    ): ChatCompletionResult {
        var delivered = false
        val publish: suspend (ChatStreamUpdate) -> Unit = { update ->
            if (
                update.delta.isNotEmpty() ||
                update.thinkingDelta.isNotEmpty() ||
                update.toolCallDeltas.isNotEmpty() ||
                update.attachments.isNotEmpty()
            ) {
                delivered = true
            }
            onUpdate(update)
        }
        val bodyBytes = requestBody(
            provider.protocol, model, messages, reasoningEffort, fastMode, systemPrompt, toolRegistry,
            conversationId = connectionScope,
        ).toByteArray(Charsets.UTF_8)
        val request = try {
            val builder = Request.Builder()
                .url(requestUrl(provider))
                .post(bodyBytes.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache, no-store")
                .header("Pragma", "no-cache")
                .header("X-Request-ID", UUID.randomUUID().toString())
                .isolateConversation(connectionScope)
            applyAuth(builder, provider)
            builder.build()
        } catch (_: Exception) {
            return ChatCompletionResult(error = tr("端点 URL 无效"))
        }
        val call = IrioHttp.clientFor(connectionScope).newCall(request)
        cancellation.attach(call)
        var response: okhttp3.Response? = null

        return try {
            if (cancellation.isCancelled()) throw CancellationException(tr("请求已停止"))
            response = call.execute()
            val status = response.code
            if (cancellation.isCancelled()) throw CancellationException(tr("请求已停止"))
            if (status !in 200..299) {
                val err = response.body?.string().orEmpty()
                ChatCompletionResult(error = apiError(err).ifBlank { tr("请求失败（HTTP %s）").format(status) })
            } else {
            var responseModel = model
            val streamedContent = StringBuilder()
            val streamedThinking = StringBuilder()
            val streamedAttachments = linkedMapOf<String, ChatAttachment>()
            val toolCalls = linkedMapOf<String, MutableToolCall>()
            var anthropicBlockType = ""
            var anthropicToolIndex = -1
            var finishReason = ""
            var terminalUpdateSent = false
            var lastUsage: TokenUsage? = null
            response.body!!.byteStream().bufferedReader().use { reader ->
                val eventName = StringBuilder()
                val eventData = StringBuilder()
                suspend fun flush(): Boolean {
                    val payload = eventData.toString()
                    val name = eventName.toString()
                    eventData.setLength(0)
                    eventName.setLength(0)
                    if (payload.isBlank()) return false
                    val update = parseSse(
                        protocol = provider.protocol,
                        eventName = name,
                        payload = payload,
                        anthropicBlockType = { anthropicBlockType },
                        onAnthropicBlockType = { anthropicBlockType = it },
                        onAnthropicToolIndex = { anthropicToolIndex = it },
                        anthropicToolIndex = { anthropicToolIndex },
                    ) ?: return false
                    if (update.error != null) {
                        throw StreamFailedException(update.error)
                    }
                    if (update.model.isNotBlank()) responseModel = update.model
                    if (update.delta.isNotEmpty()) streamedContent.append(update.delta)
                    if (update.thinkingDelta.isNotEmpty()) streamedThinking.append(update.thinkingDelta)
                    update.attachments.forEach { attachment ->
                        streamedAttachments[attachmentKey(attachment)] = attachment
                    }
                    ChatProtocol.applyToolCallDeltas(toolCalls, update.toolCallDeltas)
                    if (update.finishReason.isNotBlank() && update.finishReason != "null") {
                        finishReason = update.finishReason
                    }
                    if (update.usage != null) lastUsage = update.usage
                    val completedToolCount = ChatProtocol.completedToolCalls(toolCalls).size
                    val shouldEnd = ChatProtocol.shouldEndStream(
                        done = update.done,
                        finishReason = finishReason,
                        completedToolCount = completedToolCount,
                    )
                    if (shouldEnd) terminalUpdateSent = true
                    // 单轮 SSE 结束不等于整个请求结束：工具调用后还会继续下一轮。
                    // 只有没有已完成工具调用时，done 才能让 UI 进入最终完成态。
                    publish(
                        update.copy(
                            model = update.model.ifBlank { responseModel },
                            done = update.done && completedToolCount == 0,
                            usage = update.usage ?: lastUsage,
                        )
                    )
                    return shouldEnd
                }
                while (true) {
                    val line = reader.readLine() ?: break
                    if (cancellation.isCancelled()) throw CancellationException(tr("请求已停止"))
                    when {
                        line.isEmpty() -> if (flush()) break
                        line.startsWith("event:") -> {
                            eventName.setLength(0)
                            eventName.append(line.removePrefix("event:").trim())
                        }
                        line.startsWith("data:") -> {
                            val chunk = line.removePrefix("data:").trim()
                            if (eventData.isNotEmpty()) eventData.append('\n')
                            eventData.append(chunk)
                        }
                    }
                }
                if (eventData.isNotEmpty()) flush()
                if (!terminalUpdateSent) publish(ChatStreamUpdate(done = true, model = responseModel, usage = lastUsage))
            }
            if (cancellation.isCancelled()) throw CancellationException(tr("请求已停止"))
            val completedTools = ChatProtocol.completedToolCalls(toolCalls)
            if (cancellation.isCancelled()) throw CancellationException(tr("请求已停止"))
            val wantsTools = finishReason == "tool_calls" ||
                finishReason == "tool_use" ||
                completedTools.isNotEmpty()
            when {
                wantsTools && completedTools.isNotEmpty() -> ChatCompletionResult(
                    content = streamedContent.toString(),
                    thinking = streamedThinking.toString(),
                    attachments = streamedAttachments.values.toList(),
                    model = responseModel,
                    toolCalls = completedTools,
                    usage = lastUsage,
                )
                streamedContent.isBlank() &&
                    streamedAttachments.isEmpty() &&
                    streamedThinking.isBlank() &&
                    completedTools.isEmpty() -> ChatCompletionResult(error = tr("端点返回了空回复"))
                else -> ChatCompletionResult(
                    content = streamedContent.toString(),
                    thinking = streamedThinking.toString(),
                    attachments = streamedAttachments.values.toList(),
                    model = responseModel,
                    toolCalls = completedTools,
                    usage = lastUsage,
                )
            }
            }
        } catch (failed: StreamFailedException) {
            if (cancellation.isCancelled()) ChatCompletionResult(stopped = true)
            else ChatCompletionResult(error = failed.message)
        } catch (cancelled: CancellationException) {
            if (cancellation.isCancelled()) ChatCompletionResult(stopped = true) else throw cancelled
        } catch (error: Exception) {
            if (cancellation.isCancelled() || currentCoroutineContext()[Job]?.isCancelled == true) {
                ChatCompletionResult(stopped = true)
            } else if (!http1Retried && !delivered && isHttp2StreamReset(error)) {
                IrioHttp.downgradeToHttp1(connectionScope)
                streamOnce(
                    provider = provider,
                    model = model,
                    messages = messages,
                    reasoningEffort = reasoningEffort,
                    fastMode = fastMode,
                    systemPrompt = systemPrompt,
                    toolRegistry = toolRegistry,
                    connectionScope = connectionScope,
                    cancellation = cancellation,
                    onUpdate = onUpdate,
                    http1Retried = true,
                )
            } else {
                ChatCompletionResult(error = error.message ?: tr("无法连接到端点"))
            }
        } finally {
            cancellation.detach(call)
            response?.close()
        }
    }

    private fun isHttp2StreamReset(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current.message?.contains("stream was reset") == true) return true
            current = current.cause
        }
        return false
    }

    private fun parseSse(
        protocol: ProviderProtocol,
        eventName: String,
        payload: String,
        anthropicBlockType: () -> String,
        onAnthropicBlockType: (String) -> Unit,
        onAnthropicToolIndex: (Int) -> Unit,
        anthropicToolIndex: () -> Int,
    ): ChatStreamUpdate? {
        val data = payload.trim()
        if (data.isEmpty() || data.startsWith(":")) return null
        if (data == "[DONE]") return ChatStreamUpdate(done = true)
        return when (protocol) {
            ProviderProtocol.CHAT_COMPLETIONS -> ChatProtocol.parseChatCompletions(data)
            ProviderProtocol.RESPONSES -> parseResponses(data)
            ProviderProtocol.ANTHROPIC -> parseAnthropic(
                eventName,
                data,
                anthropicBlockType,
                onAnthropicBlockType,
                onAnthropicToolIndex,
                anthropicToolIndex,
            )
        }
    }

    private fun parseResponses(data: String): ChatStreamUpdate? = runCatching {
        val event = JSONObject(data)
        when (event.optString("type")) {
            "response.output_text.delta" -> ChatStreamUpdate(delta = event.optString("delta"))
            "response.reasoning_summary_text.delta",
            "response.reasoning_text.delta" -> ChatStreamUpdate(thinkingDelta = event.optString("delta"))
            "response.output_item.added" -> {
                val item = event.optJSONObject("item") ?: return@runCatching null
                if (item.optString("type") != "function_call") return@runCatching null
                ChatStreamUpdate(
                    toolCallDeltas = listOf(
                        ToolCallDelta(
                            key = event.optString("item_id").ifBlank { item.optString("id") },
                            id = item.optString("call_id").ifBlank { item.optString("id") },
                            name = item.optString("name"),
                            argumentsDelta = item.optString("arguments"),
                        )
                    )
                )
            }
            "response.function_call_arguments.delta" -> ChatStreamUpdate(
                toolCallDeltas = listOf(
                    ToolCallDelta(
                        key = event.optString("item_id"),
                        argumentsDelta = event.optString("delta"),
                    )
                )
            )
            "response.completed" -> ChatStreamUpdate(
                done = true,
                model = event.optJSONObject("response")?.optString("model").orEmpty(),
                usage = ChatProtocol.usageFromResponses(event.optJSONObject("response"))
                    ?: ChatProtocol.parseUsage(event.optJSONObject("usage")),
            )
            "response.failed", "error" -> ChatStreamUpdate(
                error = event.optJSONObject("response")?.optJSONObject("error")?.optString("message")
                    ?: event.optString("message").ifBlank { tr("请求失败") },
            )
            else -> {
                val delta = event.optString("delta")
                if (delta.isNotBlank() && event.optString("type").contains("output_text")) {
                    ChatStreamUpdate(delta = delta)
                } else {
                    null
                }
            }
        }
    }.getOrNull()

    private fun parseAnthropic(
        eventName: String,
        data: String,
        anthropicBlockType: () -> String,
        onAnthropicBlockType: (String) -> Unit,
        onAnthropicToolIndex: (Int) -> Unit,
        anthropicToolIndex: () -> Int,
    ): ChatStreamUpdate? = runCatching {
        val event = JSONObject(data)
        val type = event.optString("type").ifBlank { eventName }
        when (type) {
            "content_block_start" -> {
                val block = event.optJSONObject("content_block")
                val blockType = block?.optString("type").orEmpty()
                onAnthropicBlockType(blockType)
                val index = event.optInt("index", 0)
                if (blockType == "tool_use") {
                    onAnthropicToolIndex(index)
                    ChatStreamUpdate(
                        toolCallDeltas = listOf(
                            ToolCallDelta(
                                index = index,
                                key = "a$index",
                                id = block?.optString("id").orEmpty(),
                                name = block?.optString("name").orEmpty(),
                                argumentsDelta = block?.opt("input")?.takeIf { it != JSONObject.NULL }?.toString().orEmpty()
                                    .takeIf { it != "{}" }
                                    .orEmpty(),
                            )
                        )
                    )
                } else {
                    val thinking = block?.optString("thinking").orEmpty()
                    val text = block?.optString("text").orEmpty()
                    ChatStreamUpdate(delta = text, thinkingDelta = thinking)
                }
            }
            "content_block_delta" -> {
                val delta = event.optJSONObject("delta") ?: JSONObject()
                when (delta.optString("type")) {
                    "thinking_delta" -> ChatStreamUpdate(thinkingDelta = delta.optString("thinking"))
                    "text_delta" -> ChatStreamUpdate(delta = delta.optString("text"))
                    "input_json_delta" -> ChatStreamUpdate(
                        toolCallDeltas = listOf(
                            ToolCallDelta(
                                index = event.optInt("index", anthropicToolIndex()),
                                key = "a${event.optInt("index", anthropicToolIndex())}",
                                argumentsDelta = delta.optString("partial_json"),
                            )
                        )
                    )
                    else -> when (anthropicBlockType()) {
                        "thinking" -> ChatStreamUpdate(thinkingDelta = delta.optString("thinking").ifBlank { delta.optString("text") })
                        "tool_use" -> ChatStreamUpdate(
                            toolCallDeltas = listOf(
                                ToolCallDelta(
                                    index = event.optInt("index", anthropicToolIndex()),
                                    key = "a${event.optInt("index", anthropicToolIndex())}",
                                    argumentsDelta = delta.optString("partial_json"),
                                )
                            )
                        )
                        else -> ChatStreamUpdate(delta = delta.optString("text"))
                    }
                }
            }
            "message_start" -> ChatStreamUpdate(
                model = event.optJSONObject("message")?.optString("model").orEmpty(),
                usage = ChatProtocol.parseUsage(event.optJSONObject("message")?.optJSONObject("usage")),
            )
            "message_delta" -> ChatStreamUpdate(
                finishReason = event.optJSONObject("delta")?.optString("stop_reason").orEmpty(),
                usage = ChatProtocol.parseUsage(event.optJSONObject("usage")),
            )
            "message_stop" -> ChatStreamUpdate(done = true)
            "error" -> ChatStreamUpdate(
                error = event.optJSONObject("error")?.optString("message") ?: event.optString("message"),
            )
            else -> null
        }
    }.getOrNull()

    /**
     * 幻灵（sub2api）在请求没带会话号时，用 tools + instructions 的稳定前缀生成同一个
     * x-grok-conv-id。每条新对话的工具和记忆都一样，所以上游把它们黏成一条对话。
     * 键放在请求体里：带下划线的 session_id 头会被 nginx 丢掉。
     */
    private fun JSONObject.putConversationKey(conversationId: String) {
        if (conversationId.isNotEmpty()) put("prompt_cache_key", conversationId)
    }

    private fun okhttp3.Request.Builder.isolateConversation(conversationId: String): okhttp3.Request.Builder {
        if (conversationId.isEmpty()) return this
        return header("X-Grok-Conv-Id", conversationId)
    }

    private fun requestBody(
        protocol: ProviderProtocol,
        model: String,
        messages: List<ChatRequestMessage>,
        reasoningEffort: ReasoningEffort,
        fastMode: Boolean,
        systemPrompt: String?,
        toolRegistry: ToolRegistry,
        conversationId: String = "",
    ): String {
        fun toolsArray(): JSONArray? {
            val tools = JSONArray()
            // Keep the complete tool surface stable across turns; runtime settings affect execution.
            toolRegistry.schemas(protocol).forEach(tools::put)
            return if (tools.length() > 0) tools else null
        }
        return when (protocol) {
        ProviderProtocol.CHAT_COMPLETIONS -> JSONObject().apply {
            put("model", model)
            put("stream", true)
            put("stream_options", JSONObject().put("include_usage", true))
            ChatProtocol.putReasoning(this, ProviderProtocol.CHAT_COMPLETIONS, reasoningEffort)
            if (fastMode) put("service_tier", "fast")
            toolsArray()?.let { put("tools", it) }
            put("messages", JSONArray().apply {
                if (!systemPrompt.isNullOrBlank()) {
                    put(JSONObject().put("role", "system").put("content", systemPrompt))
                }
                messages.forEach { put(it.toChatCompletionsJson()) }
            })
            putConversationKey(conversationId)
        }.toString()
        ProviderProtocol.RESPONSES -> JSONObject().apply {
            put("model", model)
            put("stream", true)
            put("stream_options", JSONObject().put("include_usage", true))
            ChatProtocol.putReasoning(this, ProviderProtocol.RESPONSES, reasoningEffort)
            if (fastMode) put("service_tier", "fast")
            toolsArray()?.let { put("tools", it) }
            if (!systemPrompt.isNullOrBlank()) put("instructions", systemPrompt)
            put("input", JSONArray().apply {
                messages.forEach { message ->
                    message.toResponsesItems().forEach(::put)
                }
            })
            putConversationKey(conversationId)
        }.toString()
        ProviderProtocol.ANTHROPIC -> JSONObject().apply {
            put("model", model)
            put("stream", true)
            put("max_tokens", anthropicMaxTokens(reasoningEffort))
            ChatProtocol.putReasoning(this, ProviderProtocol.ANTHROPIC, reasoningEffort, model)
            toolsArray()?.let { put("tools", it) }
            if (!systemPrompt.isNullOrBlank()) put("system", systemPrompt)
            put("messages", JSONArray().apply {
                messages.filter { it.role == "user" || it.role == "assistant" || it.role == "tool" }.forEach {
                    put(it.toAnthropicJson())
                }
            })
        }.toString()
        }
    }

    private fun ChatRequestMessage.toChatCompletionsJson(): JSONObject {
        val stampedText = text.withTimestampPrefix(createdAt, role == "user")
        return JSONObject().apply {
            when {
                role == "tool" -> {
                    put("role", "tool")
                    put("tool_call_id", toolCallId)
                    put("content", text)
                }
                toolCalls.isNotEmpty() -> {
                    put("role", "assistant")
                    if (stampedText.isNotBlank()) put("content", stampedText) else put("content", JSONObject.NULL)
                    put("tool_calls", JSONArray().apply {
                        toolCalls.forEach { call ->
                            put(
                                JSONObject()
                                    .put("id", call.id)
                                    .put("type", "function")
                                    .put(
                                        "function",
                                        JSONObject()
                                            .put("name", call.name)
                                            .put("arguments", call.arguments),
                                    )
                            )
                        }
                    })
                }
                else -> {
                    put("role", role)
                    val parts = openAiParts(text = true, overrideText = stampedText)
                    if (parts.size == 1 && parts[0].optString("type") == "text") {
                        put("content", parts[0].optString("text"))
                    } else {
                        put("content", JSONArray().apply { parts.forEach(::put) })
                    }
                }
            }
        }
    }

    private fun ChatRequestMessage.toResponsesItems(): List<JSONObject> {
        return when {
            role == "tool" -> listOf(
                JSONObject()
                    .put("type", "function_call_output")
                    .put("call_id", toolCallId)
                    .put("output", text),
            )
            toolCalls.isNotEmpty() -> toolCalls.map { call ->
                JSONObject()
                    .put("type", "function_call")
                    .put("call_id", call.id)
                    .put("name", call.name)
                    .put("arguments", call.arguments)
            }
            else -> listOf(toResponsesJson())
        }
    }

    private fun ChatRequestMessage.toResponsesJson(): JSONObject {
        return JSONObject().apply {
            put("role", role)
            put("content", JSONArray().apply {
                openAiParts(text = false, overrideText = text.withTimestampPrefix(createdAt, role == "user")).forEach(::put)
            })
        }
    }

    private fun ChatRequestMessage.toAnthropicJson(): JSONObject {
        val stampedText = text.withTimestampPrefix(createdAt, role == "user")
        return JSONObject().apply {
            when {
                role == "tool" -> {
                    put("role", "user")
                    put(
                        "content",
                        JSONArray().put(
                            JSONObject()
                                .put("type", "tool_result")
                                .put("tool_use_id", toolCallId)
                                .put("content", text),
                        ),
                    )
                }
                toolCalls.isNotEmpty() -> {
                    put("role", "assistant")
                    val parts = buildList {
                        if (thinking.isNotBlank()) {
                            add(JSONObject().put("type", "thinking").put("thinking", thinking))
                        }
                        if (stampedText.isNotBlank()) {
                            add(JSONObject().put("type", "text").put("text", stampedText))
                        }
                        toolCalls.forEach { call ->
                            add(
                                JSONObject()
                                    .put("type", "tool_use")
                                    .put("id", call.id)
                                    .put("name", call.name)
                                    .put("input", runCatching { JSONObject(call.arguments) }.getOrDefault(JSONObject())),
                            )
                        }
                    }
                    put("content", JSONArray().apply { parts.forEach(::put) })
                }
                else -> {
                    put("role", if (role == "user") "user" else "assistant")
                    val parts = buildList {
                        if (thinking.isNotBlank() && role != "user") {
                            add(JSONObject().put("type", "thinking").put("thinking", thinking))
                        }
                        if (stampedText.isNotBlank()) {
                            add(JSONObject().put("type", "text").put("text", stampedText))
                        }
                        attachments.forEach { attachment ->
                            add(attachment.toAnthropicPart())
                        }
                    }.ifEmpty { listOf(JSONObject().put("type", "text").put("text", "")) }
                    put("content", JSONArray().apply { parts.forEach(::put) })
                }
            }
        }
    }

    private fun ChatRequestMessage.openAiParts(text: Boolean, overrideText: String? = null): List<JSONObject> {
        val textType = if (text) "text" else "input_text"
        val imageType = if (text) "image_url" else "input_image"
        val fileType = if (text) "file" else "input_file"
        val bodyText = overrideText ?: this@openAiParts.text
        val parts = buildList {
            if (bodyText.isNotBlank()) {
                add(JSONObject().put("type", textType).put("text", bodyText))
            }
            attachments.forEach { attachment ->
                val dataUrl = dataUrl(attachment.mimeType, attachment.bytes())
                add(
                    if (attachment.isImage) {
                        if (text) {
                            JSONObject()
                                .put("type", imageType)
                                .put("image_url", JSONObject().put("url", dataUrl))
                        } else {
                            JSONObject().put("type", imageType).put("image_url", dataUrl)
                        }
                    } else {
                        val file = JSONObject()
                            .put("filename", attachment.name)
                            .put("file_data", dataUrl)
                        if (text) {
                            JSONObject().put("type", fileType).put("file", file)
                        } else {
                            file.put("type", fileType)
                        }
                    }
                )
            }
        }
        return parts.ifEmpty { listOf(JSONObject().put("type", textType).put("text", "")) }
    }

    private fun ChatAttachment.toAnthropicPart(): JSONObject {
        val encoded = Base64.encodeToString(bytes(), Base64.NO_WRAP)
        return if (isImage) {
            JSONObject()
                .put("type", "image")
                .put(
                    "source",
                    JSONObject()
                        .put("type", "base64")
                        .put("media_type", mimeType)
                        .put("data", encoded),
                )
        } else {
            JSONObject()
                .put("type", "document")
                .put("title", name)
                .put(
                    "source",
                    JSONObject()
                        .put("type", "base64")
                        .put("media_type", mimeType.ifBlank { "application/pdf" })
                        .put("data", encoded),
                )
        }
    }

    private fun parseOpenAiContent(value: Any?): ParsedContent {
        return when (value) {
            is String -> ParsedContent(text = value)
            is JSONArray -> {
                val text = StringBuilder()
                val attachments = mutableListOf<ChatAttachment>()
                for (index in 0 until value.length()) {
                    val part = value.optJSONObject(index) ?: continue
                    when (part.optString("type")) {
                        "text", "output_text", "input_text" -> text.append(part.optString("text"))
                        "image_url", "input_image", "output_image" -> {
                            val url = part.optJSONObject("image_url")?.optString("url")
                                ?: part.optString("image_url").ifBlank { part.optString("url") }
                            attachmentFromDataUrl(url, "image")?.let(attachments::add)
                        }
                        "file", "input_file" -> {
                            val file = part.optJSONObject("file") ?: part
                            val filename = file.optString("filename").ifBlank { "file" }
                            attachmentFromDataUrl(file.optString("file_data"), filename)?.let(attachments::add)
                        }
                    }
                }
                ParsedContent(text = text.toString(), attachments = attachments)
            }
            else -> ParsedContent()
        }
    }

    private data class ParsedContent(
        val text: String = "",
        val attachments: List<ChatAttachment> = emptyList(),
    )

    private fun parseImageList(images: JSONArray?): List<ChatAttachment> {
        if (images == null) return emptyList()
        return buildList {
            for (index in 0 until images.length()) {
                val item = images.optJSONObject(index) ?: continue
                val url = item.optString("url").ifBlank {
                    item.optJSONObject("image_url")?.optString("url").orEmpty()
                }
                attachmentFromDataUrl(url, "image")?.let(::add)
            }
        }
    }

    private fun attachmentFromDataUrl(value: String, fallbackName: String): ChatAttachment? {
        val dataUrl = value.trim()
        if (dataUrl.startsWith("http://", ignoreCase = true) ||
            dataUrl.startsWith("https://", ignoreCase = true)
        ) {
            return downloadRemoteAttachment(dataUrl, fallbackName)
        }
        if (!dataUrl.startsWith("data:", ignoreCase = true)) return null
        val comma = dataUrl.indexOf(',')
        if (comma <= 5) return null
        val header = dataUrl.substring(5, comma)
        val payload = dataUrl.substring(comma + 1)
        val mimeType = header.substringBefore(';', missingDelimiterValue = header)
            .ifBlank { "application/octet-stream" }
        val bytes = runCatching { Base64.decode(payload, Base64.DEFAULT) }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        val name = if (fallbackName.contains('.')) fallbackName else "$fallbackName${extensionFor(mimeType)}"
        return writeTempAttachment(name, mimeType, bytes)
    }

    private fun downloadRemoteAttachment(url: String, fallbackName: String): ChatAttachment? {
        val connection = runCatching { URL(url).openConnection() as HttpURLConnection }.getOrNull() ?: return null
        return try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            if (connection.responseCode !in 200..299) return null
            val mimeType = connection.contentType?.substringBefore(';')?.trim()?.lowercase()?.ifBlank { null }
                ?: "application/octet-stream"
            val rawName = url.substringAfterLast('/').substringBefore('?').ifBlank { fallbackName }
            val name = if (rawName.contains('.')) rawName else "$rawName${extensionFor(mimeType)}"
            val file = File.createTempFile("irio-in-", extensionFor(mimeType).ifBlank { ".bin" })
            connection.inputStream.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            if (!file.exists() || file.length() <= 0L || file.length() > MAX_ATTACHMENT_BYTES_TOTAL) {
                file.delete()
                return null
            }
            ChatAttachment(name = name, mimeType = mimeType, filePath = file.absolutePath)
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    /** 给用户消息文本加上仅模型可见的时间戳前缀。关闭「消息时间戳」时 createdAt 会被清成 0，这里直接跳过。模型回复不加前缀。 */
    internal fun stampMessageText(text: String, createdAt: Long, fromUser: Boolean): String {
        if (!fromUser || createdAt <= 0L) return text
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date(createdAt))
        return if (text.isBlank()) "[$stamp]" else "[$stamp] $text"
    }

    private fun String.withTimestampPrefix(createdAt: Long, fromUser: Boolean): String =
        stampMessageText(this, createdAt, fromUser)

    private fun requestUrl(provider: ProviderConfig): String = ProviderEndpoints.chatUrl(provider)

    private fun anthropicMaxTokens(effort: ReasoningEffort): Int = when (effort) {
        ReasoningEffort.XHIGH, ReasoningEffort.MAX -> 32_000
        ReasoningEffort.HIGH -> 16_000
        else -> 8_192
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

    private fun attachmentKey(attachment: ChatAttachment): String {
        return "${attachment.name}:${attachment.filePath}:${attachment.size}"
    }

    private fun writeTempAttachment(name: String, mimeType: String, bytes: ByteArray): ChatAttachment? {
        if (bytes.isEmpty()) return null
        val file = File.createTempFile("irio-in-", extensionFor(mimeType).ifBlank { ".bin" })
        file.writeBytes(bytes)
        return ChatAttachment(name = name, mimeType = mimeType, filePath = file.absolutePath)
    }

}

internal object WebSearchTool {
    const val NAME = "web_search"
    const val DESCRIPTION = "Search the live web. Call with {\"query\":\"...\"}."

    private fun parameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject().put(
                "query",
                JSONObject().put("type", "string").put("description", "Search query"),
            ),
        )
        .put("required", JSONArray().put("query"))
        .put("additionalProperties", false)

    fun schemaFor(protocol: ProviderProtocol): JSONObject = when (protocol) {
        ProviderProtocol.CHAT_COMPLETIONS -> JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", NAME)
                    .put("description", DESCRIPTION)
                    .put("parameters", parameters()),
            )
        ProviderProtocol.RESPONSES -> JSONObject()
            .put("type", "function")
            .put("name", NAME)
            .put("description", DESCRIPTION)
            .put("parameters", parameters())
            .put("strict", true)
        ProviderProtocol.ANTHROPIC -> JSONObject()
            .put("name", NAME)
            .put("description", DESCRIPTION)
            .put("input_schema", parameters())
    }
}

internal object WebFetchTool {
    const val NAME = "web_fetch"
    const val DESCRIPTION = "Fetch and read the main text of a web page. Call with {\"url\":\"https://...\"}."

    private fun parameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject().put(
            "url", JSONObject().put("type", "string").put("description", "The full URL to read"),
        ))
        .put("required", JSONArray().put("url"))
        .put("additionalProperties", false)

    fun schemaFor(protocol: ProviderProtocol): JSONObject = when (protocol) {
        ProviderProtocol.CHAT_COMPLETIONS -> JSONObject().put("type", "function").put(
            "function", JSONObject().put("name", NAME).put("description", DESCRIPTION).put("parameters", parameters()),
        )
        ProviderProtocol.RESPONSES -> JSONObject().put("type", "function").put("name", NAME)
            .put("description", DESCRIPTION).put("parameters", parameters()).put("strict", true)
        ProviderProtocol.ANTHROPIC -> JSONObject().put("name", NAME).put("description", DESCRIPTION)
            .put("input_schema", parameters())
    }
}

internal object ImageGenTool {
    const val NAME = "generate_image"
    /** 过程行内容标记，不是展示文案。空内容表示用户停止。 */
    const val STATUS_OK = "ok"
    const val STATUS_FAILED = "failed"
    const val DESCRIPTION =
        "Generate an image from a text prompt and deliver it to the user. " +
            "Call with {\"prompt\":\"detailed image description in English\"}. " +
            "Do NOT use this tool if you can already output images yourself."

    private fun parameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject().put(
                "prompt",
                JSONObject()
                    .put("type", "string")
                    .put("description", "Detailed image description, in English"),
            ),
        )
        .put("required", JSONArray().put("prompt"))
        .put("additionalProperties", false)

    fun schemaFor(protocol: ProviderProtocol): JSONObject = when (protocol) {
        ProviderProtocol.CHAT_COMPLETIONS -> JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", NAME)
                    .put("description", DESCRIPTION)
                    .put("parameters", parameters()),
            )
        ProviderProtocol.RESPONSES -> JSONObject()
            .put("type", "function")
            .put("name", NAME)
            .put("description", DESCRIPTION)
            .put("parameters", parameters())
            .put("strict", true)
        ProviderProtocol.ANTHROPIC -> JSONObject()
            .put("name", NAME)
            .put("description", DESCRIPTION)
            .put("input_schema", parameters())
    }
}

internal object TimeTool {
    const val NAME = "get_time"
    const val DESCRIPTION =
        "Get the current local date and time on the user's device. " +
            "Call this whenever the user asks about the current time, date, weekday, or " +
            "anything that depends on 'now'. No parameters needed."

    private fun parameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject())
        .put("required", JSONArray())
        .put("additionalProperties", false)

    fun schemaFor(protocol: ProviderProtocol): JSONObject = when (protocol) {
        ProviderProtocol.CHAT_COMPLETIONS -> JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", NAME)
                    .put("description", DESCRIPTION)
                    .put("parameters", parameters()),
            )
        ProviderProtocol.RESPONSES -> JSONObject()
            .put("type", "function")
            .put("name", NAME)
            .put("description", DESCRIPTION)
            .put("parameters", parameters())
            .put("strict", true)
        ProviderProtocol.ANTHROPIC -> JSONObject()
            .put("name", NAME)
            .put("description", DESCRIPTION)
            .put("input_schema", parameters())
    }

    /** 设备本地当前时间；一次调用返回完整描述，模型无需再追问。 */
    fun now(): String {
        val current = java.time.ZonedDateTime.now()
        val time = current.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
        if (ThemeStore.language == AppLanguage.EN) {
            val date = current.format(java.time.format.DateTimeFormatter.ofPattern("MMMM d, yyyy", java.util.Locale.ENGLISH))
            val weekday = current.format(java.time.format.DateTimeFormatter.ofPattern("EEEE", java.util.Locale.ENGLISH))
            return "Current time: $date ($weekday) $time, time zone ${current.zone.id}"
        }
        val date = current.format(java.time.format.DateTimeFormatter.ofPattern("yyyy年M月d日"))
        val weekday = when (current.dayOfWeek) {
            java.time.DayOfWeek.MONDAY -> "一"
            java.time.DayOfWeek.TUESDAY -> "二"
            java.time.DayOfWeek.WEDNESDAY -> "三"
            java.time.DayOfWeek.THURSDAY -> "四"
            java.time.DayOfWeek.FRIDAY -> "五"
            java.time.DayOfWeek.SATURDAY -> "六"
            java.time.DayOfWeek.SUNDAY -> "日"
        }
        return tr("当前时间：%s（星期%s）%s，时区 %s").format(date, weekday, time, current.zone.id)
    }
}

internal object MemoryTool {
    const val GET = "get_memory"
    const val UPDATE = "update_memory"

    private const val GET_DESCRIPTION =
        "Read the user's saved long-term memory. Call this when you need the current memory " +
            "contents before answering, or to check what is already stored. No parameters."
    private const val UPDATE_DESCRIPTION =
        "Append a new durable fact about the user to long-term memory. Use for lasting " +
            "preferences, identity, or constraints — not for the current conversation. " +
            "Call with {\"content\":\"one concise fact in the user's language\"}."

    fun liveLabel(name: String): String = when (name) {
        ImageGenTool.NAME -> tr("图片生成中")
        TimeTool.NAME -> tr("获取时间")
        LinuxShellTool.NAME -> tr("运行命令中")
        LinuxBackgroundTool.NAME -> tr("处理后台命令")
        WebFetchTool.NAME -> tr("浏览网页中")
        GET -> tr("读取记忆")
        UPDATE -> tr("写入记忆")
        else -> tr("调用工具")
    }

    fun doneLabel(name: String, content: String = ""): String = when (name) {
        ImageGenTool.NAME -> when (content) {
            ImageGenTool.STATUS_FAILED -> tr("生成图片失败")
            else -> tr("图片生成已停止")
        }
        TimeTool.NAME -> tr("已获取时间")
        LinuxShellTool.NAME -> tr("已运行命令")
        LinuxBackgroundTool.NAME -> tr("已处理后台命令")
        WebFetchTool.NAME -> tr("已浏览网页")
        GET -> tr("已读取记忆")
        UPDATE -> tr("已写入记忆")
        else -> tr("已调用工具")
    }

    fun getSchema(protocol: ProviderProtocol): JSONObject =
        functionSchema(protocol, GET, GET_DESCRIPTION, emptyParameters())

    fun updateSchema(protocol: ProviderProtocol): JSONObject =
        functionSchema(protocol, UPDATE, UPDATE_DESCRIPTION, contentParameters())

    fun read(context: android.content.Context?): String {
        if (context == null) return tr("记忆当前不可用。")
        if (!MemoryStore.loadEnabled(context)) return tr("记忆已关闭。")
        val content = MemoryStore.loadContent(context).trim()
        return if (content.isEmpty()) tr("当前没有已保存的记忆。") else tr("用户记忆：\n%s").format(content)
    }

    fun write(context: android.content.Context?, addition: String): String {
        if (context == null) return tr("记忆当前不可用，未能写入。")
        if (!MemoryStore.loadEnabled(context)) return tr("记忆已关闭，未能写入。")
        val trimmed = addition.trim()
        if (trimmed.isEmpty()) return tr("没有可写入的内容。")
        val stored = MemoryStore.appendContent(context, trimmed)
        return tr("已写入记忆。当前记忆：\n%s").format(stored)
    }

    private fun emptyParameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject())
        .put("required", JSONArray())
        .put("additionalProperties", false)

    private fun contentParameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject().put(
                "content",
                JSONObject()
                    .put("type", "string")
                    .put("description", "One concise durable fact to remember about the user"),
            ),
        )
        .put("required", JSONArray().put("content"))
        .put("additionalProperties", false)

    private fun functionSchema(
        protocol: ProviderProtocol,
        name: String,
        description: String,
        parameters: JSONObject,
    ): JSONObject = when (protocol) {
        ProviderProtocol.CHAT_COMPLETIONS -> JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put("description", description)
                    .put("parameters", parameters),
            )
        ProviderProtocol.RESPONSES -> JSONObject()
            .put("type", "function")
            .put("name", name)
            .put("description", description)
            .put("parameters", parameters)
            .put("strict", true)
        ProviderProtocol.ANTHROPIC -> JSONObject()
            .put("name", name)
            .put("description", description)
            .put("input_schema", parameters)
    }
}

private class StreamFailedException(message: String) : Exception(message)

internal val IMAGE_MIME_TYPES = setOf(
    "image/png",
    "image/jpeg",
    "image/jpg",
    "image/webp",
    "image/gif",
)

internal fun dataUrl(mimeType: String, bytes: ByteArray): String {
    val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
    return "data:$mimeType;base64,$encoded"
}

internal fun extensionFor(mimeType: String): String = when (mimeType.lowercase()) {
    "image/png" -> ".png"
    "image/jpeg", "image/jpg" -> ".jpg"
    "image/webp" -> ".webp"
    "image/gif" -> ".gif"
    "video/mp4" -> ".mp4"
    "application/pdf" -> ".pdf"
    "text/plain" -> ".txt"
    "text/markdown" -> ".md"
    "application/json" -> ".json"
    else -> ""
}

internal fun attachmentSlots(attachments: List<ChatAttachment>): Float =
    attachments.sumOf { it.slotCost.toDouble() }.toFloat()

internal fun attachmentBytes(attachments: List<ChatAttachment>): Long =
    attachments.sumOf { it.size }
