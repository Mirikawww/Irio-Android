package com.irio

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GitHubMcpChatTest {
    @Test fun modelCallsTheDiscoveredRemoteToolAndReceivesItsRealResult() = runBlocking {
        MockWebServer().use { mcp ->
            MockWebServer().use { model ->
                mcp.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","capabilities":{"tools":{}}}}"""))
                mcp.enqueue(MockResponse().setResponseCode(202))
                mcp.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"get_me","description":"Read profile","inputSchema":{"type":"object","properties":{}}}]}}"""))
                mcp.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"octocat profile fixture"}]}}"""))
                model.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                    "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"gh1\",\"function\":{\"name\":\"github_mcp_get_me\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n"))
                model.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"Profile found\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
                val cancellation = ChatRequestCancellation()
                val client = GitHubMcpClient("credential-fixture", endpoint = mcp.url("/mcp/"), cancellation = cancellation)
                val registry = ToolRegistry().register(GitHubMcpToolProvider(client.listTools()) { name, args -> client.callTool(name, args) })
                val updates = mutableListOf<ChatStreamUpdate>()
                IrioHttp.resetForTests(IrioHttp.defaultClient())
                try {
                    val result = ChatApi.streamChat(provider = ProviderConfig("fixture", "Fixture", model.url("/v1").toString(), "model-fixture"),
                        model = "fixture", messages = listOf(ChatRequestMessage("user", "Read my GitHub profile")),
                        reasoningEffort = ReasoningEffort.OFF, fastMode = false, toolRegistry = registry,
                        cancellation = cancellation, onUpdate = { updates += it })
                    assertNull(result.error)
                    assertEquals("Profile found", result.content)
                    val initial = model.takeRequest().body.readUtf8()
                    assertTrue(initial.contains("github_mcp_get_me"))
                    assertFalse(initial.contains("credential-fixture"))
                    val continuation = model.takeRequest().body.readUtf8()
                    assertTrue(continuation.contains("octocat profile fixture"))
                    assertEquals(1, updates.mapNotNull { it.toolRecord }.size)
                    repeat(3) { mcp.takeRequest() }
                    val remote = JSONObject(mcp.takeRequest().body.readUtf8())
                    assertEquals("tools/call", remote.getString("method"))
                    assertEquals("get_me", remote.getJSONObject("params").getString("name"))
                } finally { IrioHttp.resetForTests() }
            }
        }
    }
}
