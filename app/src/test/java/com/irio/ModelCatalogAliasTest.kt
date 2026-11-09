package com.irio

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ModelCatalogAliasTest {
    @Test fun gemini25FlashThinkingGatewayAliasResolvesOfflineWithoutChangingTheRequestId() {
        val context = RuntimeEnvironment.getApplication()
        for (id in listOf("gemini-2.5-flash-thinking", "google/gemini-2.5-flash-thinking",
            "gateway/gemini-2.5-flash-thinking", "GEMINI-2.5-FLASH-THINKING")) {
            assertEquals(id, 1_048_576, ModelCatalog.contextWindow(context, id))
            assertEquals(id, "Gemini 2.5 Flash", ModelCatalog.displayName(context, id))
            assertEquals(id, ModelCatalog.reasoningInfo(context, "gemini-2.5-flash", true),
                ModelCatalog.reasoningInfo(context, id, true))
            assertEquals(id, false, ModelCatalog.isImageModel(context, id))
            assertEquals(id, id, ModelCatalog.canonicalModel(listOf(id)))
        }
        assertEquals("Do not generalize to unknown models", 0, ModelCatalog.contextWindow(context, "unknown-thinking"))
    }

    @Test fun gemini25FlashOfflineMetadataResolvesThroughTheActualAssetReader() {
        val context = RuntimeEnvironment.getApplication()
        for (id in listOf("gemini-2.5-flash", "google/gemini-2.5-flash", "models/gemini-2.5-flash", "GEMINI-2.5-FLASH")) {
            assertEquals(id, "Gemini 2.5 Flash", ModelCatalog.displayName(context, id))
            assertEquals(id, 1_048_576, ModelCatalog.contextWindow(context, id))
            assertEquals(id, ModelCatalog.ReasoningInfo(true, true), ModelCatalog.reasoningInfo(context, id, true))
            assertEquals(id, false, ModelCatalog.isImageModel(context, id))
        }
    }

    @Test
    fun qwenMaxRollingAliasReadsTheCanonicalOfflineMetadata() {
        val context = RuntimeEnvironment.getApplication()
        val expectedReasoning = ModelCatalog.ReasoningInfo(
            supports = true, toggleable = false,
            options = setOf("xhigh", "high", "medium", "low", "minimal"),
        )
        for (id in listOf("qwen/qwen3.8-max", "qwen3.8-max", "gateway/qwen3.8-max", "QWEN3.8-MAX")) {
            assertEquals(id, "Qwen3.8 Max (0902)", ModelCatalog.displayName(context, id))
            assertEquals(id, 1_000_000, ModelCatalog.contextWindow(context, id))
            val reasoning = ModelCatalog.reasoningInfo(context, id, useCatalog = true)
            assertEquals(id, expectedReasoning, reasoning)
            assertEquals(id, listOf(ReasoningEffort.MINIMAL, ReasoningEffort.LOW, ReasoningEffort.MEDIUM,
                ReasoningEffort.HIGH, ReasoningEffort.XHIGH), ModelCatalog.reasoningLevels(reasoning))
        }
    }
}
