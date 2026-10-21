package com.irio

import org.json.JSONArray
import org.json.JSONObject

/** Native GenerateContent wire format; never send OpenAI-only fields to this API. */
internal object GeminiProtocol {
    fun requestBody(messages: List<ChatRequestMessage>, systemPrompt: String?, tools: List<JSONObject> = emptyList()): JSONObject = JSONObject().apply {
        val limit = 100L * 1024 * 1024
        val estimatedBytes = messages.sumOf { message ->
            message.text.toByteArray(Charsets.UTF_8).size.toLong() + message.attachments.sumOf { ((it.size + 2) / 3) * 4 }
        } + (systemPrompt?.toByteArray(Charsets.UTF_8)?.size ?: 0)
        require(estimatedBytes < limit) { tr("Gemini 内联请求过大，请缩小附件（暂不支持 Files API 上传）") }
        require(messages.flatMap { it.attachments }.none { it.mimeType == "application/pdf" && it.size > 50L * 1024 * 1024 }) {
            tr("Gemini PDF 附件不能超过 50 MB")
        }
        if (!systemPrompt.isNullOrBlank()) {
            put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
        }
        val contents = JSONArray()
        messages.filter { it.role in setOf("user", "assistant", "tool") }.forEach { message ->
            val parts = JSONArray()
            when {
                message.role == "tool" -> parts.put(JSONObject().put("functionResponse", JSONObject().apply {
                    put("name", message.toolName)
                    message.geminiFunctionId?.let { put("id", it) }
                    put("response", JSONObject().put("result", message.text))
                }))
                message.geminiParts.isNotEmpty() -> message.geminiParts.forEach { parts.put(JSONObject(it)) }
                else -> {
                    if (message.text.isNotBlank()) parts.put(JSONObject().put("text", ChatApi.stampMessageText(message.text, message.createdAt, message.role == "user")))
                    message.attachments.forEach { attachment ->
                        parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", attachment.mimeType)
                            .put("data", java.util.Base64.getEncoder().encodeToString(attachment.bytes()))))
                    }
                    message.toolCalls.forEach { call ->
                        parts.put(JSONObject().put("functionCall", JSONObject().apply {
                            put("name", call.name)
                            call.geminiFunctionId?.let { put("id", it) }
                            put("args", JSONObject(call.arguments))
                        }))
                    }
                    if (parts.length() == 0) parts.put(JSONObject().put("text", ""))
                }
            }
            val role = if (message.role == "assistant") "model" else "user"
            // Parallel function responses belong to one user Content, not separate turns.
            val previous = contents.optJSONObject(contents.length() - 1)
            if (message.role == "tool" && previous?.optString("role") == "user") {
                for (index in 0 until parts.length()) previous.getJSONArray("parts").put(parts.get(index))
            } else contents.put(JSONObject().put("role", role).put("parts", parts))
        }
        put("contents", contents)
        if (tools.isNotEmpty()) put("tools", JSONArray().put(JSONObject().put("functionDeclarations", JSONArray(tools))))
        require(toString().toByteArray(Charsets.UTF_8).size < limit) { tr("Gemini 内联请求过大，请缩小附件（暂不支持 Files API 上传）") }
    }

    fun declaration(name: String, description: String, parameters: JSONObject): JSONObject = JSONObject()
        .put("name", name).put("description", description).put("parametersJsonSchema", parameters)

    fun putThinking(target: JSONObject, effort: ReasoningEffort, model: String) {
        val id = model.removePrefix("models/").lowercase()
        if (!id.startsWith("gemini-3") && !id.startsWith("gemini-2.5")) return
        val thinking = JSONObject().put("includeThoughts", effort != ReasoningEffort.OFF)
        if (effort != ReasoningEffort.AUTO) {
            if (id.startsWith("gemini-3")) {
                val pro = "pro" in id
                val imageLite = "flash-lite-image" in id
                val newerFlash = id.startsWith("gemini-3.7-flash") || id.startsWith("gemini-3.8-flash")
                val level = when (effort) {
                    ReasoningEffort.OFF, ReasoningEffort.MINIMAL -> if (pro || newerFlash) "LOW" else "MINIMAL"
                    ReasoningEffort.LOW -> if (imageLite) "MINIMAL" else "LOW"
                    ReasoningEffort.MEDIUM -> if (imageLite || (pro && !id.startsWith("gemini-3.1"))) "HIGH" else "MEDIUM"
                    else -> "HIGH"
                }
                thinking.put("thinkingLevel", level)
            } else {
                val pro = "pro" in id
                val budget = when (effort) {
                    ReasoningEffort.OFF -> if (pro) 128 else 0
                    ReasoningEffort.MINIMAL -> if (pro) 128 else 512
                    ReasoningEffort.LOW -> 1024
                    ReasoningEffort.MEDIUM -> 4096
                    ReasoningEffort.HIGH -> 8192
                    ReasoningEffort.XHIGH -> 16384
                    ReasoningEffort.MAX, ReasoningEffort.ULTRA -> if (pro) 32768 else 24576
                    ReasoningEffort.AUTO -> -1
                }
                thinking.put("thinkingBudget", budget)
            }
        }
        val generation = target.optJSONObject("generationConfig") ?: JSONObject().also { target.put("generationConfig", it) }
        generation.put("thinkingConfig", thinking)
    }

    /** Each SSE frame can contain more calls; indices must remain unique across frames. */
    class StreamParser {
        private var toolIndex = 0
        fun parse(data: String): ChatStreamUpdate? = GeminiProtocol.parse(data, toolIndex)?.also {
            toolIndex += it.toolCallDeltas.size
        }
    }

    fun parse(data: String, toolIndex: Int = 0): ChatStreamUpdate? = runCatching {
        val root = JSONObject(data)
        val candidate = root.optJSONArray("candidates")?.optJSONObject(0)
        root.optJSONObject("error")?.let { error ->
            return@runCatching ChatStreamUpdate(error = error.optString("message").ifBlank { "Gemini API error (${error.optInt("code")})" }, done = true)
        }
        val block = root.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
        val finish = ChatProtocol.presentString(candidate?.opt("finishReason"))
        if ((block.isNotBlank() && block != "BLOCK_REASON_UNSPECIFIED") ||
            (finish.isNotBlank() && finish !in setOf("STOP", "MAX_TOKENS", "FINISH_REASON_UNSPECIFIED"))) {
            val reason = block.ifBlank { finish }
            return@runCatching ChatStreamUpdate(error = candidate?.optString("finishMessage")?.takeIf { it.isNotBlank() }
                ?: "Gemini: $reason", done = true)
        }
        val parts = candidate?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val text = StringBuilder()
        val thinking = StringBuilder()
        val calls = mutableListOf<ToolCallDelta>()
        val rawParts = mutableListOf<String>()
        val attachments = mutableListOf<ChatAttachment>()
        for (index in 0 until parts.length()) {
            val part = parts.optJSONObject(index) ?: continue
            rawParts += part.toString()
            val value = ChatProtocol.presentString(part.opt("text"))
            if (part.optBoolean("thought")) thinking.append(value) else text.append(value)
            part.optJSONObject("inlineData")?.let { inline ->
                val mime = inline.optString("mimeType").ifBlank { "application/octet-stream" }
                val bytes = java.util.Base64.getDecoder().decode(inline.getString("data"))
                if (bytes.isNotEmpty()) {
                    val extension = when (mime) {
                        "image/png" -> ".png"
                        "image/jpeg" -> ".jpg"
                        "image/webp" -> ".webp"
                        "audio/wav" -> ".wav"
                        else -> ".bin"
                    }
                    val file = java.io.File.createTempFile("irio-gemini-", extension)
                    file.writeBytes(bytes)
                    attachments += ChatAttachment(file.name, mime, file.absolutePath)
                }
            }
            part.optJSONObject("functionCall")?.let { function ->
                val callIndex = toolIndex + calls.size
                val wireId = ChatProtocol.presentString(function.opt("id")).takeIf { it.isNotBlank() }
                calls += ToolCallDelta(index = callIndex, key = "g$callIndex",
                    id = wireId ?: "gemini-$callIndex", name = function.getString("name"),
                    argumentsDelta = (function.optJSONObject("args") ?: JSONObject()).toString(), geminiFunctionId = wireId)
            }
        }
        val metadata = root.optJSONObject("usageMetadata")
        fun count(name: String): Int? = metadata?.takeIf { it.has(name) && !it.isNull(name) }
            ?.optInt(name, -1)?.takeIf { it >= 0 }
        val output = listOfNotNull(count("candidatesTokenCount"), count("thoughtsTokenCount"))
            .takeIf { it.isNotEmpty() }?.sum()
        val usage = if (metadata == null) null else TokenUsage(count("promptTokenCount"), output, count("totalTokenCount"))
        ChatStreamUpdate(delta = text.toString(), thinkingDelta = thinking.toString(),
            model = ChatProtocol.presentString(root.opt("modelVersion")),
            done = finish.isNotBlank() && finish != "FINISH_REASON_UNSPECIFIED", usage = usage,
            toolCallDeltas = calls, geminiParts = rawParts, attachments = attachments)
    }.getOrNull()
}
