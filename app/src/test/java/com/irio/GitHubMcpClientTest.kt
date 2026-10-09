package com.irio

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubMcpClientTest {
    @Test fun stopCancelsAnInFlightMcpRequest() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            val http = okhttp3.OkHttpClient.Builder().readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
            val cancellation = ChatRequestCancellation()
            val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                val future = worker.submit<Boolean> {
                    runCatching { GitHubMcpClient("fixture-token", endpoint = server.url("/mcp/"), cancellation = cancellation, http = http).listTools() }.isFailure
                }
                assertTrue(server.takeRequest(3, java.util.concurrent.TimeUnit.SECONDS) != null)
                cancellation.cancel()
                assertTrue(future.get(3, java.util.concurrent.TimeUnit.SECONDS))
            } finally {
                worker.shutdownNow()
                http.dispatcher.executorService.shutdownNow()
                http.connectionPool.evictAll()
            }
        }
    }

    @Test fun discoveryNeverIncludesCredentialsInModelFacingSchemas() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","capabilities":{"tools":{}}}}"""))
            server.enqueue(MockResponse().setResponseCode(202))
            server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"get_me","description":"fixture-token","inputSchema":{"type":"object","description":"fixture-token"}}]}}"""))
            val tool = GitHubMcpClient("fixture-token", endpoint = server.url("/mcp/")).listTools().single()
            assertTrue(!tool.description.contains("fixture-token"))
            assertTrue(!tool.inputSchema.toString().contains("fixture-token"))
        }
    }

    @Test fun malformedRpcEnvelopeCannotInstallTools() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"id":1,"result":{"protocolVersion":"2025-06-18","capabilities":{"tools":{}}}}"""))
            server.enqueue(MockResponse().setResponseCode(202))
            server.enqueue(MockResponse().setBody("""{"id":2,"result":{"tools":[{"name":"get_me","inputSchema":{"type":"object"}}]}}"""))
            assertTrue(runCatching { GitHubMcpClient("fixture-token", endpoint = server.url("/mcp/")).listTools() }.isFailure)
        }
    }

    @Test
    fun streamableHttpSupportsSseAndPaginatedDiscovery() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/message\"}\n\n" +
                    "data: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{\"tools\":{}}}}\n\n",
            ))
            server.enqueue(MockResponse().setResponseCode(202))
            server.enqueue(MockResponse().setBody("""
                {"jsonrpc":"2.0","id":2,"result":{"nextCursor":"page-2","tools":[{"name":"get_me","inputSchema":{"type":"object","properties":{}}}]}}
            """.trimIndent()))
            server.enqueue(MockResponse().setBody("""
                {"jsonrpc":"2.0","id":3,"result":{"tools":[{"name":"search_repositories","inputSchema":{"type":"object","properties":{}}}]}}
            """.trimIndent()))
            assertEquals(listOf("get_me", "search_repositories"),
                GitHubMcpClient("fixture-token", endpoint = server.url("/mcp/")).listTools().map { it.name })
            repeat(3) { server.takeRequest() }
            assertEquals("page-2", JSONObject(server.takeRequest().body.readUtf8()).getJSONObject("params").getString("cursor"))
        }
    }

    @Test
    fun credentialsAreNeverForwardedThroughRedirects() {
        MockWebServer().use { source ->
            MockWebServer().use { target ->
                source.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target.url("/leak")))
                val error = runCatching { GitHubMcpClient("fixture-token", endpoint = source.url("/mcp/")).listTools() }.exceptionOrNull()
                assertTrue(error != null)
                assertEquals(0, target.requestCount)
                assertTrue(!error!!.message.orEmpty().contains("fixture-token"))
            }
        }
    }

    @Test
    fun installationNegotiatesSessionAndDiscoversRealTools() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Mcp-Session-Id", "session-1").setBody("""
                {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"github","version":"test"}}}
            """.trimIndent()))
            server.enqueue(MockResponse().setResponseCode(202))
            server.enqueue(MockResponse().setBody("""
                {"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"get_me","description":"Read profile","inputSchema":{"type":"object","properties":{}}}]}}
            """.trimIndent()))
            val client = GitHubMcpClient("fixture-token", endpoint = server.url("/mcp/"))
            val tools = client.listTools()
            assertEquals("Only discovered tools count as an installation", listOf("get_me"), tools.map { it.name })
            val initialize = server.takeRequest()
            assertEquals("initialize", JSONObject(initialize.body.readUtf8()).getString("method"))
            assertEquals("Bearer fixture-token", initialize.getHeader("Authorization"))
            assertTrue(initialize.getHeader("Accept")!!.contains("text/event-stream"))
            val initialized = server.takeRequest()
            assertEquals("notifications/initialized", JSONObject(initialized.body.readUtf8()).getString("method"))
            assertEquals("session-1", initialized.getHeader("Mcp-Session-Id"))
            val list = server.takeRequest()
            assertEquals("tools/list", JSONObject(list.body.readUtf8()).getString("method"))
            assertEquals("2025-06-18", list.getHeader("MCP-Protocol-Version"))
        }
    }
}
