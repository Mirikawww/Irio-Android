package com.irio

import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolContextTest {
    @Test
    fun aStoppedLaterCallOfTheSameToolKeepsItsPartialTranscript() {
        val completed = "$ echo first\nfirst"
        val partial = "$ long-command\npartial-output"
        val reply = ChatMessage(1L, "", false,
            toolRecords = listOf(ChatToolRecord(ChatToolCall("c", LinuxShellTool.NAME, "{}"), completed)),
            process = listOf(
                ProcessEvent(1L, ProcessEventKind.TOOL, content = completed, toolName = LinuxShellTool.NAME),
                ProcessEvent(2L, ProcessEventKind.TOOL, content = partial, toolName = LinuxShellTool.NAME),
            ))
        val text = modelRequestMessages(listOf(reply)).joinToString("\n") { it.text }
        assertTrue(text.contains(partial))
        assertEquals(1, Regex(Regex.escape(completed)).findAll(text).count())
    }

    @Test
    fun toolRecordsSurviveAFailedFollowupRequestWithoutSendingTheLocalError() {
        val failed = ChatMessage(
            2L, "HTTP 500 local-error", false, isError = true,
            toolRecords = listOf(ChatToolRecord(ChatToolCall("c", "get_time", "{}"), "actual-time")),
        )
        val history = contextMessages(listOf(ChatMessage(1L, "时间", true), failed))
            .filterNot { it.isError || it.isPending }
        val request = modelRequestMessages(history)
        assertTrue(request.any { it.text.contains("actual-time") })
        assertFalse(request.any { it.text.contains("local-error") })
        assertTrue(failed.isError)
    }

    @Test
    fun independentRecordsRoundTripWithArgumentsAndNoDuplicateUiResults() {
        val records = listOf(
            ChatToolRecord(ChatToolCall("c1", "get_time", "{}"), "time-output"),
            ChatToolRecord(ChatToolCall("c2", WebSearchTool.NAME, """{"query":"actual-query"}""", "gemini-id"), "search-output"),
            ChatToolRecord(ChatToolCall("c3", "get_memory", "{}"), ""),
            ChatToolRecord(ChatToolCall("c4", WebSearchTool.NAME, """{"query":"second-query"}"""), "second-output"),
        )
        val reply = ChatMessage(2L, "完成", false, toolRecords = records,
            process = listOf(ProcessEvent(1L, ProcessEventKind.SEARCH, content = "search-output")))
        val original = Conversation("records", "测试", 1L, listOf(ChatMessage(1L, "执行", true), reply))
        val local = ConversationStore.parseConversation(JSONObject(ConversationStore.conversationJson(original).toString()))
        val linked = LinkConversationJson.decode(JSONObject(LinkConversationJson.encode(local).toString()))
        assertEquals(records, local.messages[1].toolRecords)
        assertEquals(records, linked.messages[1].toolRecords)
        val request = modelRequestMessages(linked.messages)
        val text = request.joinToString("\n") { it.text + it.toolCalls.joinToString { call -> call.name + call.arguments } }
        assertTrue(text.contains("get_memory"))
        assertTrue(text.contains("actual-query"))
        assertEquals(1, Regex("search-output").findAll(text).count())
        assertTrue(text.indexOf("actual-query") < text.indexOf("second-query"))
        val summary = summaryTurns(planCompression(linked.messages)!!)[1].text
        records.forEach { record -> assertTrue(summary.contains(record.call.arguments)); assertTrue(summary.contains(record.result)) }
        assertEquals(records.map { it.result }, request.filter { it.role == "tool" }.map { it.text })
        assertEquals("完成", request.single { it.role == "assistant" }.text)
    }

    @Test
    fun shellReturnsTheSameCommandTranscriptThatTheUiReceives() = runBlocking {
        val updates = mutableListOf<ChatStreamUpdate>()
        val result = LinuxShellToolProvider().execute(
            ChatToolCall("shell1", LinuxShellTool.NAME, """{"commands":["echo first","echo second"]}"""),
            ToolExecutionContext(
                provider = ProviderConfig("p", "t", "http://localhost", ""), model = "m",
                onUpdate = { updates += it },
            ),
        )
        assertEquals(updates.last().toolContent, result.text)
        assertTrue(result.text.contains("$ echo first"))
        assertTrue(result.text.contains("$ echo second"))
    }

    @Test
    fun reopenedShellTranscriptIsIncludedInTheNextRequestAndSummary() {
        val transcript = linuxSandboxTerminalTranscript("printf hello; exit 7", "hello", exitCode = 7)
        val reply = ChatMessage(
            id = 2L,
            text = "命令已执行",
            fromUser = false,
            process = listOf(ProcessEvent(1L, ProcessEventKind.TOOL, content = transcript, toolName = LinuxShellTool.NAME)),
        )
        val original = Conversation("tools", "测试", 1L, listOf(ChatMessage(1L, "执行命令", true), reply))
        val local = ConversationStore.parseConversation(JSONObject(ConversationStore.conversationJson(original).toString()))
        val reopened = LinkConversationJson.decode(JSONObject(LinkConversationJson.encode(local).toString()))
        val history = contextMessages(reopened.messages) + ChatMessage(3L, "刚才运行了什么？", true)
        val request = modelRequestMessages(history)
        val toolText = request.drop(1).joinToString("\n") { it.text }
        assertTrue(toolText.contains(LinuxShellTool.NAME))
        assertTrue(toolText.contains(transcript))
        assertTrue(toolText.contains(reply.text))
        assertEquals(reply.text, reopened.messages[1].text)
        val summary = summaryTurns(planCompression(reopened.messages)!!)[1].text
        assertTrue(summary.contains(transcript))
        assertTrue(summary.contains(reply.text))
        assertEquals(reply.text, request.single { it.role == "assistant" }.text)
        assertTrue(estimateRequestTokens(request) > estimateRequestTokens(request.filterNot { it.text.contains(transcript) }))
    }

    @Test
    fun recordsKeepToolOrderAndDoNotReplayThinkingOrLiveProcesses() {
        val reply = ChatMessage(
            1L, "完成", false,
            process = listOf(
                ProcessEvent(1L, ProcessEventKind.THINKING, content = "private-thought"),
                ProcessEvent(2L, ProcessEventKind.TOOL, content = "$ echo first\nfirst", toolName = LinuxShellTool.NAME),
                ProcessEvent(3L, ProcessEventKind.TOOL, content = "background-status", toolName = LinuxBackgroundTool.NAME),
                ProcessEvent(4L, ProcessEventKind.SEARCH, content = "search-result", searchQuery = "actual-query"),
                ProcessEvent(5L, ProcessEventKind.TOOL, content = "not-finished", live = true, toolName = LinuxShellTool.NAME),
            ),
        )
        val text = modelRequestMessages(listOf(reply)).joinToString("\n") { it.text }
        assertTrue(text.contains(LinuxShellTool.NAME))
        assertTrue(text.contains(LinuxBackgroundTool.NAME))
        assertTrue(text.indexOf("first") < text.indexOf("background-status"))
        assertTrue(text.indexOf("background-status") < text.indexOf("search-result"))
        assertTrue(text.contains("actual-query"))
        assertFalse(text.contains("private-thought"))
        assertFalse(text.contains("not-finished"))
    }
}
