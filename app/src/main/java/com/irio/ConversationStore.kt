package com.irio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class ChatMessage(
    val id: Long,
    val text: String,
    val fromUser: Boolean,
    val createdAt: Long = System.currentTimeMillis(),
    val attachments: List<ChatAttachment> = emptyList(),
    val process: List<ProcessEvent> = emptyList(),
    val isError: Boolean = false,
    val isPending: Boolean = false,
    val isStreaming: Boolean = false,
    val retryGroup: Long = 0L,
    val selected: Boolean = true,
    /** 回复所属的用户消息版本；旧数据为空时按消息顺序推断。 */
    val responseToUserId: Long? = null,
    /** 用户消息编辑版本组：同组内多条用户消息为同一消息的不同版本；0 = 未编辑过。 */
    val editGroup: Long = 0L,
    /** 最近一次接口回报的 usage；圆环只读这份数据，不做本地估算。 */
    val usage: TokenUsage? = null,
) {
    val groupId: Long get() = retryGroup.takeIf { it != 0L } ?: id
    /** 用户版本组键：编辑过取 editGroup，否则取 id（单条即自己）。 */
    val userGroupId: Long get() = editGroup.takeIf { it != 0L } ?: id
    val thinking: String get() = process.lastOrNull { it.kind == ProcessEventKind.THINKING }?.content.orEmpty()
    val thinkingMillis: Long get() = process.filter { it.kind == ProcessEventKind.THINKING }.sumOf { it.millis }
    val searchPages: Int get() = process.filter { it.kind == ProcessEventKind.SEARCH }.sumOf { it.pages }
    val isSearching: Boolean get() = process.any { it.kind == ProcessEventKind.SEARCH && it.live }
}

data class Conversation(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messages: List<ChatMessage>,
    val pinned: Boolean = false,
    /** 标记标题待 AI 生成：AI 生成成功后清除；存在时列表标题跟随首条用户消息截断文本显示。 */
    val titlePending: Boolean = false,
    /** 当前对话最近一次接口回报的 token 用量。 */
    val tokenUsage: TokenUsage? = null,
)

/** 抽屉列表用的轻量摘要：不含正文，loadAll 时不用解析每条消息。 */
data class ConversationSummary(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val pinned: Boolean = false,
    val titlePending: Boolean = false,
) {
    fun toConversation(): Conversation =
        Conversation(id = id, title = title, updatedAt = updatedAt, messages = emptyList(), pinned = pinned, titlePending = titlePending)
}

fun Conversation.summary(): ConversationSummary =
    ConversationSummary(id = id, title = title, updatedAt = updatedAt, pinned = pinned, titlePending = titlePending)

object ConversationStore {
    private const val LAST_ID = "last_id.txt"

    fun loadAll(context: Context): List<ConversationSummary> = ConversationSummaries.load(directory(context))

    fun load(context: Context, id: String): Conversation? {
        val file = File(directory(context), "$id.json")
        if (!file.exists()) return null
        return runCatching { conversationFromJson(JSONObject(file.readText())) }.getOrNull()
    }

    fun save(context: Context, conversation: Conversation) {
        val dir = directory(context)
        val wrote = ConversationSummaries.commit(dir, conversation.summary()) {
            File(dir, "${conversation.id}.json").writeText(conversation.toJson().toString())
        }
        if (wrote) notifyChanged()
    }

    fun replaceAll(context: Context, conversations: List<Conversation>, lastOpenedId: String?) {
        val dir = directory(context)
        ConversationSummaries.withLock {
            dir.listFiles()?.forEach { it.delete() }
            dir.mkdirs()
            conversations.forEach { conversation ->
                File(dir, "${conversation.id}.json").writeText(conversation.toJson().toString())
            }
            ConversationSummaries.replace(dir, conversations.map { it.summary() })
            val lastFile = File(dir, LAST_ID)
            if (lastOpenedId.isNullOrBlank()) lastFile.delete() else lastFile.writeText(lastOpenedId)
        }
        notifyChanged()
    }

    fun importCoexist(context: Context, incoming: List<Conversation>) {
        val dir = directory(context)
        ConversationSummaries.withLock {
            val current = ConversationSummaries.load(dir).toMutableList()
            incoming.forEach { remote ->
                val local = load(context, remote.id)
                if (local == null || remote.updatedAt >= local.updatedAt) {
                    File(dir, "${remote.id}.json").writeText(remote.toJson().toString())
                    current.removeAll { it.id == remote.id }
                    current.add(remote.summary())
                }
            }
            ConversationSummaries.replace(dir, current)
        }
        notifyChanged()
    }

    fun parseConversation(json: JSONObject): Conversation = conversationFromJson(json)

    fun conversationJson(conversation: Conversation): JSONObject = conversation.toJson()

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

    fun delete(context: Context, id: String) {
        val dir = directory(context)
        ConversationSummaries.withLock {
            File(dir, "$id.json").delete()
            ConversationSummaries.remove(dir, id)
            if (loadLastOpenedId(context) == id) {
                File(dir, LAST_ID).delete()
            }
        }
        notifyChanged()
    }

    fun deleteAll(context: Context) {
        replaceAll(context, emptyList(), null)
        val attachments = File(context.filesDir, "attachments")
        attachments.deleteRecursively()
        attachments.mkdirs()
    }

    fun loadLastOpened(context: Context): Conversation? {
        loadLastOpenedId(context)?.let { id -> load(context, id)?.let { return it } }
        // 没有 lastOpenedId 时取「置顶优先、最近更新」的第一条，而不是索引顺序的第一条。
        return loadAll(context).firstOrNull()?.let { load(context, it.id) }
    }

    fun saveLastOpenedId(context: Context, id: String) {
        File(directory(context), LAST_ID).writeText(id)
    }

    fun loadLastOpenedId(context: Context): String? {
        val file = File(directory(context), LAST_ID)
        if (!file.exists()) return null
        return file.readText().trim().takeIf { it.isNotBlank() }
    }

    fun newConversation(): Conversation {
        return Conversation(
            id = UUID.randomUUID().toString(),
            title = "新对话",
            updatedAt = System.currentTimeMillis(),
            messages = emptyList(),
            titlePending = true,
        )
    }

    fun titleFrom(messages: List<ChatMessage>): String {
        val first = messages.firstOrNull { it.fromUser && it.text.isNotBlank() }?.text
            ?: messages.firstOrNull { it.fromUser }?.attachments?.firstOrNull()?.name
        return first?.trim()?.replace('\n', ' ')?.take(24)?.ifBlank { "新对话" } ?: "新对话"
    }

    private fun directory(context: Context): File {
        return File(context.filesDir, "conversations").apply { mkdirs() }
    }

    private fun Conversation.toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("title", title.ifBlank { "新对话" })
            put("updatedAt", updatedAt)
            put("pinned", pinned)
            put("titlePending", titlePending)
            tokenUsage?.let { usage ->
                put(
                    "tokenUsage",
                    JSONObject().apply {
                        usage.promptTokens?.let { put("promptTokens", it) }
                        usage.completionTokens?.let { put("completionTokens", it) }
                        usage.totalTokens?.let { put("totalTokens", it) }
                    },
                )
            }
            put("messages", JSONArray().apply {
                messages.forEach { message ->
                    put(
                        JSONObject().apply {
                            put("id", message.id)
                            put("text", message.text)
                            put("fromUser", message.fromUser)
                            put("createdAt", message.createdAt)
                            put("isError", message.isError)
                            put("retryGroup", message.groupId)
                            put("selected", message.selected)
                            message.responseToUserId?.let { put("responseToUserId", it) }
                            if (message.fromUser && message.editGroup != 0L) put("editGroup", message.userGroupId)
                            message.usage?.let { usage ->
                                put(
                                    "usage",
                                    JSONObject().apply {
                                        usage.promptTokens?.let { put("promptTokens", it) }
                                        usage.completionTokens?.let { put("completionTokens", it) }
                                        usage.totalTokens?.let { put("totalTokens", it) }
                                    },
                                )
                            }
                            put(
                                "process",
                                JSONArray().apply {
                                    message.process.forEach { event ->
                                        put(
                                            JSONObject().apply {
                                                put("id", event.id)
                                                put("kind", event.kind.name)
                                                put("content", event.content)
                                                put("millis", event.millis)
                                                put("pages", event.pages)
                                                if (event.belowText) put("belowText", true)
                                                if (event.textOffset > 0) put("textOffset", event.textOffset)
                                                if (event.toolName.isNotBlank()) put("toolName", event.toolName)
                                            }
                                        )
                                    }
                                }
                            )
                            put(
                                "attachments",
                                JSONArray().apply {
                                    message.attachments.forEach { attachment ->
                                        put(
                                            JSONObject().apply {
                                                put("name", attachment.name)
                                                put("mimeType", attachment.mimeType)
                                                put("filePath", attachment.filePath)
                                            }
                                        )
                                    }
                                }
                            )
                        }
                    )
                }
            })
        }
    }

    private fun conversationFromJson(json: JSONObject): Conversation {
        val messagesJson = json.optJSONArray("messages") ?: JSONArray()
        val messages = buildList {
            for (index in 0 until messagesJson.length()) {
                val item = messagesJson.optJSONObject(index) ?: continue
                val attachmentsJson = item.optJSONArray("attachments") ?: JSONArray()
                val attachments = buildList {
                    for (attachmentIndex in 0 until attachmentsJson.length()) {
                        val attachment = attachmentsJson.optJSONObject(attachmentIndex) ?: continue
                        val path = attachment.optString("filePath")
                        if (path.isBlank() || !File(path).exists()) continue
                        add(
                            ChatAttachment(
                                name = attachment.optString("name"),
                                mimeType = attachment.optString("mimeType"),
                                filePath = path,
                            )
                        )
                    }
                }
                add(
                    ChatMessage(
                        id = item.optLong("id"),
                        text = item.optString("text"),
                        fromUser = item.optBoolean("fromUser"),
                        createdAt = item.optLong("createdAt").takeIf { it > 0L } ?: System.currentTimeMillis(),
                        attachments = attachments,
                        process = processEventsFrom(item),
                        isError = item.optBoolean("isError"),
                        retryGroup = item.optLong("retryGroup").takeIf { it != 0L } ?: item.optLong("id"),
                        selected = if (item.has("selected")) item.optBoolean("selected") else true,
                        responseToUserId = item.optLong("responseToUserId").takeIf { it != 0L },
                        editGroup = if (item.optBoolean("fromUser")) {
                            item.optLong("editGroup").takeIf { it != 0L } ?: 0L
                        } else 0L,
                        usage = tokenUsageFrom(item.optJSONObject("usage")),
                    )
                )
            }
        }
        return Conversation(
            id = json.optString("id"),
            title = json.optString("title").ifBlank { "新对话" },
            updatedAt = json.optLong("updatedAt"),
            messages = messages,
            pinned = json.optBoolean("pinned"),
            titlePending = json.optBoolean("titlePending"),
            tokenUsage = tokenUsageFrom(json.optJSONObject("tokenUsage"))
                ?: messages.lastOrNull { !it.fromUser && it.usage != null }?.usage,
        )
    }
}

internal fun tokenUsageFrom(json: JSONObject?): TokenUsage? {
    if (json == null) return null
    fun optionalInt(key: String): Int? =
        if (json.has(key) && !json.isNull(key)) json.optInt(key).takeIf { it >= 0 } else null
    val prompt = optionalInt("promptTokens")
    val completion = optionalInt("completionTokens")
    val total = optionalInt("totalTokens")
    if (prompt == null && completion == null && total == null) return null
    return TokenUsage(promptTokens = prompt, completionTokens = completion, totalTokens = total)
}

internal fun processEventsFrom(item: JSONObject): List<ProcessEvent> {
    val array = item.optJSONArray("process")
    if (array != null && array.length() > 0) {
        return buildList {
            for (index in 0 until array.length()) {
                val event = array.optJSONObject(index) ?: continue
                val kind = runCatching { ProcessEventKind.valueOf(event.optString("kind")) }.getOrNull() ?: continue
                add(
                    ProcessEvent(
                        id = event.optLong("id").takeIf { it != 0L } ?: (index + 1L),
                        kind = kind,
                        content = event.optString("content"),
                        millis = event.optLong("millis"),
                        pages = event.optInt("pages"),
                        belowText = event.optBoolean("belowText"),
                        textOffset = event.optInt("textOffset"),
                        toolName = event.optString("toolName"),
                    )
                )
            }
        }
    }
    val thinking = item.optString("thinking")
    val thinkingMillis = item.optLong("thinkingMillis")
    val searchPages = item.optInt("searchPages")
    return buildList {
        if (thinking.isNotBlank() || thinkingMillis > 0L) {
            add(
                ProcessEvent(
                    id = 1L,
                    kind = ProcessEventKind.THINKING,
                    content = thinking,
                    millis = thinkingMillis,
                )
            )
        }
        if (searchPages > 0) {
            add(
                ProcessEvent(
                    id = 2L,
                    kind = ProcessEventKind.SEARCH,
                    pages = searchPages,
                )
            )
        }
    }
}
