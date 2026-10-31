package com.irio

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VideoGenerationOptionsTest {
    @Test fun defaultsAndAllH3ConcreteCombinationsAreValid() {
        assertEquals(VideoGenerationOptions("768P", 5, "16:9"), VideoGenerationOptions())
        for (resolution in listOf("768P", "2K")) for (duration in 4..15) for (ratio in VideoGenerationOptions.ratios) {
            assertNull(VideoGenerationOptions(resolution, duration, ratio).validationError("MiniMax-H3"))
        }
    }

    @Test fun eachVariantEnforcesDocumentedResolutionAndDuration() {
        VideoGenerationOptions.models.forEach { model ->
            val resolutions = VideoGenerationOptions.resolutionsFor(model)
            val durations = VideoGenerationOptions.durationsFor(model)
            resolutions.forEach { resolution -> durations.forEach { duration ->
                assertNull(VideoGenerationOptions(resolution, duration).validationError(model))
            } }
            assertNotNull(VideoGenerationOptions(duration = durations.first - 1).validationError(model))
            assertNotNull(VideoGenerationOptions(duration = 16).validationError(model))
        }
        assertNotNull(VideoGenerationOptions("480P").validationError("MiniMax-H3"))
        assertNotNull(VideoGenerationOptions("2K").validationError("MiniMax-H3-Lite"))
        assertNotNull(VideoGenerationOptions("2K").validationError("MiniMax-H3-Highspeed"))
        assertNotNull(VideoGenerationOptions("2K").validationError("MiniMax-H3-Max"))
        assertNotNull(VideoGenerationOptions().validationError("not-a-model"))
    }

    @Test fun storageNormalizationDoesNotChangeExplicitApiValidation() {
        val invalid = VideoGenerationOptions("2K", 4, "bad")
        assertNotNull(invalid.validationError("MiniMax-H3-Max"))
        assertEquals(VideoGenerationOptions(), invalid.forModel("MiniMax-H3-Max"))
        assertEquals(VideoGenerationOptions(ratio = "16:9"), VideoGenerationOptions(ratio = "adaptive").forModel("MiniMax-H3-Lite"))
    }

    @Test fun jsonRoundTripAndMalformedFieldsAreSafe() {
        val options = VideoGenerationOptions("2K", 15, "9:16")
        assertEquals(options, VideoGenerationOptions.fromJson(options.toJson()))
        assertEquals(VideoGenerationOptions(), VideoGenerationOptions.fromJson(JSONObject()))
        assertEquals(VideoGenerationOptions(), VideoGenerationOptions.fromJson(JSONObject("""{"resolution":null,"duration":4.5,"ratio":false}""")))
        assertEquals(VideoGenerationOptions(duration = 15), VideoGenerationOptions.fromJson(JSONObject("""{"resolution":"bad","duration":999,"ratio":"bad"}""")).forModel("MiniMax-H3"))
    }

    @Test fun qualifiedModelCapabilitiesDoNotRewriteTheWireIdentifier() {
        val model = "minimax/MiniMax-H3"
        val options = VideoGenerationOptions("2K", 15, "9:16")
        assertEquals(options, options.forModel(model))
        assertNull(options.validationError(model))
        assertEquals(model, VideoGenerationApi.requestBody(model, "fixture", options, emptyList()).getString("model"))
    }
}
