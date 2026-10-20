package com.mikepenz.markdown.compose.elements

import org.junit.Assert.assertEquals
import org.junit.Test

class CodeLineNumberTest {
    @Test
    fun trailingNewlineIsItsOwnLine() {
        assertEquals(intArrayOf(0).toList(), codeLineStartOffsets("").toList())
        assertEquals(intArrayOf(0, 2).toList(), codeLineStartOffsets("a\n").toList())
        assertEquals(intArrayOf(0, 2, 3).toList(), codeLineStartOffsets("a\n\nb").toList())
    }

    @Test
    fun wrappedSourceLineKeepsOneNumber() {
        val text = "ab\nc"
        val lines = codeSourceLineVisualCounts(text) { offset ->
            when (offset) {
                0 -> 0
                1, 2 -> 1
                else -> 2
            }
        }
        assertEquals(intArrayOf(2, 1).toList(), lines.toList())
        assertEquals("1\n\n2", codeLineNumberLabel(lines))
    }

    @Test
    fun numberBaselineMatchesTheCodeLine() {
        val baselines = codeLineBaselines(
            text = "ab\nc",
            lineForOffset = { offset -> if (offset < 3) 0 else 1 },
            baselineOfLine = { line -> if (line == 0) 18 else 48 },
        )
        assertEquals(intArrayOf(18, 48).toList(), baselines.toList())
        assertEquals(4, codeLineNumberTop(codeBaselinePx = 18, numberBaselinePx = 14))
    }

    @Test
    fun unwrappedLinesStayOneToOne() {
        val label = codeLineNumberLabel(intArrayOf(1, 1, 1))
        assertEquals("1\n2\n3", label)
        assertEquals("1\n\n\n2", codeLineNumberLabel(intArrayOf(3, 1)))
    }
}
