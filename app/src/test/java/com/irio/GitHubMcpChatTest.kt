package com.irio

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class GitHubMcpChatTest {
    @Test fun requestsDescribeOnlyConnectedMcpToolsAcrossAllProtocols() = runBlocking {
        IrioHttp.resetForTests(IrioHttp.defaultClient())
        try {
            MockWebServer().use { model ->
                for (protocol in ProviderProtocol.entries.filter { it != ProviderProtocol.MINIMAX_VIDEO }) {
                    for (connected in listOf(false, true)) {
                        for (hints in listOf(false, true)) {
                            model.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(""))
                            val registry = ToolRegistry(listOf(TimeToolProvider()), smartToolHints = hints)
                            if (connected) registry.register(GitHubMcpToolProvider(listOf(
                                McpTool("get_me", "Read profile", JSONObject("{\"type\":\"object\",\"properties\":{}}")),
                            )) { _, _ -> error("No tool call expected") })
                            ChatApi.streamChat(
                                ProviderConfig("fixture", "Fixture", model.url("/v1beta").toString(), "fixture", protocol),
                                "model", listOf(ChatRequestMessage("user", "Which MCP tools can you use?")),
                                ReasoningEffort.OFF, false, systemPrompt = "Existing system prompt",
                                toolRegistry = registry, cancellation = ChatRequestCancellation(), onUpdate = {},
                            )
                            val request = JSONObject(model.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())
                            val prompt = systemPrompt(request, protocol)
                            assertTrue(prompt.startsWith("Existing system prompt"))
                            assertEquals("$protocol MCP capability, connected=$connected hints=$hints",
                                connected, prompt.contains("GitHub MCP"))
                            assertEquals(connected, prompt.contains("github_mcp_get_me"))
                            assertEquals(connected, request.getJSONArray("tools").toString().contains("github_mcp_get_me"))
                            assertFalse(prompt.contains("github_mcp_delete_repository"))
                            assertEquals(hints, prompt.contains(SmartToolHints.INSTRUCTIONS))
                        }
                    }
                }
            }
        } finally { IrioHttp.resetForTests() }
    }

    private fun systemPrompt(request: JSONObject, protocol: ProviderProtocol): String = when (protocol) {
        ProviderProtocol.MINIMAX_VIDEO -> error("Video protocol is not chat")
        ProviderProtocol.CHAT_COMPLETIONS -> request.getJSONArray("messages").getJSONObject(0).getString("content")
        ProviderProtocol.RESPONSES -> request.getString("instructions")
        ProviderProtocol.ANTHROPIC -> request.getString("system")
        ProviderProtocol.GEMINI -> request.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
        ProviderProtocol.GEMINI_INTERACTIONS -> request.getString("system_instruction")
    }

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
                    assertTrue(systemPrompt(JSONObject(initial), ProviderProtocol.CHAT_COMPLETIONS).contains("GitHub MCP"))
                    assertFalse(initial.contains("credential-fixture"))
                    val continuation = model.takeRequest().body.readUtf8()
                    assertTrue(continuation.contains("octocat profile fixture"))
                    assertEquals(systemPrompt(JSONObject(initial), ProviderProtocol.CHAT_COMPLETIONS),
                        systemPrompt(JSONObject(continuation), ProviderProtocol.CHAT_COMPLETIONS))
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
