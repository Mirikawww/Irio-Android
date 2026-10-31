package com.irio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal fun ChatScreenState.flushSubagentResults(conversationId: String) {
    if (generations.values.any { it.conversationId == conversationId }) return
    if (preparing.values.any { it == conversationId }) return
    val queued = subagentFinishes.take(conversationId)
    if (queued.isEmpty()) return
    deliverSubagentResults(conversationId, queued)
}

internal fun ChatScreenState.flushBackgroundNotices(conversationId: String) {
    if (generations.values.any { it.conversationId == conversationId }) return
    if (preparing.values.any { it == conversationId }) return
    val queued = pendingBackgroundNotices.remove(conversationId)?.toList().orEmpty()
    if (queued.isNotEmpty()) deliverBackgroundNotices(conversationId, queued)
    flushVideoResults(conversationId)
    flushSubagentResults(conversationId)
}

internal fun ChatScreenState.noteSubagentFinished(snapshot: SubagentSnapshot) {
    if (snapshot.state == SubagentState.RUNNING || snapshot.conversationId.isBlank()) return
    if (!subagentFinishes.add(snapshot)) return
    flushSubagentResults(snapshot.conversationId)
}

internal fun ChatScreenState.noteBackgroundFinished(command: LinuxBackgroundCommand) {
    val conversationId = command.conversationId
    if (conversationId.isBlank()) return
    pendingBackgroundNotices.getOrPut(conversationId) { mutableListOf() }.add(command)
    flushBackgroundNotices(conversationId)
}

internal fun ChatScreenState.deliverBackgroundNotices(
    conversationId: String,
    commands: List<LinuxBackgroundCommand>,
) {
    if (clearingHistory || commands.isEmpty()) return
    val stored =
        if (currentConversation.id == conversationId) {
            currentConversation.copy(messages = messages)
        } else {
            conversationContent(conversationId) ?: return
        }
    val notice =
        ChatMessage(
            id = MessageIds.next(),
            text = linuxBackgroundCompletionText(commands),
            fromUser = true,
            hidden = true,
        )
    val reply =
        ChatMessage(
            id = MessageIds.next(),
            text = "",
            fromUser = false,
            isPending = true,
            responseToUserId = notice.id,
        )
    val next = messagesOf(conversationId) + notice + reply
    if (currentConversation.id == conversationId) {
        messages = next
        persistCurrent(messages)
    } else {
        val updated =
            stored.copy(
                messages = next,
                updatedAt = System.currentTimeMillis(),
                title = if (stored.titlePending) ConversationStore.titleFrom(next) else stored.title,
            )
        cacheConversationContent(updated)
        conversations =
            conversations
                .filterNot { it.id == updated.id }
                .plus(updated.summary())
                .let { list ->
                    val sorted =
                        list.sortedWith(
                            compareByDescending<ConversationSummary> { it.pinned }
                                .thenByDescending { it.updatedAt }
                        )
                    if (sorted == list) list else sorted
                }
        scope.launch(Dispatchers.IO) { ConversationStore.save(context, updated) }
    }
    val history = contextMessages(next).filterNot { it.isError || it.isPending }
    sendOrCompress(conversationId, history, reply)
}

internal fun ChatScreenState.deliverSubagentResults(
    conversationId: String,
    finished: List<SubagentSnapshot>,
) {
    if (clearingHistory || finished.isEmpty()) return
    val stored =
        if (currentConversation.id == conversationId) {
            currentConversation.copy(messages = messages)
        } else {
            conversationContent(conversationId) ?: return
        }
    val notice =
        ChatMessage(
            id = MessageIds.next(),
            text =
                finished.joinToString("\n\n") { snapshot ->
                    val output = snapshot.finalOutputText()
                    if (snapshot.state == SubagentState.COMPLETED) {
                        tr("子智能体「%s」已完成。最终输出：\n%s")
                            .format(snapshot.name, output.ifBlank { tr("（没有文本输出）") })
                    } else {
                        tr("子智能体「%s」失败或已被停止。已有输出：\n%s")
                            .format(snapshot.name, output.ifBlank { tr("（没有文本输出）") })
                    }
                },
            fromUser = true,
            hidden = true,
        )
    val reply =
        ChatMessage(
            id = MessageIds.next(),
            text = "",
            fromUser = false,
            isPending = true,
            responseToUserId = notice.id,
        )
    val next = messagesOf(conversationId) + notice + reply
    if (currentConversation.id == conversationId) {
        messages = next
        persistCurrent(messages)
    } else {
        val updated =
            stored.copy(
                messages = next,
                updatedAt = System.currentTimeMillis(),
                title = if (stored.titlePending) ConversationStore.titleFrom(next) else stored.title,
            )
        cacheConversationContent(updated)
        conversations =
            conversations
                .filterNot { it.id == updated.id }
                .plus(updated.summary())
                .let { list ->
                    val sorted =
                        list.sortedWith(
                            compareByDescending<ConversationSummary> { it.pinned }
                                .thenByDescending { it.updatedAt }
                        )
                    if (sorted == list) list else sorted
                }
        scope.launch(Dispatchers.IO) { ConversationStore.save(context, updated) }
    }
    val history = contextMessages(next).filterNot { it.isError || it.isPending }
    sendOrCompress(conversationId, history, reply)
}
