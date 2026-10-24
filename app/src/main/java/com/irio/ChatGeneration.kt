package com.irio

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.runtime.snapshots.Snapshot
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun ChatScreenState.stopGeneration(conversationId: String = currentConversation.id) {
    val active = generations.values.filter { it.conversationId == conversationId }
    if (active.isEmpty()) return
    // 立刻停止流式展示并移除生图占位，不等待网络收尾。
    val replyIds =
        generations.entries
            .filter { it.value.conversationId == conversationId }
            .map { it.key }
            .toSet()
    messages =
        messages.map { msg ->
            if (msg.id in replyIds) msg.copy(isStreaming = false).stopImageGenerationPreviews()
            else msg
        }
    // 断开 HTTP 连接会让 ChatApi 返回 stopped；不要取消协程本身，
    // 否则取消状态会跳过后续收尾逻辑，思考事件无法写入最终消息。
    active.forEach { entry -> entry.cancellation.cancel() }
}

internal fun ChatScreenState.startGeneration(
    requestConversationId: String,
    requestSource: List<ChatMessage>,
    reply: ChatMessage,
) {
    ReplyNotifications.requestPermissionIfNeeded(
        granted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
    ) {
        requestReplyNotification()
    }
    SpeechPlayer.stop()
    val requestModel = selectedModel
    val requestImageOptions = ImageGenerationStore.load(context, activeProviderId, requestModel)
    val requestImageStreaming = ThemeStore.imageGenerationStreaming
    val effectiveReasoningInfo =
        if (ProviderStore.loadModelsDevReasoning(context)) {
            ModelCatalog.reasoningInfo(context, requestModel, useCatalog = true)
        } else null
    val requestEffort =
        ModelCatalog.resolveReasoningEffort(
            reasoningEffort,
            ModelCatalog.reasoningLevels(effectiveReasoningInfo),
        )
    val requestFast = fastMode
    val requestRetryOptions = ThemeStore.retryOptions()
    val requestWebSearch = SearchStore.isWebSearchActive(context)
    val requestSearchProvider = SearchStore.loadProvider(context)
    val requestSearchKey = SearchStore.loadApiKey(context, requestSearchProvider)
    val requestSearchMaxResults = SearchStore.loadMaxResults(context, requestSearchProvider)
    val requestImageSearchKey = SearchStore.loadImageApiKey(context)
    val requestImageSearchMaxResults = SearchStore.loadImageMaxResults(context)
    val requestMaxToolRounds = ToolStore.loadMaxRounds(context)
    val requestSystemPrompt = chatSystemPrompt(context)
    // 双重门槛，任一不满足都不注入 generate_image 工具：
    // ① 对话模型本身是生图模型（它自己能画；且图片渠道普遍不支持 tools，注入必报能力错）；
    // ② 设置里存的生图模型必须在目录判定为真生图模型（过滤规则上线前的旧值——
    //    如曾被选成聊天模型——拿去打 /images/generations 一样报能力错，直接视为未配置）。
    val requestImageGenModel = configuredImageGenModel(context, requestModel)
    val requestRegistry =
        chatToolRegistry(
            context,
            requestWebSearch,
            requestImageGenModel,
            imageSearch = requestImageSearchKey.isNotBlank(),
        )
    val requestCancellation = ChatRequestCancellation()
    // 优先用当前内存态的 provider（省一次全量 Keystore 解密解析）；
    // 被选中 provider 被删掉时回落磁盘。
    val requestProvider =
        providers.firstOrNull { it.id == activeProviderId && it.models.isNotEmpty() }
            ?: providers.firstOrNull { it.models.isNotEmpty() }
            ?: ProviderStore.loadProvider(context)
    val uploadMessageId =
        requestSource
            .lastOrNull()
            ?.takeIf { user ->
                user.fromUser &&
                    !user.hidden &&
                    user.id == reply.responseToUserId &&
                    user.attachments.isNotEmpty() &&
                    requestProvider != null &&
                    requestModel.isNotBlank() &&
                    !ModelCatalog.isImageModel(context, requestModel)
            }
            ?.id
    if (uploadMessageId != null)
        attachmentUploads.prepare(requestConversationId, uploadMessageId, reply.id)
    val job =
        scope.launch {
            var appliedFinal = false
            try {
                GitHubMcpStore.of(context)
                    .toolProvider(requestCancellation)
                    ?.let(requestRegistry::register)
                TodoistMcpStore.of(context)
                    .toolProvider(requestCancellation)
                    ?.let(requestRegistry::register)
                val provider = requestProvider
                var streamedText = ""
                var roundBreakPending = false
                var streamedAttachments = emptyList<ChatAttachment>()
                var toolRecords = emptyList<ChatToolRecord>()
                val process = ProcessTracker()
                var notice = tr("响应中")
                fun noticeTitle(): String {
                    val stored =
                        if (currentConversation.id == requestConversationId) {
                            currentConversation.title
                        } else {
                            conversationContent(requestConversationId)?.title ?: "新对话"
                        }
                    return displayConversationTitle(stored)
                }
                fun pushNotice(text: String, finished: Boolean) {
                    if (text == notice && !finished) return
                    notice = text
                    ReplyLiveNotice.publish(
                        context,
                        requestConversationId,
                        noticeTitle(),
                        text,
                        finished,
                    )
                }
                pushNotice(notice, finished = false)
                var lastPublishAt = 0L
                var publishedBodyLength = 0
                var publishedThinking = false
                var streamedUsage: TokenUsage? = null
                var retryStatus: ChatRetryStatus? = null
                var attemptText = ""
                var attemptAttachments = emptyList<ChatAttachment>()
                var attemptProcess = process.checkpoint()
                var attemptUsage: TokenUsage? = null
                var attemptRoundBreak = false
                var attemptToolTextLength = 0
                // 对话模型本身是生图模型：聊天请求整条改走 /images/generations
                // （gpt-image 等纯生图模型在 chat/completions 上游必拒），
                // 最后一条用户消息当 prompt，预览及实际尺寸流式回流，最终图仍作为普通附件保存。
                val result =
                    if (provider == null) {
                        ChatCompletionResult(error = tr("请先在设置中添加提供商"))
                    } else if (ModelCatalog.isImageModel(context, requestModel)) {
                        val lastUserIndex = requestSource.indexOfLast { it.fromUser && !it.hidden }
                        val prompt =
                            requestSource
                                .getOrNull(lastUserIndex)
                                ?.requestText()
                                .orEmpty()
                                .ifBlank { tr("图片") }
                        val imageContext = chatImageGenerationContext(requestSource)
                        process.onToolStarted(ImageGenTool.NAME)
                        pushNotice(MemoryTool.liveLabel(ImageGenTool.NAME), finished = false)
                        replaceInConversation(
                            requestConversationId,
                            reply.id,
                            reply.copy(
                                process = process.snapshot(),
                                isPending = false,
                                isStreaming = true,
                            ),
                        )
                        val imageProcess = process.checkpoint()
                        val imageRetries =
                            ChatRetryController(
                                requestRetryOptions,
                                requestCancellation,
                                onUpdate = { update ->
                                    if (update.resetAttempt) process.restore(imageProcess)
                                    if (update.retry != null) retryStatus = update.retry
                                    if (update.retryCleared) retryStatus = null
                                    withContext(Dispatchers.Main.immediate) {
                                        if (!requestCancellation.isCancelled())
                                            replaceInConversation(
                                                requestConversationId,
                                                reply.id,
                                                reply.copy(
                                                    process = process.snapshot(),
                                                    retryStatus = retryStatus,
                                                    isPending = false,
                                                    isStreaming = true,
                                                ),
                                            )
                                    }
                                },
                                downgradeToHttp1 = { false },
                                isEnabled = { ThemeStore.autoRetry },
                            )
                        withContext(Dispatchers.IO) {
                            imageRetries.runRound {
                                val generated =
                                    ProviderApi.generateImage(
                                        provider,
                                        requestModel,
                                        prompt,
                                        imageContext,
                                        cancellation = requestCancellation,
                                        options = requestImageOptions,
                                        streaming = requestImageStreaming,
                                        onProgress = { progress ->
                                            if (!requestCancellation.isCancelled())
                                                kotlinx.coroutines.runBlocking {
                                                    process.onImageGeneration(progress)
                                                    withContext(Dispatchers.Main.immediate) {
                                                        if (!requestCancellation.isCancelled())
                                                            replaceInConversation(
                                                                requestConversationId,
                                                                reply.id,
                                                                reply.copy(
                                                                    process = process.snapshot(),
                                                                    retryStatus = retryStatus,
                                                                    isPending = false,
                                                                    isStreaming = true,
                                                                ),
                                                            )
                                                    }
                                                }
                                        },
                                    )
                                val stopped =
                                    requestCancellation.isCancelled() ||
                                        generated.error == tr("图片生成已停止")
                                if (!stopped) {
                                    if (generated.attachment != null) process.dropLiveTool()
                                    else process.onToolFinished(ImageGenTool.STATUS_FAILED)
                                }
                                when {
                                    stopped -> ChatCompletionResult(stopped = true)
                                    generated.attachment != null ->
                                        ChatCompletionResult(
                                            content = generated.text,
                                            attachments = listOf(generated.attachment),
                                            model = requestModel,
                                        )
                                    else ->
                                        ChatCompletionResult(
                                            error = generated.error ?: tr("图片生成失败"),
                                            retryable = generated.retryable,
                                        )
                                }
                            }
                        }
                    } else if (requestModel.isBlank()) {
                        ChatCompletionResult(error = tr("请先选择一个可用模型"))
                    } else {
                        val requestMessages = modelRequestMessages(requestSource)
                        val baseContext =
                            withContext(Dispatchers.Default) {
                                estimateRequestTokens(requestMessages) to
                                    estimateToolContextTokens(requestSource)
                            }
                        val baseContextTokens = baseContext.first
                        if (currentConversation.id == requestConversationId) {
                            liveContextEstimate = requestConversationId to baseContextTokens
                            contextToolTokens = requestConversationId to baseContext.second
                            liveToolTokens = requestConversationId to 0
                        }
                        val liveTokenEstimator = LiveReplyTokenEstimator()
                        val streamedToolCalls = StringBuilder()
                        var lastUploadPercent = -1
                        withContext(Dispatchers.IO) {
                            ChatApi.streamChat(
                                provider = provider,
                                model = requestModel,
                                messages = requestMessages,
                                reasoningEffort = requestEffort,
                                fastMode = requestFast,
                                webSearch = requestWebSearch,
                                searchProvider = requestSearchProvider,
                                searchApiKey = requestSearchKey,
                                searchMaxResults = requestSearchMaxResults,
                                imageSearchApiKey = requestImageSearchKey,
                                imageSearchMaxResults = requestImageSearchMaxResults,
                                maxToolRounds = requestMaxToolRounds,
                                systemPrompt = requestSystemPrompt,
                                imageGenModel = requestImageGenModel,
                                toolRegistry = requestRegistry,
                                connectionScope =
                                    modelConversationScope(requestConversationId, requestSource),
                                conversationId = requestConversationId,
                                cancellation = requestCancellation,
                                appContext = context,
                                retryOptions = requestRetryOptions,
                                onUploadProgress =
                                    uploadMessageId?.let { userMessageId ->
                                        { sent: Long, total: Long ->
                                            val percent =
                                                if (total > 0L) (sent * 100L / total).toInt() else 0
                                            if (percent != lastUploadPercent) {
                                                lastUploadPercent = percent
                                                scope.launch(Dispatchers.Main.immediate) {
                                                    if (!requestCancellation.isCancelled()) {
                                                        attachmentUploads.advance(
                                                            requestConversationId,
                                                            userMessageId,
                                                            reply.id,
                                                            sent,
                                                            total,
                                                        )
                                                        if (sent == total) {
                                                            delay(350)
                                                            attachmentUploads.hideCompleted(
                                                                requestConversationId,
                                                                userMessageId,
                                                                reply.id,
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    },
                                onReaction = { messageId, emoji ->
                                    withContext(Dispatchers.Main.immediate) {
                                        !requestCancellation.isCancelled() &&
                                            updateMessageReaction(
                                                requestConversationId,
                                                messageId,
                                                emoji,
                                                fromModel = true,
                                            )
                                    }
                                },
                                onUpdate = update@{ update ->
                                        if (update.attemptStarted) {
                                            attemptText = streamedText
                                            attemptAttachments = streamedAttachments
                                            attemptProcess = process.checkpoint()
                                            attemptUsage = streamedUsage
                                            attemptRoundBreak = roundBreakPending
                                            attemptToolTextLength = streamedToolCalls.length
                                            return@update
                                        }
                                        if (update.resetAttempt) {
                                            streamedText = attemptText
                                            streamedAttachments = attemptAttachments
                                            process.restore(attemptProcess)
                                            streamedUsage = attemptUsage
                                            roundBreakPending = attemptRoundBreak
                                            streamedToolCalls.setLength(attemptToolTextLength)
                                            lastUploadPercent = -1
                                        }
                                        if (update.retry != null) retryStatus = update.retry
                                        if (update.retryCleared) retryStatus = null
                                        update.toolRecord?.let { toolRecords = toolRecords + it }
                                        val advanced =
                                            update.retry?.let { chatRetryLine(it) }
                                                ?: advanceReplyNotice(notice, update)
                                        if (advanced != notice) {
                                            notice = advanced
                                            val step = advanced
                                            withContext(Dispatchers.Main.immediate) {
                                                ReplyLiveNotice.publish(
                                                    context,
                                                    requestConversationId,
                                                    noticeTitle(),
                                                    step,
                                                    finished = false,
                                                )
                                            }
                                        }
                                        val cancelled = requestCancellation.isCancelled()
                                        val terminalState =
                                            update.searching == false ||
                                                update.toolActive == false ||
                                                update.done
                                        if (
                                            !cancelled ||
                                                terminalState ||
                                                update.attachments.isNotEmpty()
                                        ) {
                                            if (!cancelled) {
                                                if (update.newRound && streamedText.isNotEmpty())
                                                    roundBreakPending = true
                                                if (update.thinkingDelta.isNotEmpty()) {
                                                    process.onThinkingDelta(update.thinkingDelta)
                                                }
                                                if (update.toolCallDeltas.isNotEmpty()) {
                                                    update.toolCallDeltas.forEach { delta ->
                                                        if (delta.name.isNotEmpty())
                                                            streamedToolCalls.append(delta.name)
                                                        if (delta.argumentsDelta.isNotEmpty())
                                                            streamedToolCalls.append(
                                                                delta.argumentsDelta
                                                            )
                                                    }
                                                }
                                                if (update.delta.isNotEmpty()) {
                                                    if (roundBreakPending) {
                                                        streamedText =
                                                            appendAssistantRoundBreak(streamedText)
                                                        roundBreakPending = false
                                                    }
                                                    streamedText += update.delta
                                                    process.onOutputStarted(
                                                        textLength = streamedText.length
                                                    )
                                                }
                                            }
                                            if (!cancelled && update.subagentJson.isNotEmpty())
                                                process.onSubagent(update.subagentJson)
                                            process.refreshSubagents(SubagentHub.jsonById())
                                            if (!cancelled && update.coloredBubbles.isNotEmpty())
                                                process.onColoredBubbles(update.coloredBubbles)
                                            if (!cancelled && update.displayedImages.isNotEmpty())
                                                process.onDisplayedImages(update.displayedImages)
                                            if (update.attachments.isNotEmpty()) {
                                                streamedAttachments =
                                                    (streamedAttachments + update.attachments)
                                                        .distinctBy { it.filePath }
                                            }
                                            if (update.usage != null) streamedUsage = update.usage
                                            if (update.searching == true && !cancelled)
                                                process.onSearchStarted(
                                                    update.searchQuery,
                                                    update.displayText,
                                                )
                                            if (update.searching == false)
                                                process.onSearchFinished(
                                                    update.searchPagesDelta,
                                                    update.searchContent,
                                                    update.toolOutcome,
                                                )
                                            if (update.toolActive == true && !cancelled)
                                                process.onToolStarted(
                                                    update.toolName,
                                                    update.webDomain,
                                                    update.displayText,
                                                )
                                            if (!cancelled)
                                                update.imageGeneration.forEach(
                                                    process::onImageGeneration
                                                )
                                            if (update.toolOutput != null)
                                                process.onToolOutput(update.toolOutput)
                                            if (update.toolActive == false) {
                                                if (
                                                    update.toolName == ImageGenTool.NAME &&
                                                        update.toolContent == ImageGenTool.STATUS_OK
                                                ) {
                                                    process.dropLiveTool()
                                                } else {
                                                    process.onToolFinished(
                                                        update.toolContent,
                                                        update.toolOutcome,
                                                    )
                                                }
                                            }
                                            if (update.done) process.finishAll()
                                        }
                                        // 停止后不再把普通增量绘制到 UI；上面的终止事件仍用于闭合过程状态。
                                        if (cancelled) return@update
                                        val now = System.currentTimeMillis()
                                        val publish =
                                            update.imageGeneration.isNotEmpty() ||
                                                update.retry != null ||
                                                update.retryCleared ||
                                                update.resetAttempt ||
                                                shouldPublishStreamUpdate(
                                                    nowMs = now,
                                                    lastPublishAt = lastPublishAt,
                                                    deltaLength = update.delta.length,
                                                    thinkingDeltaLength =
                                                        update.thinkingDelta.length,
                                                    hasAttachments =
                                                        update.attachments.isNotEmpty(),
                                                    done = update.done,
                                                    searchingChanged = update.searching != null,
                                                    searchPagesDelta = update.searchPagesDelta,
                                                    toolChanged =
                                                        update.toolActive != null ||
                                                            update.toolRecord != null ||
                                                            update.coloredBubbles.isNotEmpty() ||
                                                            update.subagentJson.isNotEmpty() ||
                                                            update.displayedImages.isNotEmpty(),
                                                    toolOutputChanged = update.toolOutput != null,
                                                    toolCallDeltaLength =
                                                        update.toolCallDeltas.sumOf {
                                                            it.name.length +
                                                                it.argumentsDelta.length
                                                        },
                                                    publishedBodyLength = publishedBodyLength,
                                                    publishedThinking = publishedThinking,
                                                    deferNonEssential = imeAnimating.get(),
                                                    userActivelyScrolling =
                                                        streamingDeferActive.get(),
                                                    hasUsage = update.usage != null,
                                                )
                                        if (!publish) return@update
                                        lastPublishAt = now
                                        publishedBodyLength = streamedText.length
                                        val snapshot = process.snapshot()
                                        publishedThinking =
                                            snapshot.any {
                                                it.kind == ProcessEventKind.THINKING &&
                                                    it.content.isNotBlank()
                                            }
                                        val text = streamedText
                                        val attachments = streamedAttachments
                                        val usage = streamedUsage
                                        val liveTokens =
                                            liveTokenEstimator.estimateOnWorker(
                                                enabled = ThemeStore.contextRing,
                                                streamedText = text,
                                                process = snapshot,
                                                toolCallText = streamedToolCalls.toString(),
                                            )
                                        withContext(Dispatchers.Main.immediate) {
                                            if (requestCancellation.isCancelled())
                                                return@withContext
                                            replaceInConversation(
                                                requestConversationId,
                                                reply.id,
                                                reply.copy(
                                                    text = text,
                                                    process = snapshot,
                                                    attachments = attachments,
                                                    usage = usage,
                                                    toolRecords = toolRecords,
                                                    retryStatus = retryStatus,
                                                    finishedAt =
                                                        if (
                                                            update.done && snapshot.none { it.live }
                                                        ) {
                                                            System.currentTimeMillis()
                                                        } else {
                                                            0L
                                                        },
                                                    isPending =
                                                        retryStatus == null &&
                                                            toolRecords.isEmpty() &&
                                                            !hasReplyContent(
                                                                text,
                                                                attachments,
                                                                snapshot,
                                                            ),
                                                    isStreaming =
                                                        (!update.done &&
                                                            !requestCancellation.isCancelled()) ||
                                                            snapshot.any { it.live },
                                                ),
                                            )
                                            if (
                                                usage != null &&
                                                    currentConversation.id == requestConversationId
                                            ) {
                                                currentConversation =
                                                    currentConversation.copy(tokenUsage = usage)
                                            }
                                            if (
                                                liveTokens != null &&
                                                    currentConversation.id == requestConversationId
                                            ) {
                                                liveContextEstimate =
                                                    requestConversationId to
                                                        (baseContextTokens + liveTokens.first)
                                                liveToolTokens =
                                                    requestConversationId to liveTokens.second
                                            }
                                        }
                                    },
                            )
                        }
                    }
                // 确保所有进行中的事件都正确闭合（包括停止时的思考块）
                val now = System.currentTimeMillis()
                process.finishAll(now)
                val snapshot = process.snapshot()
                // 停止/报错/正常结束都必须让过程事件闭合：仍在进行中的事件如果带着 live=true 落库，
                // 重新进入对话时会一直显示"思考中"。正常结束时 finishAll 已经收尾，这里只兜底。
                val closedProcess = snapshot.map { it.copy(live = false) }
                val current =
                    if (currentConversation.id == requestConversationId) {
                        messages.firstOrNull { it.id == reply.id }
                    } else {
                        conversationContent(requestConversationId)?.messages?.firstOrNull {
                            it.id == reply.id
                        }
                    }
                val finalProcess = closedProcess
                val finalUsage = result.usage ?: streamedUsage ?: current?.usage
                val finishedAt =
                    current?.finishedAt?.takeIf { it > 0L } ?: System.currentTimeMillis()
                val finalMessage =
                    when {
                        result.stopped -> {
                            val finalText = streamedText.ifBlank { current?.text.orEmpty() }
                            val finalAttachments =
                                streamedAttachments.ifEmpty { current?.attachments.orEmpty() }
                            if (
                                retryStatus == null &&
                                    toolRecords.isEmpty() &&
                                    !hasReplyContent(finalText, finalAttachments, finalProcess)
                            ) {
                                null
                            } else {
                                reply.copy(
                                    text = finalText,
                                    process = finalProcess.map { it.copy(live = false) },
                                    attachments = finalAttachments,
                                    usage = finalUsage,
                                    toolRecords = toolRecords,
                                    retryStatus =
                                        retryStatus?.copy(
                                            running = false,
                                            remainingSeconds = null,
                                            stopped = true,
                                            downgradedToHttp1 = false,
                                        ),
                                    isError = retryStatus != null,
                                    finishedAt = finishedAt,
                                    isPending = false,
                                    isStreaming = false,
                                )
                            }
                        }
                        result.error != null ->
                            reply.copy(
                                text = streamedText,
                                process = finalProcess.map { it.copy(live = false) },
                                toolRecords = toolRecords,
                                attachments = streamedAttachments,
                                retryStatus =
                                    retryStatus ?: ChatRetryStatus(result.error, stopped = true),
                                usage = finalUsage,
                                finishedAt = finishedAt,
                                isError = true,
                                isPending = false,
                                isStreaming = false,
                            )
                        else ->
                            reply.copy(
                                text = settledAssistantText(streamedText, result.content),
                                process = finalProcess.map { it.copy(live = false) },
                                toolRecords = toolRecords,
                                attachments = result.attachments.ifEmpty { streamedAttachments },
                                usage = finalUsage,
                                finishedAt = finishedAt,
                                isPending = false,
                                isStreaming = false,
                            )
                    }
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    if (!clearingHistory) {
                        Snapshot.withMutableSnapshot {
                            if (finalMessage == null) {
                                ReplyLiveNotice.dismiss(context, requestConversationId)
                                if (currentConversation.id == requestConversationId) {
                                    messages = messages.filterNot { it.id == reply.id }
                                    persistCurrent(messages)
                                } else {
                                    val stored = conversationContent(requestConversationId)
                                    if (stored != null) {
                                        val updated =
                                            stored.copy(
                                                messages =
                                                    stored.messages.filterNot { it.id == reply.id }
                                            )
                                        cacheConversationContent(updated)
                                        ConversationStore.save(context, updated)
                                    }
                                }
                            } else {
                                replaceInConversation(
                                    requestConversationId,
                                    reply.id,
                                    finalMessage,
                                    persist = true,
                                )
                                if (
                                    finalMessage.usage != null &&
                                        currentConversation.id == requestConversationId
                                ) {
                                    currentConversation =
                                        currentConversation.copy(tokenUsage = finalMessage.usage)
                                }
                                // 首轮成功回复后生成 AI 标题（异步，不阻塞 UI）
                                if (result.error == null && !result.stopped) {
                                    maybeGenerateTitle(
                                        conversationId = requestConversationId,
                                        userText =
                                            requestSource
                                                .lastOrNull { it.fromUser && !it.hidden }
                                                ?.text
                                                .orEmpty(),
                                        assistantText = finalMessage.text,
                                    )
                                }
                                val completed = result.error == null && !result.stopped
                                if (completed) {
                                    val preview =
                                        replyNotificationPreview(finalMessage.text).ifEmpty {
                                            if (finalMessage.attachments.any { it.isImage })
                                                tr("已生成图片")
                                            else ""
                                        }
                                    pushNotice(preview.ifEmpty { tr("完成") }, finished = true)
                                } else {
                                    ReplyLiveNotice.dismiss(context, requestConversationId)
                                }
                                if (
                                    result.error == null &&
                                        !result.stopped &&
                                        currentConversation.id == requestConversationId &&
                                        SpeechStore.loadAutoRead(context)
                                ) {
                                    val spoken = speechPlainText(finalMessage.visibleText())
                                    if (spoken.isNotBlank()) {
                                        ensureSpeechNotification()
                                        SpeechPlayer.speak(context, finalMessage.id, spoken)
                                    }
                                }
                            }
                        }
                        appliedFinal = true
                    }
                    generations = generations - reply.id
                    flushBackgroundNotices(requestConversationId)
                }
            } finally {
                if (uploadMessageId != null)
                    attachmentUploads.finish(requestConversationId, uploadMessageId, reply.id)
                if (!appliedFinal) {
                    generations = generations - reply.id
                    flushBackgroundNotices(requestConversationId)
                }
            }
        }
    generations =
        generations +
            (reply.id to
                ActiveGeneration(
                    conversationId = requestConversationId,
                    job = job,
                    cancellation = requestCancellation,
                ))
}

internal data class ActiveGeneration(
    val conversationId: String,
    val job: Job,
    val cancellation: ChatRequestCancellation,
)

private fun mergeProcess(
    current: List<ProcessEvent>,
    snapshot: List<ProcessEvent>,
): List<ProcessEvent> {
    if (snapshot.isEmpty()) return current.map { it.copy(live = false) }
    if (current.isEmpty()) return snapshot
    val byId = current.associateBy { it.id }
    return snapshot.map { event ->
        val previous = byId[event.id]
        if (previous == null) {
            event
        } else {
            event.copy(
                content = event.content.ifBlank { previous.content },
                millis = maxOf(event.millis, previous.millis),
                pages = maxOf(event.pages, previous.pages),
            )
        }
    }
}
