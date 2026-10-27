package com.irio

import org.json.JSONArray
import org.json.JSONObject

/** Stateless Interactions requests: local history is authoritative; no server conversation to leak/reuse. */
internal object GeminiInteractionsProtocol {
    fun declaration(name: String, description: String, parameters: JSONObject): JSONObject =
        JSONObject().put("type", "function").put("name", name).put("description", description).put("parameters", parameters)

    fun requestBody(model: String, messages: List<ChatRequestMessage>, systemPrompt: String?,
                    tools: List<JSONObject> = emptyList(), streaming: Boolean = false,
                    uploadEncoding: ImageUploadEncoding = ImageUploadEncoding()): JSONObject = JSONObject().apply {
        put("model", model.removePrefix("models/"))
        put("stream", streaming)
        put("store", false)
        if (!systemPrompt.isNullOrBlank()) put("system_instruction", systemPrompt)
        put("input", JSONArray().apply {
            messages.forEach { message ->
                when (message.role) {
                    "user" -> put(JSONObject().put("type", "user_input")
                        .put("content", messageContent(message, ChatApi.stampMessageText(message.text, message.createdAt, true), uploadEncoding)))
                    "assistant" -> {
                        val content = messageContent(message, ChatApi.stampMessageText(message.text, message.createdAt, false), uploadEncoding)
                        if (content.length() > 0) put(JSONObject().put("type", "model_output").put("content", content))
                        message.toolCalls.forEach { call ->
                            put(JSONObject().put("type", "function_call").put("id", call.geminiFunctionId ?: call.id)
                                .put("name", call.name).put("arguments", runCatching { JSONObject(call.arguments) }.getOrDefault(JSONObject())))
                        }
                    }
                    "tool" -> put(JSONObject().put("type", "function_result").put("name", message.toolName)
                        .put("call_id", message.toolCallId).put("result", JSONArray().put(
                            JSONObject().put("type", "text").put("text", message.text))))
                }
            }
        })
        if (tools.isNotEmpty()) put("tools", JSONArray(tools))
    }

    private fun messageContent(message: ChatRequestMessage, text: String, uploadEncoding: ImageUploadEncoding): JSONArray = JSONArray().apply {
        if (text.isNotBlank()) put(JSONObject().put("type", "text").put("text", text))
        message.attachments.filter { it.isImage }.forEach { attachment ->
            val encoded = uploadEncoding.encode(attachment)
            put(JSONObject().put("type", "image").put("mime_type", encoded.mimeType)
                .put("data", encoded.base64))
        }
    }

    fun putThinking(target: JSONObject, effort: ReasoningEffort, model: String) {
        if (!model.removePrefix("models/").startsWith("gemini-")) return
        val generation = target.optJSONObject("generation_config") ?: JSONObject().also { target.put("generation_config", it) }
        generation.put("thinking_summaries", if (effort == ReasoningEffort.OFF) "none" else "auto")
        if (effort != ReasoningEffort.AUTO) generation.put("thinking_level", when (effort) {
            ReasoningEffort.OFF, ReasoningEffort.MINIMAL -> "minimal"
            ReasoningEffort.LOW -> "low"
            ReasoningEffort.MEDIUM -> "medium"
            else -> "high"
        })
    }

    private fun usage(root: JSONObject?): TokenUsage? {
        if (root == null) return null
        fun count(name: String): Int? = root.takeIf { it.has(name) && !it.isNull(name) }?.optInt(name, -1)?.takeIf { it >= 0 }
        val output = listOfNotNull(count("total_output_tokens"), count("total_thought_tokens")).takeIf { it.isNotEmpty() }?.sum()
        return TokenUsage(count("total_input_tokens"), output, count("total_tokens"))
    }

    fun parseResponse(data: String): ChatStreamUpdate {
        val root = JSONObject(data)
        val text = StringBuilder()
        val thought = StringBuilder()
        val attachments = mutableListOf<ChatAttachment>()
        val steps = root.optJSONArray("steps") ?: JSONArray()
        for (i in 0 until steps.length()) {
            val step = steps.getJSONObject(i)
            val content = step.optJSONArray(if (step.optString("type") == "thought") "summary" else "content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.getJSONObject(j)
                if (part.optString("type") == "text") {
                    (if (step.optString("type") == "thought") thought else text).append(part.optString("text"))
                } else if (part.optString("type") == "image") {
                    val bytes = runCatching { java.util.Base64.getDecoder().decode(part.getString("data")) }.getOrNull()
                        ?: continue
                    if (bytes.isNotEmpty()) {
                        val mime = part.optString("mime_type").ifBlank { "image/png" }
                        val extension = when (mime) {
                            "image/jpeg" -> ".jpg"
                            "image/webp" -> ".webp"
                            else -> ".png"
                        }
                        val file = java.io.File.createTempFile("irio-interactions-", extension)
                        file.writeBytes(bytes)
                        attachments += ChatAttachment(file.name, mime, file.absolutePath)
                    }
                }
            }
        }
        return ChatStreamUpdate(delta = text.toString(), thinkingDelta = thought.toString(), attachments = attachments,
            model = ChatProtocol.presentString(root.opt("model")), usage = usage(root.optJSONObject("usage")),
            error = root.optJSONObject("error")?.optString("message"), done = true)
    }

    class StreamParser {
        private val functionCallIndices = mutableSetOf<Int>()

        fun parse(data: String, eventName: String = ""): ChatStreamUpdate? {
            val root = JSONObject(data)
            val interaction = root.optJSONObject("interaction")
            val model = ChatProtocol.presentString(interaction?.opt("model"))
            val tokens = usage(interaction?.optJSONObject("usage") ?: root.optJSONObject("usage")
                ?: root.optJSONObject("metadata")?.optJSONObject("total_usage"))
            return when (root.optString("event_type").ifBlank { eventName }) {
                "error" -> ChatStreamUpdate(error = root.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: "Gemini Interactions API error", done = true)
                "step.delta" -> {
                    val delta = root.optJSONObject("delta") ?: return null
                    when (delta.optString("type")) {
                        "text" -> ChatStreamUpdate(delta = delta.optString("text"), usage = tokens)
                        "arguments_delta" -> {
                            val index = root.optInt("index")
                            ChatStreamUpdate(toolCallDeltas = listOf(ToolCallDelta(
                                index = index, key = "i$index", argumentsDelta = delta.optString("arguments"),
                            )), usage = tokens)
                        }
                        else -> ChatStreamUpdate(usage = tokens)
                    }
                }
                "step.start" -> {
                    val step = root.optJSONObject("step") ?: return null
                    if (step.optString("type") != "function_call") return ChatStreamUpdate(model = model, usage = tokens)
                    val index = root.optInt("index")
                    functionCallIndices += index
                    val id = ChatProtocol.presentString(step.opt("id"))
                    ChatStreamUpdate(toolCallDeltas = listOf(ToolCallDelta(
                        index = index, key = "i$index", id = id, name = ChatProtocol.presentString(step.opt("name")),
                        geminiFunctionId = id,
                    )), model = model, usage = tokens)
                }
                "interaction.completed" -> ChatStreamUpdate(done = true, model = model, usage = tokens)
                "interaction.created" -> ChatStreamUpdate(model = model, usage = tokens)
                "step.stop" -> {
                    val index = root.optInt("index")
                    ChatStreamUpdate(finishReason = if (functionCallIndices.remove(index)) "function_call" else "",
                        model = model, usage = tokens)
                }
                else -> null
            }
        }
    }
}
