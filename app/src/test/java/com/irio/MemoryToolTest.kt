package com.irio

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryToolTest {
    @Test
    fun labelsMatchToolNames() {
        assertEquals("获取时间", MemoryTool.liveLabel(TimeTool.NAME))
        assertEquals("浏览网页中", MemoryTool.liveLabel(WebFetchTool.NAME))
        assertEquals("读取记忆", MemoryTool.liveLabel(MemoryTool.GET))
        assertEquals("写入记忆", MemoryTool.liveLabel(MemoryTool.UPDATE))
        assertEquals("已获取时间", MemoryTool.doneLabel(TimeTool.NAME))
        assertEquals("已浏览网页", MemoryTool.doneLabel(WebFetchTool.NAME))
        assertEquals("已读取记忆", MemoryTool.doneLabel(MemoryTool.GET))
        assertEquals("已写入记忆", MemoryTool.doneLabel(MemoryTool.UPDATE))
    }

    @Test
    fun toolOutputReplacesTheLiveTranscriptUntilFinish() {
        val tracker = ProcessTracker()
        tracker.onToolStarted(LinuxShellTool.NAME)
        tracker.onToolOutput("$ echo hi")
        val live = tracker.snapshot().single()
        assertEquals(LinuxShellTool.NAME, live.toolName)
        assertTrue(live.live)
        assertEquals("$ echo hi", live.content)
        tracker.onToolOutput("$ echo hi\nhi")
        assertEquals("$ echo hi\nhi", tracker.snapshot().single().content)
        assertTrue(tracker.snapshot().single().live)
        tracker.onToolFinished()
        val done = tracker.snapshot().single()
        assertFalse(done.live)
        assertEquals("$ echo hi\nhi", done.content)
    }

    @Test
    fun getSchemaHasNoParameters() {
        ProviderProtocol.entries.forEach { protocol ->
            val schema = MemoryTool.getSchema(protocol)
            val params: JSONObject = when (protocol) {
                ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.MINIMAX_VIDEO -> schema.getJSONObject("function").getJSONObject("parameters")
                ProviderProtocol.RESPONSES -> schema.getJSONObject("parameters")
                ProviderProtocol.ANTHROPIC -> schema.getJSONObject("input_schema")
                ProviderProtocol.GEMINI -> schema.getJSONObject("parametersJsonSchema")
                ProviderProtocol.GEMINI_INTERACTIONS -> schema.getJSONObject("parameters")
            }
            assertEquals(0, params.optJSONObject("properties")?.length() ?: 0)
            assertEquals("object", params.optString("type"))
            assertNotNull(params.optJSONArray("required"))
        }
    }

    @Test
    fun updateSchemaRequiresContent() {
        ProviderProtocol.entries.forEach { protocol ->
            val schema = MemoryTool.updateSchema(protocol)
            val params: JSONObject = when (protocol) {
                ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.MINIMAX_VIDEO -> schema.getJSONObject("function").getJSONObject("parameters")
                ProviderProtocol.RESPONSES -> schema.getJSONObject("parameters")
                ProviderProtocol.ANTHROPIC -> schema.getJSONObject("input_schema")
                ProviderProtocol.GEMINI -> schema.getJSONObject("parametersJsonSchema")
                ProviderProtocol.GEMINI_INTERACTIONS -> schema.getJSONObject("parameters")
            }
            assertTrue(params.getJSONObject("properties").has("content"))
            assertEquals("content", params.getJSONArray("required").getString(0))
        }
    }

    @Test
    fun schemaNamesAreStable() {
        ProviderProtocol.entries.forEach { protocol ->
            val get = MemoryTool.getSchema(protocol)
            val update = MemoryTool.updateSchema(protocol)
            val getName = when (protocol) {
                ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.MINIMAX_VIDEO -> get.getJSONObject("function").optString("name")
                else -> get.optString("name")
            }
            val updateName = when (protocol) {
                ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.MINIMAX_VIDEO -> update.getJSONObject("function").optString("name")
                else -> update.optString("name")
            }
            assertEquals("get_memory", getName)
            assertEquals("update_memory", updateName)
        }
    }
}
