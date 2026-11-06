package com.irio

import java.io.File
import javax.crypto.KeyGenerator
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class McpToolRefreshTest {
    private val oldTools = listOf(McpTool("old", "Old", JSONObject("{\"type\":\"object\"}")))
    private val newTools = listOf(McpTool("new", "Updated", JSONObject("{\"type\":\"object\",\"properties\":{}}")))

    @Test fun githubRefreshDiscoversAndPersistsToolsWithoutChangingAccountOrEnablement() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.cacheDir, "refresh-github.json")
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = GitHubMcpStore(file, { key })
        val account = GitHubMcpAccount("fixture", "", "fixture@example.test")
        store.save(GitHubMcpInstallation(GitHubCredential("fixture-token"), "PAT", oldTools, false, account))
        MockWebServer().use { server ->
            enqueueTools(server, newTools)
            val updated = refreshGitHubMcpTools(store, ChatRequestCancellation()) { token, cancel ->
                GitHubMcpClient(token, endpoint = server.url("/mcp"), cancellation = cancel).listTools()
            }
            assertEquals("new", updated.tools.single().name)
            assertFalse(updated.enabled)
            assertEquals(account, updated.account)
            assertEquals("PAT", updated.auth)
            assertEquals("new", GitHubMcpStore(file, { key }).load()!!.tools.single().name)
            assertEquals("Bearer fixture-token", server.takeRequest().getHeader("Authorization"))
        }
    }

    @Test fun todoistRefreshDiscoversAndPersistsToolsWithoutChangingAccountOrEnablement() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.cacheDir, "refresh-todoist.json")
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = TodoistMcpStore(file, { key })
        val account = TodoistMcpAccount("Fixture", "", "fixture@example.test")
        store.save(TodoistMcpInstallation("fixture-client", TodoistCredential("fixture-token"), oldTools, false, account))
        MockWebServer().use { server ->
            enqueueTools(server, newTools)
            val updated = refreshTodoistMcpTools(store, ChatRequestCancellation()) { token, cancel ->
                TodoistMcpClient(token, endpoint = server.url("/mcp"), cancellation = cancel).listTools()
            }
            assertEquals("new", updated.tools.single().name)
            assertFalse(updated.enabled)
            assertEquals(account, updated.account)
            assertEquals("fixture-client", updated.clientId)
            assertEquals("new", TodoistMcpStore(file, { key }).load()!!.tools.single().name)
            assertEquals("Bearer fixture-token", server.takeRequest().getHeader("Authorization"))
        }
    }

    @Test fun failuresEmptyResultsCancellationAndStaleResponsesPreserveExistingTools() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val github = GitHubMcpStore(File(context.cacheDir, "failure-github.json"), { key })
        val todoist = TodoistMcpStore(File(context.cacheDir, "failure-todoist.json"), { key })
        github.save(GitHubMcpInstallation(GitHubCredential("old-token"), "PAT", oldTools))
        todoist.save(TodoistMcpInstallation("client", TodoistCredential("old-token"), oldTools))
        suspend fun checkBoth(discover: (String, ChatRequestCancellation) -> List<McpTool>) {
            assertTrue(runCatching { refreshGitHubMcpTools(github, ChatRequestCancellation(), discover) }.isFailure)
            assertTrue(runCatching { refreshTodoistMcpTools(todoist, ChatRequestCancellation(), discover) }.isFailure)
            assertEquals("old", github.load()!!.tools.single().name)
            assertEquals("old", todoist.load()!!.tools.single().name)
        }
        checkBoth { _, _ -> throw java.io.IOException("offline fixture") }
        checkBoth { _, _ -> emptyList() }
        checkBoth { _, cancel -> cancel.cancel(); newTools }
        assertTrue(runCatching {
            refreshGitHubMcpTools(github, ChatRequestCancellation()) { _, _ -> github.remove(); newTools }
        }.isFailure)
        assertNull(github.load())
        assertTrue(runCatching {
            refreshTodoistMcpTools(todoist, ChatRequestCancellation()) { _, _ -> todoist.remove(); newTools }
        }.isFailure)
        assertNull(todoist.load())
        github.save(GitHubMcpInstallation(GitHubCredential("new-token"), "PAT", oldTools))
        todoist.save(TodoistMcpInstallation("client", TodoistCredential("new-token"), oldTools))
        assertNull(github.updateTools("old-token", newTools))
        assertNull(todoist.updateTools("old-token", newTools))
        assertEquals("old", github.load()!!.tools.single().name)
        assertEquals("old", todoist.load()!!.tools.single().name)
    }

    @Test fun docsRefreshCachesRemoteSchemasForTheActualProviderAndPreservesDisabledState() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        IrioDocsMcpStore.install(context)
        IrioDocsMcpStore.saveEnabled(context, false)
        val tools = IrioDocsMcpClient.TOOL_NAMES.map { McpTool(it, "Refreshed $it", JSONObject("{\"type\":\"object\",\"properties\":{}}")) }
        MockWebServer().use { server ->
            enqueueTools(server, tools)
            assertEquals(3, refreshIrioDocsMcpTools(context, ChatRequestCancellation()) { cancel ->
                IrioDocsMcpClient(endpoint = server.url("/mcp"), cancellation = cancel).listTools()
            })
            assertNull(server.takeRequest().getHeader("Authorization"))
        }
        assertFalse(IrioDocsMcpStore.loadEnabled(context))
        assertTrue(IrioDocsMcpStore.tools(context).all { it.description.startsWith("Refreshed ") })
        assertTrue(runCatching { refreshIrioDocsMcpTools(context, ChatRequestCancellation()) { emptyList() } }.isFailure)
        assertTrue(IrioDocsMcpStore.tools(context).all { it.description.startsWith("Refreshed ") })
        IrioDocsMcpStore.saveEnabled(context, true)
        val definitions = IrioDocsMcpStore.toolProvider(context)!!.definitions(ProviderProtocol.CHAT_COMPLETIONS)
        assertTrue(definitions.all { it.toString().contains("Refreshed ") })
        IrioDocsMcpStore.remove(context)
        assertTrue(runCatching { IrioDocsMcpStore.saveTools(context, tools) }.isFailure)
        assertNull(IrioDocsMcpStore.toolProvider(context))
    }

    private fun enqueueTools(server: MockWebServer, tools: List<McpTool>) {
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","capabilities":{"tools":{}}}}"""))
        server.enqueue(MockResponse().setResponseCode(202))
        val array = org.json.JSONArray(tools.map { JSONObject().put("name", it.name)
            .put("description", it.description).put("inputSchema", it.inputSchema) })
        server.enqueue(MockResponse().setBody(JSONObject().put("jsonrpc", "2.0").put("id", 2)
            .put("result", JSONObject().put("tools", array)).toString()))
    }
}
