package com.irio

import org.json.JSONArray
import org.json.JSONObject

data class ChatToolRecord(val call: ChatToolCall, val result: String)

/** 已保存的执行记录作为历史上下文发送，不改界面正文，也不伪造旧记录中没有的 tool_call id/参数。 */
internal fun ChatMessage.contextText(): String = buildString {
    append(requestText())
    if (fromUser) return@buildString
    toolRecords.forEach { record ->
        if (isNotEmpty()) append("\n\n")
        append("[Tool execution record: ")
        append(record.call.name)
        append("]\nArguments: ")
        append(record.call.arguments)
        append("\nResult:\n")
        append(record.result)
    }
    // 逐条去重，不能按名字跳过所有事件：同一工具后一次调用被停止时仍需保留部分输出。
    val recordedEvents = toolRecords.groupingBy { it.call.name }.eachCount().toMutableMap()
    process.filter { !it.live && it.content.isNotBlank() }.forEach { event ->
        val name = when (event.kind) {
            ProcessEventKind.TOOL -> event.toolName.ifBlank { "tool" }
            ProcessEventKind.SEARCH -> WebSearchTool.NAME
            ProcessEventKind.THINKING -> return@forEach
        }
        val remaining = recordedEvents[name] ?: 0
        if (remaining > 0) {
            recordedEvents[name] = remaining - 1
            return@forEach
        }
        if (isNotEmpty()) append("\n\n")
        append("[Tool execution record: ")
        append(name)
        append("]\n")
        if (event.kind == ProcessEventKind.SEARCH && event.searchQuery.isNotBlank()) {
            append("Query: ")
            append(event.searchQuery)
            append('\n')
        }
        append(event.content)
    }
}

internal fun toolRecordsJson(records: List<ChatToolRecord>): JSONArray = JSONArray().apply {
    records.forEach { record ->
        put(JSONObject().apply {
            put("id", record.call.id)
            put("name", record.call.name)
            put("arguments", record.call.arguments)
            record.call.geminiFunctionId?.let { put("geminiFunctionId", it) }
            put("result", record.result)
        })
    }
}

internal fun toolRecordsFrom(item: JSONObject): List<ChatToolRecord> = buildList {
    val records = item.optJSONArray("toolRecords") ?: return@buildList
    for (index in 0 until records.length()) {
        val record = records.optJSONObject(index) ?: continue
        val name = record.optString("name")
        if (name.isBlank()) continue
        add(ChatToolRecord(
            call = ChatToolCall(
                id = record.optString("id"),
                name = name,
                arguments = record.optString("arguments"),
                geminiFunctionId = record.optString("geminiFunctionId").takeIf { it.isNotBlank() },
            ),
            result = record.optString("result"),
        ))
    }
}
