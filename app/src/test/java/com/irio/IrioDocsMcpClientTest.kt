package com.irio

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class IrioDocsMcpClientTest {
    @Test fun liveAnonymousDocsServiceWhenExplicitlyRequested() {
        org.junit.Assume.assumeTrue(System.getenv("IRIO_DOCS_MCP_LIVE_TEST") == "1")
        val ip = System.getenv("IRIO_DOCS_MCP_LIVE_IP")
        val http = okhttp3.OkHttpClient.Builder().dns { host ->
            if (!ip.isNullOrBlank() && host == "irio-docs-mcp.mirikawww.workers.dev") listOf(java.net.InetAddress.getByName(ip))
            else okhttp3.Dns.SYSTEM.lookup(host)
        }.followRedirects(false).followSslRedirects(false).build()
        try {
            val client = IrioDocsMcpClient(http = http)
            assertEquals(IrioDocsMcpClient.TOOL_NAMES, client.listTools().map { it.name }.toSet())
            val found = JSONObject(client.callTool("search_irio_docs", JSONObject().put("query", "图片压缩").put("platform", "android").put("channel", "preview")))
            assertTrue(found.getJSONArray("results").length() > 0)
            val document = found.getJSONArray("results").getJSONObject(0)
            val read = JSONObject(client.callTool("read_irio_doc", JSONObject().put("id", document.getString("id"))))
            assertTrue(read.getString("content").contains("原始图片不会被改动"))
            val releases = JSONObject(client.callTool("list_irio_releases", JSONObject().put("platform", "android").put("channel", "stable")))
            assertEquals("1.0.0-iota", releases.getJSONArray("releases").getJSONObject(0).getString("version"))
        } finally { http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll() }
    }

    @Test fun anonymousCallsNegotiateProtocolAndNeverSendAuthorization() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","capabilities":{"tools":{}}}}"""))
            server.enqueue(MockResponse().setResponseCode(202))
            server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"Source: https://irio.mirikawww.workers.dev/"}]}}"""))
            val client = IrioDocsMcpClient(endpoint = server.url("/mcp"))
            assertTrue(client.callTool("search_irio_docs", JSONObject().put("query", "图片压缩")).contains("Source:"))
            val requests = List(3) { server.takeRequest() }
            assertTrue(requests.all { it.getHeader("Authorization") == null })
            assertEquals("initialize", JSONObject(requests[0].body.readUtf8()).getString("method"))
            assertEquals("2025-06-18", requests[2].getHeader("MCP-Protocol-Version"))
            val call = JSONObject(requests[2].body.readUtf8())
            assertEquals("tools/call", call.getString("method"))
            assertEquals("图片压缩", call.getJSONObject("params").getJSONObject("arguments").getString("query"))
        }
    }

    @Test fun rejectsWriteToolsBeforeAnyNetworkAndReportsRemoteFailure() {
        MockWebServer().use { server ->
            val client = IrioDocsMcpClient(endpoint = server.url("/mcp"))
            assertTrue(runCatching { client.callTool("delete_doc", JSONObject()) }.isFailure)
            assertEquals(0, server.requestCount)
            server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","capabilities":{"tools":{}}}}"""))
            server.enqueue(MockResponse().setResponseCode(202))
            server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":2,"result":{"isError":true,"content":[{"type":"text","text":"Document not found"}]}}"""))
            assertTrue(runCatching { client.callTool("read_irio_doc", JSONObject().put("id", "missing")) }.exceptionOrNull() is McpToolFailure)
        }
    }

    @Test fun redirectsAreNotFollowed() {
        MockWebServer().use { source -> MockWebServer().use { target ->
            source.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target.url("/mcp")))
            assertTrue(runCatching { IrioDocsMcpClient(endpoint = source.url("/mcp")).listTools() }.isFailure)
            assertEquals(0, target.requestCount)
        } }
    }

    @Test fun docsTransportIsAvailable() {
        assertNotNull("The anonymous Docs MCP transport has not been implemented",
            runCatching { Class.forName("com.irio.IrioDocsMcpClient") }.getOrNull())
    }
}
