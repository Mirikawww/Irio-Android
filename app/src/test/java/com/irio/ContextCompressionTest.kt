package com.irio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContextCompressionTest {
    @Test
    fun compressionStatusIsATranscriptLabel() {
        assertEquals("上下文压缩中", compressionStatusLabel(SummaryState.RUNNING))
        assertEquals("已压缩上下文", compressionStatusLabel(SummaryState.SUCCESS))
        assertEquals("上下文压缩失败", compressionStatusLabel(SummaryState.FAILED))
        assertNull(compressionStatusLabel(SummaryState.NONE))
    }
}
