package com.irio

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentTest {
    @Test
    fun snapshotRoundTripKeepsNameStateAndLines() {
        val original = SubagentSnapshot(
            id = "agent-1",
            conversationId = "chat-1",
            name = "检索",
            createdAt = 1_700_000_000_000L,
            state = SubagentState.RUNNING,
            lines = listOf(SubagentLine(true, "查找资料"), SubagentLine(false, "正在查")),
        )
        val restored = subagentSnapshot(original.toJson())
        assertEquals(original, restored)
    }

    @Test
    fun snapshotRoundTripKeepsToolHint() {
        val hint = ProcessEvent(
            id = 2,
            kind = ProcessEventKind.TOOL,
            content = "12:00",
            belowText = true,
            toolName = TimeTool.NAME,
            textOffset = 4,
            displayText = "查询当地时间",
        )
        val original = SubagentSnapshot(
            id = "agent-1",
            conversationId = "chat-1",
            name = "检索",
            createdAt = 1_700_000_000_000L,
            state = SubagentState.COMPLETED,
            lines = listOf(SubagentLine(true, "查找资料"), SubagentLine(false, "先查一下", listOf(hint))),
            liveProcess = listOf(hint.copy(id = 3, live = false, displayText = "正在搜索")),
        )
        assertEquals(original, subagentSnapshot(original.toJson()))
    }

    @Test
    fun turnKeepsToolHintAnchoredBetweenSpeeches() {
        val turn = SubagentTurn()
        assertTrue(turn.apply(ChatStreamUpdate(delta = "先查一下")))
        assertTrue(turn.apply(ChatStreamUpdate(toolActive = true, toolName = TimeTool.NAME, displayText = "查询当地时间")))
        assertTrue(turn.apply(ChatStreamUpdate(toolActive = false, toolName = TimeTool.NAME, toolContent = "12:00")))
        turn.apply(ChatStreamUpdate(newRound = true))
        turn.apply(ChatStreamUpdate(delta = "现在是中午"))
        turn.finish()
        val event = turn.process().single { it.kind == ProcessEventKind.TOOL }
        assertEquals("查询当地时间", event.displayText)
        assertEquals("12:00", event.content)
        assertEquals("先查一下".length, event.textOffset)
        assertFalse(event.live)
        assertEquals(
            listOf("先查一下", "查询当地时间", "\n\n现在是中午"),
            interleaveTranscript(turn.text(), turn.process()).map { piece ->
                when (piece) {
                    is TranscriptPiece.Text -> piece.text
                    is TranscriptPiece.Event -> piece.event.displayText
                }
            },
        )
    }

    @Test
    fun toolOnlyTurnStillProducesAHintLine() {
        val turn = SubagentTurn()
        turn.apply(ChatStreamUpdate(toolActive = true, toolName = TimeTool.NAME, displayText = "查询当地时间"))
        turn.apply(ChatStreamUpdate(toolActive = false, toolName = TimeTool.NAME, toolContent = "12:00"))
        turn.finish()
        val line = settledSubagentLine(turn.text(), turn.process())
        assertEquals("", line?.text)
        assertEquals("查询当地时间", line?.process?.single()?.displayText)
        assertFalse(line?.process?.single()?.live == true)
    }

    @Test
    fun finalOutputIncludesColoredBubblesWhenThereIsNoOrdinaryText() {
        val snapshot = SubagentSnapshot(
            id = "agent-1",
            conversationId = "chat-1",
            name = "气泡助手",
            createdAt = 1L,
            state = SubagentState.COMPLETED,
            lines = listOf(
                SubagentLine(
                    fromUser = false,
                    text = "",
                    process = listOf(
                        ProcessEvent(1, ProcessEventKind.BUBBLE, content = "第一段", belowText = true, bubbleColor = "#123456"),
                        ProcessEvent(2, ProcessEventKind.BUBBLE, content = "第二段", belowText = true, bubbleColor = "#654321"),
                    ),
                ),
            ),
        )
        assertEquals("第一段\n\n第二段", snapshot.finalOutputText())
    }

    @Test
    fun mergedRenderCollapsesToolHintsOntoTheFirstAnchor() {
        val events = listOf(
            ProcessEvent(1, ProcessEventKind.TOOL, toolName = TimeTool.NAME, displayText = "查询当地时间"),
            ProcessEvent(2, ProcessEventKind.SEARCH, pages = 2),
        )
        assertEquals(events, renderedProcessEvents(events, mergeToolCalls = false))
        val merged = renderedProcessEvents(events, mergeToolCalls = true).single()
        assertEquals(MERGED_TOOL_CALLS_NAME, merged.toolName)
        assertEquals("搜索了 2 个网页，查看了 1 次时间", merged.displayText)
    }

    @Test
    fun adjacentSubagentsShareOneRowUntilTextBreaksThem() {
        val first = ProcessEvent(1, ProcessEventKind.SUBAGENT, content = "a")
        val second = ProcessEvent(2, ProcessEventKind.SUBAGENT, content = "b")
        val tool = ProcessEvent(3, ProcessEventKind.TOOL, toolName = TimeTool.NAME)
        val third = ProcessEvent(4, ProcessEventKind.SUBAGENT, content = "c")
        val rows = transcriptRenderRows(
            listOf(
                TranscriptPiece.Event(first),
                TranscriptPiece.Event(second),
                TranscriptPiece.Text("然后"),
                TranscriptPiece.Event(tool),
                TranscriptPiece.Event(third),
            ),
        )
        val grouped = rows.filterIsInstance<TranscriptRenderRow.Subagents>()
        assertEquals(listOf(listOf(first, second), listOf(third)), grouped.map { it.events })
        assertEquals(2, rows.filterIsInstance<TranscriptRenderRow.Single>().size)
    }

    @Test
    fun finishQueueKeepsOneCompletionPerSubagent() {
        val queue = SubagentFinishQueue()
        val done = SubagentSnapshot("a", "chat", "检索", 1L, SubagentState.COMPLETED)
        assertTrue(queue.add(done))
        assertFalse(queue.add(done.copy(lines = listOf(SubagentLine(false, "结果")))))
        assertFalse(queue.add(done.copy(state = SubagentState.RUNNING)))
        assertEquals(listOf(done), queue.take("chat"))
        assertTrue(queue.take("chat").isEmpty())
        assertTrue(queue.add(SubagentSnapshot("b", "chat", "另一个", 2L, SubagentState.FAILED)))
        assertEquals("b", queue.take("chat").single().id)
    }

    @Test
    fun childRegistryCannotCreateSubagents() {
        val registry = ToolRegistry(listOf(TimeToolProvider(), SubagentToolProvider())).withoutSubagents()
        assertTrue(registry.handles(TimeTool.NAME))
        assertFalse(registry.handles(SubagentTools.CREATE))
        assertFalse(registry.handles(SubagentTools.MESSAGE))
        assertFalse(registry.handles(SubagentTools.STOP))
    }

    @Test
    fun createToolSkipsSmartDisplayText() {
        val decorated = ToolRegistry(listOf(SubagentToolProvider()), smartToolHints = true)
            .definitions(ProviderProtocol.CHAT_COMPLETIONS)
        val create = decorated.first { it.getJSONObject("function").getString("name") == SubagentTools.CREATE }
        val message = decorated.first { it.getJSONObject("function").getString("name") == SubagentTools.MESSAGE }
        assertFalse(create.getJSONObject("function").getJSONObject("parameters").getJSONObject("properties").has("_display_text"))
        val inspect = decorated.first { it.getJSONObject("function").getString("name") == SubagentTools.INSPECT }
        assertFalse(inspect.getJSONObject("function").getJSONObject("parameters").getJSONObject("properties").has("_display_text"))
        assertTrue(message.getJSONObject("function").getJSONObject("parameters").getJSONObject("properties").has("_display_text"))
    }

    @Test
    fun blankSnapshotIsIgnored() {
        assertEquals(null, subagentSnapshot(JSONObject().put("name", "x").toString()))
    }
}
