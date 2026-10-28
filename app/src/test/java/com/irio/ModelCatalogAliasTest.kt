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
