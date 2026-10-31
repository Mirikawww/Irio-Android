package com.irio

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ImageGenerationOptionsTest {
    @Test fun imagesRequestsDefaultToNonStreamingWithOrWithoutOptions() {
        val config = ProviderConfig("fixture", "Fixture", "https://example.invalid/v1", "fixture")
        ProviderProtocol.entries.filter { it != ProviderProtocol.MINIMAX_VIDEO }.filterNot { it.isGoogleGemini }.forEach { protocol ->
            listOf(null, ImageGenerationOptions()).forEach { options ->
                val body = ProviderApi.imageRequestBody(config.copy(protocol = protocol), "gpt-image-2", "fixture", options = options)
                assertFalse(body.getBoolean("stream"))
                assertFalse(body.has("partial_images"))
            }
        }
    }

    @Test fun streamingRequestsUseThreePartialImagesWithOrWithoutOptions() {
        val config = ProviderConfig("fixture", "Fixture", "https://example.invalid/v1", "fixture")
        ProviderProtocol.entries.filter { it != ProviderProtocol.MINIMAX_VIDEO }.filterNot { it.isGoogleGemini }.forEach { protocol ->
            listOf(null, ImageGenerationOptions()).forEach { options ->
                val body = ProviderApi.imageRequestBody(config.copy(protocol = protocol), "gpt-image-2", "fixture", options = options, streaming = true)
                assertTrue(body.getBoolean("stream"))
                assertEquals(3, body.get("partial_images"))
            }
        }
    }

    @Test fun customSelectionSurvivesReopeningEvenWhenItMatchesAPreset() {
        val custom = ImageGenerationOptions(aspectRatio = ImageAspectRatio(2, 3),
            useCustomAspectRatio = true, customRatio = ImageAspectRatio(2, 3))
        val reopened = ImageGenerationOptions.fromJson(custom.toJson())
        assertTrue(reopened.useCustomAspectRatio)
        assertEquals(ImageAspectRatio(2, 3), reopened.aspectRatio)
        val preset = reopened.copy(aspectRatio = ImageAspectRatio(16, 9), useCustomAspectRatio = false)
        val reopenedPreset = ImageGenerationOptions.fromJson(preset.toJson())
        assertFalse(reopenedPreset.useCustomAspectRatio)
        assertEquals(ImageAspectRatio(16, 9), reopenedPreset.aspectRatio)
        assertEquals("Custom input must survive selecting a preset", ImageAspectRatio(2, 3), reopenedPreset.customRatio)
    }

    @Test fun defaultsKeepFramingAutomaticAndUseMediumQuality() {
        val options = ImageGenerationOptions()
        assertEquals(ImageResolution.K1, options.resolution)
        assertNull(options.aspectRatio)
        assertEquals(ImageBackground.AUTO, options.background)
        assertEquals(ImageQuality.MEDIUM, options.quality)
        ImageResolution.entries.forEach {
            assertEquals("auto", options.copy(resolution = it).openAiSize())
        }
        assertEquals(mapOf("size" to "auto", "background" to "auto", "quality" to "medium"), options.openAiFields())
    }

    @Test fun ratiosIncludeBoundariesAndRejectInvalidCustomValues() {
        listOf("1:3", "3:1", "16:9", "7:5", " 10 ： 20 ", "4x3").forEach { assertNotNull(it, ImageAspectRatio.parse(it)) }
        listOf("0:1", "-1:1", "4:1", "1:4", "NaN:1", "1.5:1", "1:2:3", "2147483647:1", "", "1:0").forEach {
            assertNull(it, ImageAspectRatio.parse(it))
        }
        assertEquals("1:2", ImageAspectRatio.parse("10:20")?.label)
        imageAspectRatioPresets.forEach { assertNotNull(ImageAspectRatio.parse(it)) }
    }

    @Test fun dimensionsRespectTheDocumentedGridAreaAndAspectRatioLimits() {
        for (resolution in ImageResolution.entries) for (ratioText in imageAspectRatioPresets + listOf("7:5", "997:499")) {
            val ratio = ImageAspectRatio.parse(ratioText)!!
            val size = ImageGenerationOptions(resolution, ratio).openAiSize().split('x').map(String::toInt)
            val (width, height) = size
            assertTrue(width in 16..3840 && height in 16..3840)
            assertEquals(0, width % 16)
            assertEquals(0, height % 16)
            assertTrue(width * height in 655_360..8_294_400)
            assertTrue(width * 3 >= height && height * 3 >= width)
            assertEquals(ratio.width.toDouble() / ratio.height, width.toDouble() / height, 0.02)
        }
        assertEquals("1024x1024", ImageGenerationOptions(aspectRatio = ImageAspectRatio(1, 1)).openAiSize())
        assertEquals("2048x2048", ImageGenerationOptions(ImageResolution.K2, ImageAspectRatio(1, 1)).openAiSize())
        assertEquals("3840x2160", ImageGenerationOptions(ImageResolution.K4, ImageAspectRatio(16, 9)).openAiSize())
    }

    @Test fun savedOptionsRoundTripAndOldOrMalformedValuesUseSafeDefaults() {
        val options = ImageGenerationOptions(ImageResolution.K4, ImageAspectRatio(7, 5), ImageBackground.TRANSPARENT, ImageQuality.HIGH)
        assertEquals(options, ImageGenerationOptions.fromJson(options.toJson()))
        assertEquals(ImageGenerationOptions(), ImageGenerationOptions.fromJson(JSONObject()))
        assertEquals(ImageGenerationOptions(), ImageGenerationOptions.fromJson(JSONObject("""{
            "resolution":"bad","aspectRatio":"100:1","background":"bad","quality":"bad"
        }""")))
        assertEquals("png", options.openAiFields()["output_format"])
        assertFalse(options.copy(background = ImageBackground.OPAQUE).openAiFields().containsKey("output_format"))
    }

    @Test fun generationSendsTheSelectedParametersOnTheWire() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"message":"fixture"}}"""))
            val config = ProviderConfig("fixture", "Fixture", server.url("/v1").toString(), "fixture")
            val options = ImageGenerationOptions(ImageResolution.K2, ImageAspectRatio(16, 9), ImageBackground.TRANSPARENT, ImageQuality.HIGH)
            ProviderApi.generateImage(config, "gpt-image-2", "draw a tree", options = options, streaming = true)
            val request = server.takeRequest()
            assertEquals("/v1/images/generations", request.path)
            val json = JSONObject(request.body.readUtf8())
            assertEquals("gpt-image-2", json.getString("model"))
            assertEquals("draw a tree", json.getString("prompt"))
            assertEquals(3, json.get("partial_images"))
            options.openAiFields().forEach { (name, value) -> assertEquals(value, json.getString(name)) }
            assertFalse(json.has("resolution"))
            assertFalse(json.has("aspect_ratio"))
        }
    }

    @Test fun editsAndFallbackReuseTheSameOptions() {
        val image = Files.createTempFile("irio-image-options-", ".png").toFile()
        try {
            image.writeBytes(byteArrayOf(1, 2, 3))
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(404))
                server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"message":"fixture"}}"""))
                val config = ProviderConfig("fixture", "Fixture", server.url("/v1").toString(), "fixture")
                val options = ImageGenerationOptions(ImageResolution.K4, ImageAspectRatio(16, 9), ImageBackground.OPAQUE, ImageQuality.LOW)
                val context = ProviderApi.ImageGenContext("old prompt", listOf(ChatAttachment("previous.png", "image/png", image.absolutePath)))
                ProviderApi.generateImage(config, "gpt-image-2", "new prompt", context, options = options, streaming = true)
                val edits = server.takeRequest()
                assertEquals("/v1/images/edits", edits.path)
                val multipart = edits.body.readUtf8()
                assertTrue(multipart.contains("name=\"partial_images\"\r\n\r\n3\r\n"))
                options.openAiFields().forEach { (name, value) ->
                    assertTrue("Missing edits parameter $name", multipart.contains("name=\"$name\"\r\n\r\n$value\r\n"))
                }
                assertTrue(multipart.contains("name=\"image[]\""))
                val fallback = server.takeRequest()
                assertEquals("/v1/images/generations", fallback.path)
                val json = JSONObject(fallback.body.readUtf8())
                assertEquals(3, json.get("partial_images"))
                options.openAiFields().forEach { (name, value) -> assertEquals(value, json.getString(name)) }
                assertTrue(json.getString("prompt").contains("old prompt"))
                assertTrue(json.getString("prompt").contains("new prompt"))
            }
        } finally { image.delete() }
    }

    @Test fun nativeGeminiUsesNativeFieldsAndOmitsUnsupportedOpenAiControls() {
        val options = ImageGenerationOptions(ImageResolution.K2, ImageAspectRatio(16, 9), ImageBackground.TRANSPARENT, ImageQuality.HIGH)
        val config = ProviderConfig("fixture", "Fixture", "https://example.invalid/v1beta", "fixture")
        val generate = ProviderApi.imageRequestBody(config.copy(protocol = ProviderProtocol.GEMINI), "gemini-3-pro-image", "fixture", options = options)
        val native = generate.getJSONObject("generationConfig").getJSONObject("imageConfig")
        assertEquals("2K", native.getString("imageSize"))
        assertEquals("16:9", native.getString("aspectRatio"))
        val interactions = ProviderApi.imageRequestBody(config.copy(protocol = ProviderProtocol.GEMINI_INTERACTIONS), "gemini-3-pro-image", "fixture", options = options)
        val format = interactions.getJSONObject("response_format")
        assertEquals("image", format.getString("type"))
        assertEquals("2K", format.getString("image_size"))
        assertEquals("16:9", format.getString("aspect_ratio"))
        listOf(generate, interactions).forEach {
            assertFalse(it.has("quality")); assertFalse(it.has("background")); assertFalse(it.has("size"))
            assertFalse(it.has("partial_images"))
        }
        val auto = ProviderApi.imageRequestBody(config.copy(protocol = ProviderProtocol.GEMINI), "gemini-3-pro-image", "fixture", options = ImageGenerationOptions())
        assertFalse(auto.getJSONObject("generationConfig").getJSONObject("imageConfig").has("aspectRatio"))
    }
}
