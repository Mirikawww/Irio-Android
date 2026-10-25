package com.irio

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class MermaidAgentRegistryTest {
    @Test fun realRequestFactoryAndToolSettingsExposeMermaidWithoutExternalPrerequisites() {
        val context = RuntimeEnvironment.getApplication()
        ShadowEnvironment.addExternalDir(context.cacheDir.resolve("mermaid-external").absolutePath)
        val originalEnabled = ToolPreferencesStore.load(context).isEnabled(MermaidTool.NAME)
        try {
            ToolPreferencesStore.saveEnabled(context, MermaidTool.NAME, true)
            val parent = chatToolRegistry(context, webSearch = false, imageGenModel = "", imageSearch = false)
            val child = parent.withoutSubagents()
            for (registry in listOf(parent, child)) {
                assertTrue(registry.handles(MermaidTool.NAME))
                assertTrue(registry.systemPrompt(null)!!.contains(MermaidTool.NAME))
                for (protocol in ProviderProtocol.entries) {
                    assertEquals(1, registry.definitions(protocol).count { (it.optJSONObject("function") ?: it)
                        .optString("name") == MermaidTool.NAME })
                }
            }
            val entry = ToolCatalog.entries(ToolFeatureState()).single { it.name == MermaidTool.NAME }
            assertEquals(tr("渲染 Mermaid 图表"), entry.title)
            assertNull(entry.blockedReason)

            ToolPreferencesStore.saveEnabled(context, MermaidTool.NAME, false)
            val disabled = chatToolRegistry(context, webSearch = false, imageGenModel = "", imageSearch = false)
            assertFalse(disabled.handles(MermaidTool.NAME))
            assertFalse(disabled.withoutSubagents().handles(MermaidTool.NAME))
            assertFalse(disabled.systemPrompt(null).orEmpty().contains("Mermaid diagrams are available"))
        } finally { ToolPreferencesStore.saveEnabled(context, MermaidTool.NAME, originalEnabled) }
    }
}
