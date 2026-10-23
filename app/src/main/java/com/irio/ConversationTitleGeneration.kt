package com.irio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal data class ConversationTitleInput(val user: String, val assistant: String)

/** Reuse the visible branch, not hidden tool messages or discarded reply versions. */
internal fun conversationTitleInput(messages: List<ChatMessage>): ConversationTitleInput? {
    val visible = visibleMessages(messages).filter {
        !it.isError && !it.isPending && !it.isStreaming && it.summaryState == SummaryState.NONE
    }
    fun text(message: ChatMessage): String = (if (message.fromUser) message.requestText() else message.visibleText()).ifBlank {
        message.attachments.joinToString(", ") { it.name }
    }
    val userIndex = visible.indexOfFirst { it.fromUser && text(it).isNotBlank() }
    if (userIndex < 0) return null
    val assistant = visible.drop(userIndex + 1).takeWhile { !it.fromUser }
        .firstOrNull { text(it).isNotBlank() }?.let(::text).orEmpty()
    return ConversationTitleInput(text(visible[userIndex]), assistant)
}

internal data class ConversationTitleRequest(val original: ConversationSummary) {
    fun accepts(current: ConversationSummary): Boolean = current.id == original.id &&
        current.titlePending == original.titlePending && (original.titlePending || current.title == original.title)
}

/** Tokens prevent duplicate requests and invalidate results when a conversation is deleted. */
internal class ConversationTitleGenerationState {
    private val requests = mutableStateMapOf<String, Any>()
    var notice by mutableStateOf<String?>(null)
    var noticeNonce by mutableIntStateOf(0)
        private set

    fun isRunning(id: String): Boolean = id in requests
    fun begin(id: String): Any? {
        if (isRunning(id)) return null
        return Any().also { requests[id] = it }
    }
    fun isCurrent(id: String, token: Any): Boolean = requests[id] === token
    fun finish(id: String, token: Any) {
        if (isCurrent(id, token)) requests.remove(id)
    }
    fun cancel(id: String) { requests.remove(id) }
    fun clear() { requests.clear() }
    fun showNotice(message: String) {
        notice = message
        noticeNonce++
    }
}
