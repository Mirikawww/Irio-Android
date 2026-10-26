package com.irio

data class ChatRetryOptions(
    val enabled: Boolean = true,
    val maxRetries: Int = 5,
    val cooldownSeconds: Int = 10,
) {
    val limit: Int get() = maxRetries.coerceIn(1, 20)
    val cooldown: Int get() = cooldownSeconds.coerceIn(0, 300)
}

data class ChatRetryStatus(
    val error: String,
    val attempt: Int = 0,
    val remainingSeconds: Int? = null,
    val running: Boolean = false,
    val stopped: Boolean = false,
    /** Only the attempt that actually changes transport carries this marker. */
    val downgradedToHttp1: Boolean = false,
)

internal fun retryableHttpStatus(status: Int): Boolean =
    status in setOf(408, 409, 425, 429) || status in 500..599

internal fun retryableConnectionFailure(error: Throwable): Boolean {
    if (error !is java.io.IOException) return false
    var cause: Throwable? = error
    while (cause != null) {
        if (cause is java.security.cert.CertificateException || cause is javax.net.ssl.SSLPeerUnverifiedException) return false
        cause = cause.cause?.takeIf { it !== cause }
    }
    return true
}

internal fun retryableProviderError(payload: String): Boolean = runCatching {
    val root = org.json.JSONObject(payload)
    val error = root.optJSONObject("error") ?: root.optJSONObject("response")?.optJSONObject("error") ?: root
    val code = error.optString("code").lowercase()
    val status = error.optInt("status", error.optInt("code", 0))
    if (status in 400..599) return@runCatching retryableHttpStatus(status)
    val kind = "${error.optString("type")} $code".lowercase()
    listOf("authentication", "invalid_api_key", "permission", "invalid_request", "invalid_argument",
        "not_found", "context_length", "insufficient_quota", "billing", "content_policy", "safety").none { it in kind }
}.getOrDefault(true)

internal fun chatRetryLine(status: ChatRetryStatus): String {
    val state = when {
        status.running -> tr("重试中")
        status.remainingSeconds != null -> "${status.remainingSeconds}s"
        else -> tr("已停止重试")
    }
    return "${tr("重试")} ${status.attempt} - $state" +
        if (status.running && status.downgradedToHttp1) " - ${tr("降级 HTTP/1.1")}" else ""
}

internal const val STREAM_CONTINUATION_PROMPT =
    "The previous response was interrupted. Continue writing it as one seamless answer, starting with the exact next words that naturally follow the last visible words, even in the middle of a sentence. Do not repeat, paraphrase, summarize, or restart earlier text. Do not acknowledge this instruction or add a preface such as 'Okay, continuing' or '好的，继续'. Do not insert a separator or a new introduction. If no answer text was produced yet, continue the original task directly. Tools remain available; any incomplete tool call in the interrupted stream was not executed, so issue a complete call if still needed. Preserve already completed tool results."

/** Request-local messages only: never insert these synthetic user turns into the UI/store. */
internal fun streamContinuationMessages(
    messages: List<ChatRequestMessage>,
    result: ChatCompletionResult,
): List<ChatRequestMessage> = buildList {
    addAll(messages)
    if (result.partialStreamText.isNotEmpty()) {
        add(ChatRequestMessage(role = "assistant", text = result.partialStreamText))
    }
    add(ChatRequestMessage(role = "user", text = STREAM_CONTINUATION_PROMPT))
}

internal fun chatRetryJson(status: ChatRetryStatus): org.json.JSONObject = org.json.JSONObject().apply {
    put("error", status.error)
    put("attempt", status.attempt)
}

/** Reopening a conversation must never restart an interrupted retry timer. */
internal fun chatRetryFromJson(json: org.json.JSONObject?): ChatRetryStatus? = json?.let {
    ChatRetryStatus(it.optString("error"), it.optInt("attempt").coerceAtLeast(0), stopped = true)
}

/** One budget per generation. Continue partial chat rounds; only empty attempts are replayed. */
internal class ChatRetryController(
    private val options: ChatRetryOptions,
    private val cancellation: ChatRequestCancellation,
    private val onUpdate: suspend (ChatStreamUpdate) -> Unit,
    private val downgradeToHttp1: () -> Boolean,
    private val isEnabled: () -> Boolean = { options.enabled },
) {
    private var used = 0

    suspend fun runRound(
        request: suspend (suspend (ChatStreamUpdate) -> Unit) -> ChatCompletionResult,
    ): ChatCompletionResult = run(null) { _, update -> request(update) }

    suspend fun runChatRound(
        originalMessages: List<ChatRequestMessage>,
        request: suspend (List<ChatRequestMessage>, suspend (ChatStreamUpdate) -> Unit) -> ChatCompletionResult,
    ): ChatCompletionResult = run(originalMessages, request)

    private suspend fun run(
        originalMessages: List<ChatRequestMessage>?,
        request: suspend (List<ChatRequestMessage>, suspend (ChatStreamUpdate) -> Unit) -> ChatCompletionResult,
    ): ChatCompletionResult {
        if (!options.enabled) {
            return if (cancellation.isCancelled()) ChatCompletionResult(stopped = true)
            else request(originalMessages.orEmpty(), onUpdate)
        }
        var retrying = false
        var retryVisible = false
        var retryMessages = originalMessages.orEmpty()
        var completedPartialPrefix = ""
        while (true) {
            if (cancellation.isCancelled()) return ChatCompletionResult(stopped = true)
            onUpdate(ChatStreamUpdate(attemptStarted = true))
            var producedOutput = false
            val attemptText = StringBuilder()
            val rawResult = request(retryMessages) { update ->
                attemptText.append(update.delta)
                val hasOutput = update.delta.isNotEmpty() || update.thinkingDelta.isNotEmpty() ||
                    update.attachments.isNotEmpty() || update.toolCallDeltas.isNotEmpty() || update.imageGeneration.isNotEmpty()
                if (hasOutput) producedOutput = true
                if (retryVisible && hasOutput) {
                    retryVisible = false
                    onUpdate(update.copy(retryCleared = true))
                } else {
                    onUpdate(update)
                }
            }
            val result = rawResult.copy(
                partialStreamText = rawResult.partialStreamText.ifEmpty {
                    if (rawResult.error != null || rawResult.stopped) attemptText.toString() else ""
                },
            )
            val failedResult = result.copy(
                content = completedPartialPrefix + result.partialStreamText,
                partialStreamText = completedPartialPrefix + result.partialStreamText,
            )
            if (result.stopped || cancellation.isCancelled()) return failedResult.copy(stopped = true)
            val error = result.error
            if (error == null) {
                if (retrying && retryVisible) onUpdate(ChatStreamUpdate(retryCleared = true))
                return result.copy(content = completedPartialPrefix + result.content)
            }
            val canRetry = result.retryable && options.enabled && isEnabled() && used < options.limit
            if (!canRetry) {
                onUpdate(ChatStreamUpdate(retry = ChatRetryStatus(error, used, stopped = true)))
                return failedResult
            }
            if (originalMessages != null && (producedOutput || result.hasPartialOutput ||
                result.partialStreamText.isNotEmpty() || result.thinking.isNotEmpty() || result.toolCalls.isNotEmpty())) {
                retryMessages = streamContinuationMessages(retryMessages, result)
                completedPartialPrefix += result.partialStreamText
            }
            val next = used + 1
            retryVisible = true
            for (seconds in options.cooldown downTo 1) {
                if (!isEnabled()) {
                    onUpdate(ChatStreamUpdate(retry = ChatRetryStatus(error, used, stopped = true)))
                    return failedResult
                }
                onUpdate(ChatStreamUpdate(retry = ChatRetryStatus(error, next, remainingSeconds = seconds)))
                if (!cancellation.waitForRetry(1_000)) return failedResult.copy(stopped = true)
            }
            if (cancellation.isCancelled()) return failedResult.copy(stopped = true)
            if (!isEnabled()) {
                onUpdate(ChatStreamUpdate(retry = ChatRetryStatus(error, used, stopped = true)))
                return failedResult
            }
            used = next
            retrying = true
            val downgraded = result.http1Fallback && downgradeToHttp1()
            onUpdate(ChatStreamUpdate(
                resetAttempt = originalMessages == null,
                retry = ChatRetryStatus(error, used, running = true, downgradedToHttp1 = downgraded),
            ))
        }
    }
}

/** A new user turn dismisses old failure UI, without throwing away real output or tool records. */
internal fun dismissReplyErrors(messages: List<ChatMessage>): List<ChatMessage> = messages.mapNotNull { message ->
    if (!message.isError && message.retryStatus == null) message else {
        val cleared = message.copy(
            text = if (message.retryStatus == null) "" else message.text,
            isError = false,
            retryStatus = null,
        )
        cleared.takeIf { it.toolRecords.isNotEmpty() || hasReplyContent(it.text, it.attachments, it.process) }
    }
}
