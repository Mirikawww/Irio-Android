package com.irio

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ModelCatalogImageTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun gptChatModelsNeverEnterTheImageEndpointPathForAnyProviderAlias() {
        val models = listOf("gpt-5-nano", "gpt-5-mini", "gpt-5", "gpt-5.1", "gpt-5.1-chat-latest",
            "gpt-5.1-codex", "gpt-5.1-codex-mini", "gpt-5.4", "gpt-5.4-pro", "gpt-5.5", "gpt-5.5-pro",
            "gpt-5-1", "gpt-5-2", "gpt-5-3-codex", "gpt-5-4", "gpt-5-4-mini", "gpt-5-4-nano",
            "gpt-5-5", "gpt-5-5-pro", "gpt-5-6-sol", "gpt-5-6-terra", "gpt-5-6-luna", "gpt-6-astra")
        models.forEach { model ->
            listOf(model, "openai/$model", "gateway/$model", model.uppercase()).forEach { id ->
                assertFalse("Chat model must not be routed to image generation: $id", ModelCatalog.isImageModel(context, id))
            }
        }
        val catalog = JSONObject(context.assets.open("model-image.json").bufferedReader().use { it.readText() })
        catalog.keys().asSequence().filter { it.substringAfterLast('/').startsWith("gpt-") }.forEach { id ->
            assertTrue("Every remaining GPT image entry must be an image-specific variant: $id", "image" in id.substringAfterLast('/'))
        }
    }

    @Test fun actualImageGenerationModelsRetainFullAndBareAliasRecognition() {
        for (model in listOf("gpt-image-1", "gpt-image-1-mini", "gpt-image-1.5", "gpt-image-2", "gpt-image-2.5",
            "gpt-image-2.5-flare", "gpt-image-2.5-sunburst", "gemini-3.1-flash-image")) {
            for (id in listOf(model, "gateway/$model", model.uppercase())) {
                assertTrue("Real image model must remain selectable: $id", ModelCatalog.isImageModel(context, id))
            }
        }
    }

    @Test fun staleImageToolSelectionIsRejectedButNanoCanStillUseARealImageTool() {
        ShadowEnvironment.addExternalDir(context.cacheDir.resolve("image-model-external").absolutePath)
        val previous = ToolStore.loadImageGenModel(context)
        try {
            ToolStore.saveImageGenModel(context, "gpt-5-nano")
            assertEquals("", configuredImageGenModel(context, "ordinary-chat"))
            ToolStore.saveImageGenModel(context, "gpt-image-2")
            val configured = configuredImageGenModel(context, "gpt-5-nano")
            assertEquals("gpt-image-2", configured)
            assertTrue(chatToolRegistry(context, false, configured).handles(ImageGenTool.NAME))
        } finally { ToolStore.saveImageGenModel(context, previous) }
    }
}
