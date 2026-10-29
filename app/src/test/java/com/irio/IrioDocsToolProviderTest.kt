package com.irio

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class IrioDocsToolProviderTest {
    private val tool = McpTool("search_irio_docs", "Search docs", JSONObject("""{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}"""))
    @Test fun staleCallsRespectGlobalDisableAndCancellationWithoutInvokingRemote() = runBlocking {
        var invoked = 0
        var enabled = false
        val updates = mutableListOf<ChatStreamUpdate>()
        val provider = IrioDocsMcpToolProvider(listOf(tool), enabled = { enabled }) { _, _, _ -> invoked++; "source" }
        val cancellation = ChatRequestCancellation()
        val context = ToolExecutionContext(ProviderConfig("x", "X", "https://example.test", ""), "model", cancellation = cancellation, onUpdate = { updates += it })
        val call = ChatToolCall("docs", "irio_docs_mcp_search_irio_docs", """{"query":"压缩"}""")
        assertEquals(ToolOutcome.FAILED, provider.execute(call, context).outcome)
        assertEquals(0, invoked)
        enabled = true; cancellation.cancel()
        assertEquals(ToolOutcome.STOPPED, provider.execute(call, context).outcome)
        assertEquals(0, invoked)
        assertEquals(listOf(true, false, true, false), updates.map { it.toolActive })
    }
    @Test fun successfulQueriesPreserveSourceAndFinishOneToolStatus() = runBlocking {
        val registry = ToolRegistry().register(IrioDocsMcpToolProvider(listOf(tool)) { name, args, _ ->
            assertEquals("search_irio_docs", name); assertEquals("压缩", args.getString("query"))
            "Source: https://irio.mirikawww.workers.dev/guide/"
        })
        val updates = mutableListOf<ChatStreamUpdate>()
        val result = registry.execute(ChatToolCall("docs1", "irio_docs_mcp_search_irio_docs", """{"query":"压缩"}"""),
            ToolExecutionContext(ProviderConfig("x", "X", "https://example.test", ""), "model", onUpdate = { updates += it }))
        assertTrue(result.text.contains("Source:"))
        assertEquals(ToolOutcome.SUCCESS, result.outcome)
        assertEquals(listOf(true, false), updates.map { it.toolActive })
        assertEquals(result.text, updates.last().toolContent)
        assertEquals(ToolOutcome.SUCCESS, updates.last().toolOutcome)
    }
}
