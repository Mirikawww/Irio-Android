package com.irio

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class IrioDocsRegistryTest {
    @Test fun toolSettingsKeepDocsVisibleWhenDisabledAndUseTheSameSchemas() = kotlinx.coroutines.runBlocking {
        val app = RuntimeEnvironment.getApplication()
        ShadowEnvironment.addExternalDir(app.cacheDir.resolve("external").absolutePath)
        IrioDocsMcpStore.saveEnabled(app, false)
        val entries = ToolCatalog.load(app).filter { it.name.startsWith(IrioDocsMcpToolProvider.PREFIX) }
        assertEquals(3, entries.size)
        assertTrue(entries.all { it.blockedReason == ToolBlockedReason.IRIO_DOCS })
        IrioDocsMcpStore.saveEnabled(app, true)
        assertTrue(ToolCatalog.load(app).filter { it.name.startsWith(IrioDocsMcpToolProvider.PREFIX) }.all { it.blockedReason == null })
        val tools = IrioDocsMcpStore.tools(app)
        assertEquals(IrioDocsMcpClient.TOOL_NAMES, tools.map { it.name }.toSet())
    }

    @Test fun actualFactorySerializesDocsForEveryModelProtocolWithoutAnyDocsRequest() = kotlinx.coroutines.runBlocking {
        val app = RuntimeEnvironment.getApplication()
        ShadowEnvironment.addExternalDir(app.cacheDir.resolve("external").absolutePath)
        IrioDocsMcpStore.saveEnabled(app, true)
        IrioHttp.resetForTests(IrioHttp.defaultClient())
        try {
            okhttp3.mockwebserver.MockWebServer().use { model ->
                for (protocol in ProviderProtocol.entries.filter { it != ProviderProtocol.MINIMAX_VIDEO }) {
                    model.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "text/event-stream").setBody(""))
                    ChatApi.streamChat(ProviderConfig("fixture", "Fixture", model.url("/v1beta").toString(), "fixture", protocol),
                        "model", listOf(ChatRequestMessage("user", "Irio documentation?")), ReasoningEffort.OFF, false,
                        toolRegistry = chatToolRegistry(app, false, ""), cancellation = ChatRequestCancellation(), onUpdate = {})
                    val request = model.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)!!
                    val body = request.body.readUtf8()
                    assertTrue("$protocol missing Docs schema on wire", body.contains("irio_docs_mcp_search_irio_docs"))
                    assertTrue("$protocol missing Docs instructions on wire", body.contains("Irio Docs"))
                    assertFalse(body.contains("Authorization: Bearer"))
                }
            }
        } finally { IrioHttp.resetForTests() }
    }

    @Test fun actualChatFactoryIncludesDocsAcrossProtocolsAndHonorsBothLevelsOfSwitches() {
        val app = RuntimeEnvironment.getApplication()
        ShadowEnvironment.addExternalDir(app.cacheDir.resolve("external").absolutePath)
        ThemeStore.ensureLoaded(app)
        val prefs = app.getSharedPreferences("irio_docs_mcp", 0)
        prefs.edit().putBoolean("enabled", true).commit()
        val names = setOf("irio_docs_mcp_search_irio_docs", "irio_docs_mcp_read_irio_doc", "irio_docs_mcp_list_irio_releases")
        for (protocol in ProviderProtocol.entries) {
            val registry = chatToolRegistry(app, false, "")
            val actual = registry.definitions(protocol).map { (it.optJSONObject("function") ?: it).getString("name") }.toSet()
            assertTrue("Missing Docs tools in actual $protocol chat factory: $actual", actual.containsAll(names))
            assertTrue("Local tool guide must be present independently", "search_tool_guides" in actual)
            assertTrue(registry.systemPrompt(null).orEmpty().contains("Irio Docs"))
            assertTrue(registry.withoutSubagents().definitions(protocol).map { (it.optJSONObject("function") ?: it).getString("name") }.containsAll(names))
        }
        ToolPreferencesStore.saveEnabled(app, "irio_docs_mcp_read_irio_doc", false)
        val filtered = chatToolRegistry(app, false, "")
        assertFalse(filtered.handles("irio_docs_mcp_read_irio_doc"))
        assertTrue(filtered.handles("irio_docs_mcp_search_irio_docs"))
        prefs.edit().putBoolean("enabled", false).commit()
        for (protocol in ProviderProtocol.entries) {
            val off = chatToolRegistry(app, false, "")
            assertFalse(off.definitions(protocol).any { it.toString().contains("irio_docs_mcp_") })
            assertTrue("Disabling remote Docs MCP must not disable local tutorials", off.handles("search_tool_guides"))
            assertTrue(off.systemPrompt(null).orEmpty().contains("Local tool tutorials"))
            assertFalse(off.systemPrompt(null).orEmpty().contains("Irio Docs"))
        }
    }
}
