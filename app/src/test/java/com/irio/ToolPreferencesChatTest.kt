package com.irio

import android.content.Context
import java.util.concurrent.TimeUnit
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
@Config(sdk = [35])
class ToolPreferencesChatTest {
    @Test fun contextOverheadUsesTheSamePersistedToolSurfaceAsChat() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        org.robolectric.shadows.ShadowEnvironment.addExternalDir(context.cacheDir.resolve("external").absolutePath)
        ToolPreferencesStore.saveEnabled(context, TimeTool.NAME, false)
        ToolPreferencesStore.saveDescription(context, ReactionTool.NAME, "A custom description repeated for token accounting. ".repeat(20))
        for (protocol in ProviderProtocol.entries) {
            val registry = chatToolRegistry(context, webSearch = false, imageGenModel = "")
            assertFalse(registry.definitions(protocol).any { (it.optJSONObject("function") ?: it).getString("name") == TimeTool.NAME })
            val expected = estimateSystemPromptTokens(registry.systemPrompt(chatSystemPrompt(context)),
                registry.schemas(protocol).joinToString("\n") { it.toString() })
            assertEquals(expected, loadContextRequestOverhead(context, protocol, modelId = ""))
        }
    }

    @Test fun actualChatRequestsUsePersistedChoicesAcrossAllProtocolsAndLateMcpRegistration() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        org.robolectric.shadows.ShadowEnvironment.addExternalDir(context.cacheDir.resolve("external").absolutePath)
        ToolPreferencesStore.saveEnabled(context, TimeTool.NAME, false)
        ToolPreferencesStore.saveEnabled(context, "github_mcp_hidden", false)
        ToolPreferencesStore.saveDescription(context, ReactionTool.NAME, "Custom reaction description")
        ToolPreferencesStore.saveDescription(context, "github_mcp_visible", "Custom remote description")
        ToolStore.saveSmartToolHints(context, true)
        IrioHttp.resetForTests(IrioHttp.defaultClient())
        try {
            MockWebServer().use { model ->
                for (protocol in ProviderProtocol.entries) {
                    val registry = chatToolRegistry(context, webSearch = false, imageGenModel = "")
                    registry.register(GitHubMcpToolProvider(listOf(
                        McpTool("hidden", "Hidden", JSONObject("""{"type":"object","properties":{}}""")),
                        McpTool("visible", "Visible", JSONObject("""{"type":"object","properties":{}}""")),
                    )) { _, _ -> error("No tool execution expected") })
                    model.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(""))
                    ChatApi.streamChat(
                        ProviderConfig("fixture", "Fixture", model.url("/v1beta").toString(), "fixture", protocol),
                        "model", listOf(ChatRequestMessage("user", "hello")), ReasoningEffort.OFF, false,
                        toolRegistry = registry, systemPrompt = "base", cancellation = ChatRequestCancellation(), onUpdate = {},
                    )
                    val request = JSONObject(model.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())
                    val tools = request.getJSONArray("tools").toString()
                    assertFalse("Disabled time tool leaked into $protocol", tools.contains("\"${TimeTool.NAME}\""))
                    assertFalse(tools.contains("github_mcp_hidden"))
                    assertTrue(tools.contains("Custom reaction description"))
                    assertTrue(tools.contains("Custom remote description"))
                    assertTrue(tools.contains(SmartToolHints.ARGUMENT))
                    val prompt = prompt(request, protocol)
                    assertFalse(prompt.contains("github_mcp_hidden"))
                    assertTrue(prompt.contains("github_mcp_visible"))
                    assertFalse("Feature availability gates must remain intact", tools.contains("\"${WebSearchTool.NAME}\""))
                    assertFalse(tools.contains("\"${ImageGenTool.NAME}\""))
                }
            }
        } finally { IrioHttp.resetForTests() }
    }

    private fun prompt(request: JSONObject, protocol: ProviderProtocol): String = when (protocol) {
        ProviderProtocol.CHAT_COMPLETIONS -> request.getJSONArray("messages").getJSONObject(0).getString("content")
        ProviderProtocol.RESPONSES -> request.getString("instructions")
        ProviderProtocol.ANTHROPIC -> request.getString("system")
        ProviderProtocol.GEMINI -> request.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
        ProviderProtocol.GEMINI_INTERACTIONS -> request.getString("system_instruction")
    }
}
