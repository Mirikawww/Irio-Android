package com.irio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun ChatScreenState.compressionModelId(): String {
    val configured = ToolStore.loadCompressionModel(context)
    return when {
        configured.isNotBlank() -> configured
        !ModelCatalog.isImageModel(context, selectedModel) -> selectedModel
        else -> ProviderStore.loadLastChatModel(context)
    }
}

internal suspend fun ChatScreenState.runSummary(
    plan: CompressionPlan,
    conversationId: String,
    cancellation: ChatRequestCancellation,
): String? {
    val model = compressionModelId()
    if (model.isBlank()) return null
    val provider =
        ProviderStore.loadProviders(context).firstOrNull { model in it.models }
            ?: ProviderStore.loadProvider(context)
            ?: return null
    val window = ModelCatalog.contextWindow(context, selectedModel)
    val target = ToolStore.loadCompressionTarget(context)
    val budget = summaryBudgetTokens(window, target)
    return ChatApi.summarizeContext(
        provider = provider,
        model = model,
        systemPrompt = summarySystemPrompt(target, budget),
        messages = summaryTurns(plan),
        connectionScope = "$conversationId#summary",
        cancellation = cancellation,
    )
}

internal fun ChatScreenState.messagesOf(conversationId: String): List<ChatMessage> {
    return if (currentConversation.id == conversationId) {
        messages
    } else {
        conversationContent(conversationId)?.messages.orEmpty()
    }
}

internal fun ChatScreenState.finishSummaryCard(
    conversationId: String,
    card: ChatMessage,
    plan: CompressionPlan,
    text: String?,
    cancelled: Boolean,
) {
    val updated =
        if (!cancelled && !text.isNullOrBlank()) {
            card.copy(
                text = stripContextSummary(text),
                summaryState = SummaryState.SUCCESS,
                summaryUntilId = plan.untilId,
                isPending = false,
            )
        } else if (cancelled) {
            // 取消不留失败行。
            card.copy(
                text = "",
                summaryState = SummaryState.FAILED,
                isPending = false,
                selected = false,
            )
        } else {
            card.copy(text = "", summaryState = SummaryState.FAILED, isPending = false)
        }
    replaceInConversation(conversationId, card.id, updated, persist = true)
}

/** 发送前若估算已到触发线，先插一张交接卡片。失败或目录里没有窗口就不挡住这次发送。 */
internal fun ChatScreenState.sendOrCompress(
    conversationId: String,
    source: List<ChatMessage>,
    reply: ChatMessage,
) {
    // 同一回复只预检一次；占位同步写入，调用方 guards 据此拦截重复提交。
    if (reply.id in preparing) return
    preparing = preparing + (reply.id to conversationId)
    val requestModel = selectedModel
    val compressionEnabled = ToolStore.loadContextCompression(context)
    val compressionTrigger = ToolStore.loadCompressionTrigger(context)
    val requestPreparation = GenerationRequestPreparation(source)
    scope.launch {
        try {
            // Disabled compression skips preflight entirely. Otherwise its worker estimate is
            // retained on this send snapshot and reused by generation, not tokenized twice.
            val plan =
                if (!compressionEnabled) null else withContext(Dispatchers.Default) {
                    requestPreparation.compressionPlan(
                        enabled = compressionEnabled,
                        triggerPercent = compressionTrigger,
                        isImageModel = { ModelCatalog.isImageModel(context, requestModel) },
                        contextWindow = { ModelCatalog.contextWindow(context, requestModel) },
                    )
                }
            if (plan == null) {
                startGeneration(conversationId, source, reply, requestPreparation)
                return@launch
            }
            val card =
                ChatMessage(
                    id = MessageIds.next(),
                    text = "",
                    fromUser = false,
                    summaryState = SummaryState.RUNNING,
                    isPending = true,
                )
            if (currentConversation.id == conversationId) {
                messages = insertSummaryCard(messages, plan.untilId, card)
                persistCurrent(messages)
            }
            val cancellation = ChatRequestCancellation()
            val job =
                scope.launch {
                    val text = runSummary(plan, conversationId, cancellation)
                    withContext(Dispatchers.Main.immediate) {
                        val cancelled = cancellation.isCancelled()
                        finishSummaryCard(conversationId, card, plan, text, cancelled)
                        generations = generations - card.id
                        if (cancelled) {
                            val next =
                                messagesOf(conversationId).filterNot {
                                    it.id == reply.id &&
                                        it.text.isBlank() &&
                                        it.attachments.isEmpty() &&
                                        it.process.isEmpty()
                                }
                            if (currentConversation.id == conversationId) {
                                messages = next
                                persistCurrent(messages)
                            }
                            flushBackgroundNotices(conversationId)
                            return@withContext
                        }
                        val nextSource =
                            contextMessages(messagesOf(conversationId)).filterNot {
                                it.id == reply.id || it.isError || it.isPending
                            }
                        startGeneration(conversationId, nextSource, reply)
                    }
                }
            generations =
                generations +
                    (card.id to
                        ActiveGeneration(
                            conversationId = conversationId,
                            job = job,
                            cancellation = cancellation,
                        ))
        } finally {
            preparing = preparing - reply.id
        }
    }
}

/** 不等触发线，也不接着生成回复。当前可见历史整段压成一道边界。 */
internal fun ChatScreenState.compressNow() {
    if (busyThisChat) return
    val conversationId = currentConversation.id
    val snapshot = messages
    val cardId = MessageIds.next()
    preparing = preparing + (cardId to conversationId)
    scope.launch {
        try {
            val plan =
                withContext(Dispatchers.Default) {
                    planCompression(
                        contextMessages(snapshot).filterNot {
                            it.isError || it.isPending || it.isStreaming
                        }
                    )
                } ?: return@launch
            if (clearingHistory) return@launch
            val card =
                ChatMessage(
                    id = cardId,
                    text = "",
                    fromUser = false,
                    summaryState = SummaryState.RUNNING,
                    isPending = true,
                )
            if (currentConversation.id == conversationId) {
                messages = insertSummaryCard(messages, plan.untilId, card)
                persistCurrent(messages)
            }
            val cancellation = ChatRequestCancellation()
            val job =
                scope.launch {
                    val text = runSummary(plan, conversationId, cancellation)
                    withContext(Dispatchers.Main.immediate) {
                        val cancelled = cancellation.isCancelled()
                        finishSummaryCard(conversationId, card, plan, text, cancelled)
                        generations = generations - card.id
                        flushBackgroundNotices(conversationId)
                    }
                }
            generations =
                generations +
                    (card.id to
                        ActiveGeneration(
                            conversationId = conversationId,
                            job = job,
                            cancellation = cancellation,
                        ))
        } finally {
            preparing = preparing - cardId
        }
    }
}
