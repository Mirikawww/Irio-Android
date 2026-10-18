package com.irio

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit
import java.net.Socket
import javax.crypto.KeyGenerator

class TodoistMcpTest {
    @get:Rule val temp = TemporaryFolder()
    private val tool = McpTool("get_tasks", "List Todoist tasks", JSONObject(
        """{"type":"object","properties":{"filter":{"type":"string"}},"required":[]}"""))

    @Test
    fun mcpClientDiscoversToolsAndCallsTheRemoteToolWithBearerAndSessionHeaders() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Mcp-Session-Id", "todoist-session").setBody(
                """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"Todoist","version":"1"}}}"""))
            server.enqueue(MockResponse().setResponseCode(202))
            server.enqueue(MockResponse().setBody(
                """{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"get_tasks","description":"List tasks","inputSchema":{"type":"object","properties":{"filter":{"type":"string"}}}}]}}"""))
            server.enqueue(MockResponse().setHeader("Mcp-Session-Id", "tool-session").setBody(
                """{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"{\"tasks\":[]}"},{"type":"text","text":"No tasks."}],"structuredContent":{"tasks":[]}}}"""))

            val client = TodoistMcpClient("fixture-secret", endpoint = server.url("/mcp"))
            assertEquals("get_tasks", client.listTools().single().name)
            val result = client.callTool("get_tasks", JSONObject().put("filter", "today"))
            assertTrue(result.contains("No tasks."))
            assertTrue(result.contains("\"tasks\":[]"))

            val initialize = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("Bearer fixture-secret", initialize.getHeader("Authorization"))
            assertEquals("initialize", JSONObject(initialize.body.readUtf8()).getString("method"))
            assertEquals("todoist-session", server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Mcp-Session-Id"))
            assertEquals("todoist-session", server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Mcp-Session-Id"))
            val toolCall = server.takeRequest(1, TimeUnit.SECONDS)!!
            val callBody = JSONObject(toolCall.body.readUtf8())
            assertEquals("tools/call", callBody.getString("method"))
            assertEquals("get_tasks", callBody.getJSONObject("params").getString("name"))
        }
    }

    @Test
    fun credentialsAreEncryptedAndRotatingRefreshTokensArePersisted() = runBlocking {
        val key = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
        val file = temp.newFile("todoist.json")
        val old = TodoistCredential("access-fixture", System.currentTimeMillis() + 60_000, "refresh-old")
        val tools = listOf(tool)
        val store = TodoistMcpStore(file, { key }) { clientId, credential ->
            assertEquals("client-fixture", clientId)
            assertEquals("refresh-old", credential.refreshToken)
            TodoistCredential("access-next", System.currentTimeMillis() + 3_600_000, "refresh-next")
        }
        store.save(TodoistMcpInstallation("client-fixture", old, tools))
        assertFalse(file.readText().contains("access-fixture"))
        assertFalse(file.readText().contains("refresh-old"))

        val loaded = store.current()!!
        assertEquals("access-next", loaded.credential.accessToken)
        assertEquals("refresh-next", loaded.credential.refreshToken)
        assertEquals("client-fixture", TodoistMcpStore(file, { key }).load()!!.clientId)

        store.setEnabled(false)
        assertNull(store.current())
        assertFalse(store.load()!!.enabled)
        assertNotNull(store.load())
    }

    @Test
    fun legacyInstallationsLoadWithoutAccountAndAccountUpdatesStayEncrypted() = runBlocking {
        val key = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
        val file = temp.newFile("todoist-account.json")
        val store = TodoistMcpStore(file, { key }) { _, _ ->
            TodoistCredential("access-next", System.currentTimeMillis() + 3_600_000, "refresh-next")
        }
        store.save(TodoistMcpInstallation("client-fixture", TodoistCredential("access-old",
            System.currentTimeMillis() + 60_000, "refresh-old"), listOf(tool), enabled = false))
        assertNull(store.load()!!.account)
        val account = TodoistMcpAccount("Todoist User", "https://images.cloudfront.net/avatar.jpg", "user@example.com")
        assertNull(store.updateAccount("wrong-token", account))
        assertNull(store.load()!!.account)
        assertEquals(account, store.updateAccount("access-old", account)!!.account)
        assertFalse(file.readText().contains("user@example.com"))
        assertFalse(file.readText().contains("Todoist User"))
        val refreshed = store.refreshIfNeeded()!!
        assertEquals("access-next", refreshed.credential.accessToken)
        assertEquals(account, refreshed.account)
        assertFalse(refreshed.enabled)
        assertNull(store.current())
        assertNull(store.updateAccount("access-old", account))
        store.setEnabled(true)
        assertEquals(account, store.load()!!.account)
        store.remove()
        assertNull(store.updateAccount("access-next", account))
        assertNull(store.load())
    }

    @Test
    fun oauthUsesDynamicRegistrationPkceStateAndResourceBinding() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(JSONObject().put("resource", "https://ai.todoist.net/mcp")
                .put("authorization_servers", org.json.JSONArray().put(server.url("/").toString().trimEnd('/'))).toString()))
            server.enqueue(MockResponse().setBody(JSONObject()
                .put("registration_endpoint", server.url("/register").toString())
                .put("authorization_endpoint", server.url("/authorize").toString())
                .put("token_endpoint", server.url("/token").toString())
                .put("code_challenge_methods_supported", org.json.JSONArray().put("S256")).toString()))
            server.enqueue(MockResponse().setResponseCode(201).setBody("""{"client_id":"client-fixture"}"""))
            server.enqueue(MockResponse().setBody("""{"access_token":"access-fixture","token_type":"Bearer","expires_in":3600,"refresh_token":"refresh-fixture"}"""))

            val oauth = TodoistMcpOAuth(
                resourceMetadataUrl = server.url("/.well-known/oauth-protected-resource/mcp"),
                authMetadataUrl = server.url("/.well-known/oauth-authorization-server"),
                authorizationHost = server.url("/").host,
                allowInsecureHttpForTests = true,
            )
            val result = oauth.authorize { authorizationUrl ->
                val url = authorizationUrl.toHttpUrl()
                assertEquals("S256", url.queryParameter("code_challenge_method"))
                assertNotNull(url.queryParameter("code_challenge"))
                assertEquals("https://ai.todoist.net/mcp", url.queryParameter("resource"))
                val redirect = url.queryParameter("redirect_uri")!!.toHttpUrl()
                val callback = redirect.newBuilder().addQueryParameter("code", "code-fixture")
                    .addQueryParameter("state", url.queryParameter("state")!!).build()
                Socket("localhost", redirect.port).use { socket ->
                    socket.getOutputStream().write("GET ${callback.encodedPath}?${callback.encodedQuery} HTTP/1.1\r\nHost: localhost:${redirect.port}\r\nConnection: close\r\n\r\n".toByteArray())
                    socket.getOutputStream().flush()
                }
            }
            assertEquals("client-fixture", result.clientId)
            assertEquals("access-fixture", result.credential.accessToken)
            assertEquals("refresh-fixture", result.credential.refreshToken)

            server.takeRequest(1, TimeUnit.SECONDS)
            server.takeRequest(1, TimeUnit.SECONDS)
            val registration = server.takeRequest(1, TimeUnit.SECONDS)!!
            val registrationJson = JSONObject(registration.body.readUtf8())
            assertEquals("none", registrationJson.getString("token_endpoint_auth_method"))
            assertEquals("data:read_write", registrationJson.getString("scope"))
            val tokenRequest = server.takeRequest(1, TimeUnit.SECONDS)!!
            val body = tokenRequest.body.readUtf8()
            assertTrue(body.contains("grant_type=authorization_code"))
            assertTrue(body.contains("code_verifier="))
            assertTrue(body.contains("resource=https%3A%2F%2Fai.todoist.net%2Fmcp"))
            assertFalse(body.contains("client_secret"))
        }
    }

    @Test
    fun discoveredToolsAreNamespacedAndPortableAcrossEveryProtocol() {
        val provider = TodoistMcpToolProvider(listOf(tool)) { _, _ -> "fixture" }
        assertEquals("todoist-mcp", provider.id)
        for (protocol in ProviderProtocol.entries) {
            val schema = provider.definitions(protocol).single()
            val function = schema.optJSONObject("function") ?: schema
            assertEquals("todoist_mcp_get_tasks", function.getString("name"))
            val parameters = function.optJSONObject("parameters") ?: function.optJSONObject("input_schema")
                ?: function.optJSONObject("parametersJsonSchema")
            assertTrue(parameters!!.getJSONObject("properties").has("filter"))
        }
        assertTrue(provider.systemInstructions() == null)
        assertTrue(provider.systemInstructions(setOf("todoist_mcp_get_tasks"))!!.contains("todoist_mcp_get_tasks"))
        assertEquals("调用 Todoist 工具 future_tool", MemoryTool.liveLabel("todoist_mcp_future_tool"))
        assertEquals("已调用 Todoist 工具 future_tool", MemoryTool.doneLabel("todoist_mcp_future_tool"))
        assertEquals("创建 Todoist 任务", TodoistMcpToolProvider.displayName("create-task"))
        assertEquals("搜索 Todoist 任务", TodoistMcpToolProvider.displayName("search_tasks"))
    }

    @Test
    fun toolExecutionInvokesTodoistAndEmitsTheResult() = runBlocking {
        var called = ""
        val events = mutableListOf<ChatStreamUpdate>()
        val provider = TodoistMcpToolProvider(listOf(tool)) { name, args ->
            called = "$name:${args.getString("filter")}"; "tasks-result"
        }
        val result = provider.execute(
            ChatToolCall("id", "todoist_mcp_get_tasks", """{"filter":"today"}"""),
            ToolExecutionContext(provider = ProviderConfig("test", "Test", "https://example.test", ""), model = "test",
                onUpdate = { events += it }),
        )
        assertEquals("get_tasks:today", called)
        assertEquals("tasks-result", result.text)
        assertEquals(listOf(true, false), events.map { it.toolActive })
        assertTrue(events.last().toolContent.contains("tasks-result"))
    }
}
