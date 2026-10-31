package com.irio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun ChatScreenState.noteVideoFinished(job: VideoGenerationJob) {
    if (!job.finished || job.delivered || clearingHistory) return
    pendingVideoResults[job.taskId] = job
    flushVideoResults(job.conversationId)
}

internal fun ChatScreenState.flushVideoResults(conversationId: String) {
    if (clearingHistory || generations.values.any { it.conversationId == conversationId } || preparing.values.any { it == conversationId }) return
    val ready = pendingVideoResults.values.filter { it.conversationId == conversationId }
    if (ready.isEmpty()) return
    val first = ready.first()
    val batch = ready.filter { it.chatProviderId == first.chatProviderId && it.chatModel == first.chatModel }
    batch.forEach { pendingVideoResults.remove(it.taskId) }
    val stored = if (currentConversation.id == conversationId) currentConversation.copy(messages = messages)
        else conversationContent(conversationId)
    if (stored == null) {
        batch.forEach { VideoGenerationJobs.acknowledge(it.taskId) }
        return
    }
    val fresh = batch.filter { job -> stored.messages.none { it.hidden && it.text.contains(videoCompletionMarker(job.taskId)) } }
    if (fresh.isEmpty()) {
        batch.forEach { VideoGenerationJobs.acknowledge(it.taskId) }
        return
    }
    val settled = stored.messages.map { message ->
        val events = message.process.map { event ->
            val job = fresh.firstOrNull { event.toolName == VideoGenTool.NAME &&
                runCatching { org.json.JSONObject(event.content).optString("task_id") }.getOrNull() == it.taskId }
            if (job == null) event else event.copy(live = false, content = job.resultJson(), displayText = "",
                toolOutcome = if (job.error.isBlank()) ToolOutcome.SUCCESS else ToolOutcome.FAILED)
        }
        if (events == message.process) message else message.copy(process = events)
    }
    val artifacts = fresh.map { job -> ChatMessage(
        id = MessageIds.next(), fromUser = false, text = if (job.error.isBlank()) "" else tr("视频生成失败：%s").format(job.error),
        attachments = listOfNotNull(job.attachment()), isError = job.error.isNotBlank(), finishedAt = System.currentTimeMillis(),
    ) }
    val notice = ChatMessage(MessageIds.next(), fresh.joinToString("\n\n") { job ->
        "${videoCompletionMarker(job.taskId)}\n${job.resultJson()}\n" +
            if (job.error.isBlank()) "The video is generated and its download card has been shown to the user. Briefly acknowledge completion; do not claim to have watched it or create another task."
            else "Video generation failed. Tell the user the actual error; do not recreate a paid task automatically."
    }, fromUser = true, hidden = true)
    val provider = providers.firstOrNull { it.id == first.chatProviderId && it.enabled && first.chatModel in it.enabledModels() }
        ?: ProviderStore.loadProviders(context).firstOrNull { it.id == first.chatProviderId && it.enabled && first.chatModel in it.enabledModels() }
    val reply = ChatMessage(MessageIds.next(), "", false, isPending = true, responseToUserId = notice.id)
    val next = settled + artifacts + notice + if (provider == null) emptyList() else listOf(reply)
    val delivery = stored.copy(messages = next, updatedAt = System.currentTimeMillis())
    if (currentConversation.id == conversationId) {
        messages = next
        currentConversation = delivery
    }
    cacheConversationContent(delivery)
    conversations = (conversations.filterNot { it.id == delivery.id } + delivery.summary())
        .sortedWith(compareByDescending<ConversationSummary> { it.pinned }.thenByDescending { it.updatedAt })
    // Persist the hidden dedup marker before acknowledging the durable background job.
    // A process death between these writes can replay a job, but cannot lose its result.
    preparing = preparing + (reply.id to conversationId)
    scope.launch {
        var persisted = false
        try {
            persisted = withContext(Dispatchers.IO) {
                // Serialize with delete/deleteAll: a late completion must never recreate history.
                val saved = ConversationSummaries.withLock {
                    if (clearingHistory || ConversationStore.load(context, conversationId) == null) false
                    else {
                        ConversationStore.save(context, delivery.copy(messages = next.filterNot { it.isPending }))
                        true
                    }
                }
                batch.forEach { VideoGenerationJobs.acknowledge(it.taskId) }
                saved
            }
        } finally {
            preparing = preparing - reply.id
        }
        if (persisted && !clearingHistory && provider != null && conversationContent(conversationId) != null) {
            sendOrCompress(conversationId, contextMessages(next).filterNot { it.isError || it.isPending }, reply,
                modelOverride = first.chatModel, providerOverride = provider)
        } else flushVideoResults(conversationId)
    }
}

internal fun videoCompletionMarker(taskId: String) = "[app_video_completion:$taskId]"
