package com.irio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SttStoreTest {
    @Test
    fun normalizesGroqApiKeyCopiedWithBearerPrefix() {
        assertEquals("gsk_example", normalizeSttApiKey("  Bearer gsk_example\n"))
        assertEquals("gsk_example", normalizeSttApiKey("gsk_example"))
    }

    @Test
    fun exposesModelsForEachSttProvider() {
        val deepgramModels = SttStore.models(SttProviderKind.DEEPGRAM)
        assertEquals("nova-3", deepgramModels.first())
        assertEquals("whisper-large", deepgramModels.last())
        assertTrue(
            deepgramModels.containsAll(
                listOf("nova-3-medical", "nova-3-pharma", "nova-2-medical", "nova-2-atc", "enhanced", "base", "whisper-tiny"),
            ),
        )
        assertEquals(listOf("solaria-1", "solaria-3"), SttStore.models(SttProviderKind.GLADIA))
        assertEquals(
            listOf("universal-3-6-pro", "universal-3-5-pro", "universal-2"),
            SttStore.models(SttProviderKind.ASSEMBLYAI),
        )
    }
}
