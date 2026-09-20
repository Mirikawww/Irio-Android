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
}
