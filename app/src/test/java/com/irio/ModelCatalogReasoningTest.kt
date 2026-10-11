package com.irio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogReasoningTest {

    private val fixture = """
    {
        "locked": {"supports": true, "options": []},
        "toggleable": {"supports": true, "options": ["none", "low", "high"]},
        "offonly": {"supports": false},
        "weird": {"supports": true}
    }
    """.trimIndent()

    @Test
    fun openRouterMandatoryControlsToggleabilityIndependentlyOfEffortLevels() {
        val parsed = ModelCatalog.parseReasoning("""
            {
                "locked": {"supports": true, "toggleable": false, "options": ["none", "high"]},
                "optional": {"supports": true, "toggleable": true, "options": ["high", "low"]},
                "unknown": {"supports": true, "options": ["high", "low"]},
                "plain": {"supports": false}
            }
        """.trimIndent())

        assertEquals(ModelCatalog.ReasoningInfo(true, false, setOf("none", "high")), parsed["locked"])
        assertEquals(ModelCatalog.ReasoningInfo(true, true, setOf("high", "low")), parsed["optional"])
        assertEquals(ModelCatalog.ReasoningInfo(true, null, setOf("high", "low")), parsed["unknown"])
        assertEquals(ModelCatalog.ReasoningInfo(false, null), parsed["plain"])
    }

    @Test
    fun mergedReasoningSemantics() {
        val off = ModelCatalog.ReasoningInfo(supports = false, toggleable = null)
        val locked = ModelCatalog.ReasoningInfo(supports = true, toggleable = false)
        val toggleable = ModelCatalog.ReasoningInfo(supports = true, toggleable = true)

        // 合并：reasoning 变体支持 + non-reasoning 变体不支持 → 整体支持
        assertEquals(true, ModelCatalog.mergedReasoning(listOf(off, locked))!!.supports)
        assertEquals(false, ModelCatalog.mergedReasoning(listOf(off, locked))!!.toggleable)

        // 任一变体支持且可关 → 整体可关
        assertEquals(true, ModelCatalog.mergedReasoning(listOf(locked, toggleable))!!.toggleable)

        // 全部不支持 → 整体不支持
        assertEquals(false, ModelCatalog.mergedReasoning(listOf(off, off))!!.supports)

        // mergeKey：grok-4.20-0309-reasoning 和它的 non-reasoning 变体在同一组
        assertEquals(ModelCatalog.mergeKey("grok-4.20-0309-non-reasoning", true), ModelCatalog.mergeKey("grok-4.20-0309-reasoning", true))
    }

    @Test
    fun restrictedEffortsBecomeTheOnlySliderStopsInDepthOrder() {
        val info = ModelCatalog.ReasoningInfo(true, false, setOf("high", "medium", "low"))
        assertEquals(listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH), ModelCatalog.reasoningLevels(info))
        assertEquals(ReasoningEffort.HIGH, ModelCatalog.resolveReasoningEffort(ReasoningEffort.ULTRA, ModelCatalog.reasoningLevels(info)))
        assertEquals(ReasoningEffort.LOW, ModelCatalog.resolveReasoningEffort(ReasoningEffort.OFF, ModelCatalog.reasoningLevels(info)))
    }

    @Test
    fun optionalThinkingKeepsOffWithoutInventingExtraEffortStops() {
        val info = ModelCatalog.ReasoningInfo(true, true, setOf("low", "high"))
        val levels = ModelCatalog.reasoningLevels(info)
        assertEquals(listOf(ReasoningEffort.OFF, ReasoningEffort.LOW, ReasoningEffort.HIGH), levels)
        assertEquals(ReasoningEffort.LOW, ModelCatalog.resolveReasoningEffort(ReasoningEffort.MEDIUM, levels))
        assertEquals(ReasoningEffort.HIGH, ModelCatalog.resolveReasoningEffort(ReasoningEffort.HIGH, levels))
    }

    @Test
    fun unsupportedAndSingleDepthModelsHaveOneFixedStop() {
        assertEquals(listOf(ReasoningEffort.OFF), ModelCatalog.reasoningLevels(ModelCatalog.ReasoningInfo(false, null)))
        val fixed = ModelCatalog.reasoningLevels(ModelCatalog.ReasoningInfo(true, false, setOf("high")))
        assertEquals(listOf(ReasoningEffort.HIGH), fixed)
        assertEquals(ReasoningEffort.HIGH, ModelCatalog.resolveReasoningEffort(ReasoningEffort.MAX, fixed))
    }

    @Test
    fun unknownEffortMetadataRetainsAllDepthsWithoutAnAutomaticStop() {
        val full = ReasoningEffort.entries.filter { it != ReasoningEffort.AUTO }
        assertEquals(full, ModelCatalog.reasoningLevels(null))
        assertEquals(full, ModelCatalog.reasoningLevels(ModelCatalog.ReasoningInfo(true, null)))
        assertEquals(full, ModelCatalog.reasoningLevels(ModelCatalog.ReasoningInfo(true, null, emptySet())))
        assertEquals(full.filter { it != ReasoningEffort.OFF }, ModelCatalog.reasoningLevels(ModelCatalog.ReasoningInfo(true, false)))
    }

    @Test
    fun ultraOnlyAppearsWhenExplicitlySupportedInRestrictedMetadata() {
        val parsed = ModelCatalog.parseReasoning("""{"model":{"supports":true,"toggleable":false,"options":[" ULTRA ","high",null,42]}}""")
        assertEquals(setOf("ultra", "high"), parsed.getValue("model").options)
        assertEquals(listOf(ReasoningEffort.HIGH, ReasoningEffort.ULTRA), ModelCatalog.reasoningLevels(parsed.getValue("model")))
    }

    @Test
    fun mergedRestrictedOptionsOnlyKeepDepthsSharedBySupportingVariants() {
        val merged = ModelCatalog.mergedReasoning(listOf(
            ModelCatalog.ReasoningInfo(true, false, setOf("low", "high")),
            ModelCatalog.ReasoningInfo(true, false, setOf("medium", "high")),
            ModelCatalog.ReasoningInfo(false, null),
        ))
        assertEquals(setOf("high"), merged!!.options)
        assertEquals(listOf(ReasoningEffort.HIGH), ModelCatalog.reasoningLevels(merged))
    }
}
