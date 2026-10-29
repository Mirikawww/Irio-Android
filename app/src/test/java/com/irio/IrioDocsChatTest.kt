package com.irio

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class IrioDocsChatTest {
    @Test fun modelToolRoundCallsTheDocsTransportAndPersistsOneSourceBearingRecord() = runBlocking {
        MockWebServer().use { docs -> MockWebServer().use { model ->
            docs.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","capabilities":{"tools":{}}}}"""))
            docs.enqueue(MockResponse().setResponseCode(202))
            docs.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"Official docs fixture: https://irio.mirikawww.workers.dev/guide/"}]}}"""))
            val tool = "irio_docs_mcp_read_irio_doc"
            val call = JSONObject().put("choices", org.json.JSONArray().put(JSONObject()
                .put("delta", JSONObject().put("tool_calls", org.json.JSONArray().put(JSONObject()
                    .put("index", 0).put("id", "docs1").put("function", JSONObject().put("name", tool).put("arguments", "{\"id\":\"guide\"}")))))
                .put("finish_reason", "tool_calls")))
            model.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: $call\n\ndata: [DONE]\n\n"))
            model.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"Docs found\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
            val cancellation = ChatRequestCancellation()
            val client = IrioDocsMcpClient(endpoint = docs.url("/mcp"), cancellation = cancellation)
            val registry = ToolRegistry().register(IrioDocsMcpToolProvider(listOf(McpTool("read_irio_doc", "Read docs",
                JSONObject("""{"type":"object","properties":{"id":{"type":"string"}},"required":["id"]}""")))) { name, args, _ -> client.callTool(name, args) })
            val updates = mutableListOf<ChatStreamUpdate>()
            IrioHttp.resetForTests(IrioHttp.defaultClient())
            try {
                val result = ChatApi.streamChat(ProviderConfig("fixture", "Fixture", model.url("/v1").toString(), "model-fixture"),
                    "model", listOf(ChatRequestMessage("user", "Read Irio docs")), ReasoningEffort.OFF, false,
                    toolRegistry = registry, cancellation = cancellation, onUpdate = { updates += it })
                assertNull(result.error)
                assertEquals("Docs found", result.content)
                assertTrue(model.takeRequest().body.readUtf8().contains(tool))
                assertTrue(model.takeRequest().body.readUtf8().contains("Official docs fixture"))
                assertEquals(1, updates.mapNotNull { it.toolRecord }.size)
                assertTrue(updates.mapNotNull { it.toolRecord }.single().toString().contains("https://irio.mirikawww.workers.dev/guide/"))
                val remote = List(3) { docs.takeRequest() }
                assertTrue(remote.all { it.getHeader("Authorization") == null })
                assertEquals("read_irio_doc", JSONObject(remote.last().body.readUtf8()).getJSONObject("params").getString("name"))
            } finally { IrioHttp.resetForTests() }
        } }
    }
}
