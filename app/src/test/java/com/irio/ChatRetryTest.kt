package com.irio

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatRetryTest {
    @Test fun defaultsAndBounds() {
        assertEquals(ChatRetryOptions(true, 5, 10), ChatRetryOptions())
        assertEquals(1, ChatRetryOptions(maxRetries = -1).limit)
        assertEquals(20, ChatRetryOptions(maxRetries = 100).limit)
        assertEquals(0, ChatRetryOptions(cooldownSeconds = -1).cooldown)
        assertEquals(300, ChatRetryOptions(cooldownSeconds = 999).cooldown)
        listOf(408, 409, 425, 429, 500, 503, 599).forEach { assertTrue(retryableHttpStatus(it)) }
        listOf(400, 401, 403, 404, 422).forEach { assertFalse(retryableHttpStatus(it)) }
        assertFalse(retryableProviderError("""{"error":{"type":"authentication_error"}}"""))
        assertFalse(retryableProviderError("""{"error":{"code":400}}"""))
        assertTrue(retryableProviderError("""{"error":{"type":"overloaded_error"}}"""))
        assertTrue(retryableConnectionFailure(java.io.IOException("reset")))
        assertFalse(retryableConnectionFailure(javax.net.ssl.SSLPeerUnverifiedException("certificate")))
    }

    @Test fun initialAttemptPlusFiveRetriesThenStop() = runTest {
        var calls = 0
        val updates = mutableListOf<ChatStreamUpdate>()
        val controller = ChatRetryController(ChatRetryOptions(cooldownSeconds = 0),
            ChatRequestCancellation(), { updates += it }, { false })
        val result = controller.runRound { calls++; ChatCompletionResult(error = "offline", retryable = true) }
        assertEquals(6, calls)
        assertEquals("offline", result.error)
        assertEquals((1..5).toList(), updates.mapNotNull { it.retry }.filter { it.running }.map { it.attempt })
        assertEquals(ChatRetryStatus("offline", 5, stopped = true), updates.last().retry)
    }

    @Test fun countdownAndSuccessClearTheError() = runTest {
        var calls = 0
        val updates = mutableListOf<ChatStreamUpdate>()
        val controller = ChatRetryController(ChatRetryOptions(cooldownSeconds = 3),
            ChatRequestCancellation(), { updates += it }, { false })
        val result = controller.runRound {
            calls++
            if (calls == 1) ChatCompletionResult(error = "busy", retryable = true) else ChatCompletionResult(content = "done")
        }
        assertEquals("done", result.content)
        assertEquals(listOf(3, 2, 1), updates.mapNotNull { it.retry?.remainingSeconds })
        assertEquals(3_000L, testScheduler.currentTime)
        assertTrue(updates.any { it.resetAttempt && it.retry?.running == true })
        assertTrue(updates.last().retryCleared)
    }

    @Test fun disabledAndPermanentErrorsDoNotRetry() = runTest {
        listOf(ChatRetryOptions(enabled = false), ChatRetryOptions(cooldownSeconds = 0)).forEach { options ->
            var calls = 0
            val controller = ChatRetryController(options, ChatRequestCancellation(), {}, { false })
            controller.runRound { calls++; ChatCompletionResult(error = "unauthorized") }
            assertEquals(1, calls)
        }
        var calls = 0
        ChatRetryController(ChatRetryOptions(enabled = false), ChatRequestCancellation(), {}, { false })
            .runRound { calls++; ChatCompletionResult(error = "offline", retryable = true, http1Fallback = true) }
        assertEquals(1, calls)
    }

    @Test fun firstSuccessfulStreamTokenClearsTheErrorWithoutWaitingForTheWholeReply() = runTest {
        val updates = mutableListOf<ChatStreamUpdate>()
        var calls = 0
        val controller = ChatRetryController(ChatRetryOptions(cooldownSeconds = 0), ChatRequestCancellation(),
            { updates += it }, { false })
        controller.runRound { publish ->
            if (++calls == 1) ChatCompletionResult(error = "offline", retryable = true) else {
                publish(ChatStreamUpdate(delta = "hello"))
                assertTrue(updates.last().retryCleared)
                assertEquals("hello", updates.last().delta)
                ChatCompletionResult(content = "hello")
            }
        }
    }

    @Test fun stopInterruptsCooldownWithoutAnotherRequest() = runTest {
        val cancellation = ChatRequestCancellation()
        var calls = 0
        val controller = ChatRetryController(ChatRetryOptions(cooldownSeconds = 10), cancellation,
            { if (it.retry?.remainingSeconds == 9) cancellation.cancel() }, { false })
        val result = controller.runRound { calls++; ChatCompletionResult(error = "offline", retryable = true) }
        assertTrue(result.stopped)
        assertEquals(1, calls)
        assertEquals(1_000L, testScheduler.currentTime)
    }

    @Test fun disablingTheSettingDuringCooldownPreventsTheNextAttempt() = runTest {
        var enabled = true
        var calls = 0
        val updates = mutableListOf<ChatStreamUpdate>()
        val controller = ChatRetryController(ChatRetryOptions(), ChatRequestCancellation(),
            { updates += it; if (it.retry?.remainingSeconds == 10) enabled = false }, { false }, { enabled })
        controller.runRound { calls++; ChatCompletionResult(error = "offline", retryable = true) }
        assertEquals(1, calls)
        assertTrue(updates.last().retry!!.stopped)
    }

    @Test fun retryBudgetIsSharedAcrossToolRoundsAndDowngradeMarkerIsOneShot() = runTest {
        val updates = mutableListOf<ChatStreamUpdate>()
        var downgraded = false
        val controller = ChatRetryController(ChatRetryOptions(maxRetries = 2, cooldownSeconds = 0),
            ChatRequestCancellation(), { updates += it }, { if (downgraded) false else { downgraded = true; true } })
        var first = 0
        controller.runRound {
            first++
            if (first == 1) ChatCompletionResult(error = "reset", retryable = true, http1Fallback = true)
            else ChatCompletionResult(content = "first")
        }
        var second = 0
        controller.runRound { second++; ChatCompletionResult(error = "reset again", retryable = true, http1Fallback = true) }
        assertEquals(2, first)
        assertEquals(2, second)
        assertEquals(listOf(true, false), updates.mapNotNull { it.retry }.filter { it.running }.map { it.downgradedToHttp1 })
        assertEquals(2, updates.last().retry!!.attempt)
    }

    @Test fun labelsOnlyShowDowngradeDuringTheTransitionAttempt() {
        val status = ChatRetryStatus("error", 2, running = true, downgradedToHttp1 = true)
        assertEquals("${tr("重试")} 2 - ${tr("重试中")} - ${tr("降级 HTTP/1.1")}", chatRetryLine(status))
        assertEquals("${tr("重试")} 2 - 10s", chatRetryLine(status.copy(running = false, remainingSeconds = 10)))
        assertFalse(chatRetryLine(status.copy(running = false, stopped = true)).contains("HTTP/1.1"))
    }

    @Test fun errorsRoundTripWithoutResumingTimersAndDisappearOnNewUserTurn() {
        val status = ChatRetryStatus("offline", 5, remainingSeconds = 3, running = true)
        val message = ChatMessage(2, "partial reply", false, isError = true, retryStatus = status)
        val conversation = Conversation("retry-fixture", "fixture", 1, listOf(ChatMessage(1, "hello", true), message))
        val local = ConversationStore.parseConversation(ConversationStore.conversationJson(conversation))
        val linked = LinkConversationJson.decode(LinkConversationJson.encode(local))
        assertEquals(ChatRetryStatus("offline", 5, stopped = true), linked.messages.last().retryStatus)
        val dismissed = dismissReplyErrors(linked.messages)
        assertEquals("partial reply", dismissed.last().text)
        assertNull(dismissed.last().retryStatus)
        assertFalse(dismissed.last().isError)
        assertEquals(1, dismissReplyErrors(listOf(ChatMessage(1, "hello", true),
            ChatMessage(2, "old error", false, isError = true))).size)
        val executedTool = ProcessEvent(1, ProcessEventKind.TOOL, content = "already executed", toolName = "fixture")
        val toolOnly = message.copy(text = "", process = listOf(executedTool))
        assertEquals(listOf(executedTool), dismissReplyErrors(listOf(toolOnly)).single().process)
        val interrupted = message.copy(isError = false, isStreaming = true)
        val reopened = ConversationStore.parseConversation(ConversationStore.conversationJson(conversation.copy(messages = listOf(interrupted))))
        assertTrue(reopened.messages.single().isError)
        assertNull(dismissReplyErrors(reopened.messages).single().retryStatus)
    }

    @Test fun attemptRollbackKeepsEarlierToolEventsButDropsFailedAttemptThinking() {
        val process = ProcessTracker()
        process.onToolStarted("fixture")
        process.onToolFinished("already executed")
        val checkpoint = process.checkpoint()
        process.onThinkingDelta("discard this partial attempt")
        process.restore(checkpoint)
        assertEquals(checkpoint.events, process.snapshot())
        process.onThinkingDelta("new attempt")
        assertEquals("new attempt", process.snapshot().last().content)
    }
}
