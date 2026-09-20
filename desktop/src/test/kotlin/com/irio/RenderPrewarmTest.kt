package com.irio

import org.junit.Assert.assertEquals
import org.junit.Test

class RenderPrewarmTest {
    @Test
    fun visibleWindowIsPreparedBeforeRemainingHistory() {
        assertEquals(
            listOf(7, 8, 9, 6, 5, 4, 3, 2, 1, 0),
            renderPrewarmOrder(itemCount = 10, firstVisible = 7, lastVisible = 9),
        )
    }

    @Test
    fun invalidVisibleWindowFallsBackToNewestMessagesFirst() {
        assertEquals(
            listOf(4, 3, 2, 1, 0),
            renderPrewarmOrder(itemCount = 5, firstVisible = -1, lastVisible = -1),
        )
    }

    @Test
    fun inlineFormulaContentCanBePrewarmed() {
        val rewritten = rewriteInlineTex("前 \$x + 1\$ 后 \\(y)\\)")
        assertEquals(listOf("x + 1", "y)"), inlineTexContents(rewritten))
    }
}
