package com.irio

import android.content.Context
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CommonsImageToolTest {
    private val app get() = RuntimeEnvironment.getApplication() as Context
    private val config = ProviderConfig("fixture", "Fixture", "https://model.test/v1", "model-secret")

    @Test fun searchesGoogleImagesThroughSerpApi() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(response))
            routeTo(server)
            try {
                val updates = mutableListOf<ChatStreamUpdate>()
                val result = CommonsImageToolProvider().execute(
                    ChatToolCall("search", "image_search", """{"query":"红熊猫 & forest","limit":2,"offset":4}"""),
                    ToolExecutionContext(config, "model", webSearch = true, imageSearchApiKey = "serp-secret", appContext = app, onUpdate = { updates += it }),
                )
                assertTrue("Search must return structured image results", result.text.startsWith("{"))
                val json = JSONObject(result.text)
                val images = json.getJSONArray("images")
                assertEquals(2, images.length())
                assertEquals("img:1", images.getJSONObject(0).getString("id"))
                assertEquals("First", images.getJSONObject(0).getString("title"))
                assertEquals("Example", images.getJSONObject(0).getString("source"))
                assertEquals("https://cdn.example/first-thumb.png", images.getJSONObject(0).getString("preview_url"))
                assertEquals(5, json.getInt("next_offset"))
                assertTrue(json.getBoolean("untrusted_content"))
                assertTrue(result.modelAttachments.isEmpty())
                assertTrue(updates.all { it.attachments.isEmpty() && it.displayedImages.isEmpty() })
                val request = server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals("GET", request.method)
                assertEquals("google_images", request.requestUrl!!.queryParameter("engine"))
                assertEquals("红熊猫 & forest", request.requestUrl!!.queryParameter("q"))
                assertEquals("serp-secret", request.requestUrl!!.queryParameter("api_key"))
                assertEquals("4", request.requestUrl!!.queryParameter("ijn"))
                assertTrue(request.getHeader("User-Agent")!!.startsWith("Irio/"))
                assertNull(request.getHeader("Authorization"))
                assertEquals(listOf(true, false), updates.mapNotNull { it.toolActive })
                assertEquals(result.text, updates.last().toolContent)
            } finally { IrioHttp.resetForTests() }
        }
    }

    private fun routeTo(server: MockWebServer) {
        IrioHttp.resetForTests(IrioHttp.defaultClient().newBuilder().addInterceptor { chain ->
            val request = chain.request()
            chain.proceed(request.newBuilder().url(server.url(request.url.encodedPath).newBuilder()
                .encodedQuery(request.url.encodedQuery).build()).build())
        }.build())
    }

    @Test fun selectedImagesAreDisplayedFromThePreviewUrlWithoutDownloading() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(response))
            routeTo(server)
            try {
                val tools = CommonsImageToolProvider()
                val context = ToolExecutionContext(config, "model", webSearch = true, imageSearchApiKey = "serp-secret", appContext = app)
                tools.execute(ChatToolCall("s", "image_search", """{"query":"panda"}"""), context)
                val updates = mutableListOf<ChatStreamUpdate>()
                val shown = tools.execute(ChatToolCall("show", "show_images", """{"image_ids":["img:1"]}"""),
                    context.copy(onUpdate = { updates += it }))
                val displayed = updates.single { it.displayedImages.isNotEmpty() }.displayedImages
                val image = JSONObject(displayed).getJSONArray("images").getJSONObject(0)
                assertTrue("Display must not send images as model input", shown.modelAttachments.isEmpty())
                assertTrue(updates.all { it.attachments.isEmpty() })
                assertTrue("Display must not emit tool status hints", updates.none { it.toolActive != null || it.searching != null })
                assertEquals("https://cdn.example/first-thumb.png", image.getString("preview_url"))
                assertFalse(image.has("file_path"))
                assertEquals("Search metadata is enough; display must not fetch the image", 1, server.requestCount)
                assertTrue(shown.text.contains("img:1"))
                assertFalse(shown.text.contains("file_path"))
                assertFalse(shown.text.contains(app.filesDir.absolutePath))
            } finally { IrioHttp.resetForTests() }
        }
    }

    private val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=")

    @Test fun displayedImagesStayAtTheirTextAnchorEvenWhenToolCallsAreMerged() {
        val turn = SubagentTurn()
        turn.apply(ChatStreamUpdate(delta = "Before"))
        turn.apply(ChatStreamUpdate(displayedImages = """{"images":[{"id":"commons:11"}]}"""))
        turn.apply(ChatStreamUpdate(delta = "After"))
        val image = turn.process().single()
        assertEquals("show_images", image.toolName)
        assertFalse(image.live)
        assertEquals(6, image.textOffset)
        val events = listOf(ProcessEvent(100, ProcessEventKind.TOOL, toolName = TimeTool.NAME)) + turn.process()
        val rendered = renderedProcessEvents(events, true)
        assertTrue("Image output must not be swallowed by merged tool summaries", rendered.any { it.id == image.id })
        assertFalse(mergedToolSummary(turn.process()).contains("使用了"))
        val restored = processEventsFrom(JSONObject().put("process", subagentProcessJson(turn.process())))
        assertEquals(turn.process(), restored)
        val pieces = interleaveTranscript(turn.text(), turn.process())
        assertEquals(listOf("Before", "After"), pieces.filterIsInstance<TranscriptPiece.Text>().map { it.text })
    }

    @Test fun realChatAcrossEveryProtocolSendsPixelsOnlyAfterExplicitView() = runBlocking {
        org.robolectric.shadows.ShadowEnvironment.addExternalDir(app.cacheDir.resolve("external").absolutePath)
        for (protocol in ProviderProtocol.entries.filter { it != ProviderProtocol.MINIMAX_VIDEO }) {
            MockWebServer().use { media -> MockWebServer().use { model ->
                media.enqueue(MockResponse().setBody(response))
                media.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(okio.Buffer().write(png)))
                IrioHttp.resetForTests(IrioHttp.defaultClient().newBuilder().addInterceptor { chain ->
                    val request = chain.request()
                    val target = if (request.url.host == "serpapi.com" || request.url.host == "cdn.example") request.newBuilder()
                        .url(media.url(request.url.encodedPath).newBuilder().encodedQuery(request.url.encodedQuery).build()).build() else request
                    chain.proceed(target)
                }.build())
                try {
                    model.enqueue(toolFrame(protocol, "search", "image_search", """{"query":"panda","limit":2}"""))
                    model.enqueue(toolFrame(protocol, "show", "show_images", """{"image_ids":["img:1"]}"""))
                    model.enqueue(toolFrame(protocol, "view", "view_images", """{"image_ids":["img:1"]}"""))
                    model.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(when (protocol) {
                        ProviderProtocol.MINIMAX_VIDEO -> error("Video protocol is not chat")
                        ProviderProtocol.CHAT_COMPLETIONS -> "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                        ProviderProtocol.RESPONSES -> "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n"
                        ProviderProtocol.ANTHROPIC -> "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}\n\n"
                        ProviderProtocol.GEMINI -> "data: {\"candidates\":[{\"content\":{\"parts\":[]},\"finishReason\":\"STOP\"}]}\n\n"
                        ProviderProtocol.GEMINI_INTERACTIONS -> "data: {\"event_type\":\"interaction.completed\",\"interaction\":{\"status\":\"completed\"}}\n\n"
                    }))
                    val updates = mutableListOf<ChatStreamUpdate>()
                    val registry = chatToolRegistry(app, webSearch = true, imageGenModel = "", imageSearch = true)
                    val result = ChatApi.streamChat(config.copy(endpointUrl = model.url("/v1beta").toString(), protocol = protocol),
                        "model", listOf(ChatRequestMessage("user", "Search and display a panda; inspect it only if needed")),
                        ReasoningEffort.OFF, false, webSearch = true, imageSearchApiKey = "serp-secret", toolRegistry = registry, appContext = app,
                        cancellation = ChatRequestCancellation(), onUpdate = { updates += it })
                    assertNull("$protocol: ${result.error}; records=${updates.mapNotNull { it.toolRecord }}", result.error)
                    val requests = (0..3).map { model.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8().replace("\\/", "/") }
                    val encoded = java.util.Base64.getEncoder().encodeToString(png)
                    assertTrue("$protocol advertised image search", requests[0].contains("image_search"))
                    assertTrue("$protocol advertised silent display", requests[0].contains("show_images"))
                    assertFalse("$protocol search must not send pixels", requests[1].contains(encoded))
                    assertFalse("$protocol showing must not send pixels", requests[2].contains(encoded))
                    assertTrue("$protocol explicit view must send actual image pixels", requests[3].contains(encoded))
                    assertEquals("Only the search and requested image should be fetched", 2, media.requestCount)
                    assertEquals(1, updates.count { it.displayedImages.isNotEmpty() })
                    assertFalse(updates.any { it.toolName == "show_images" && it.toolActive != null })
                    assertEquals(listOf("image_search", "show_images", "view_images"), updates.mapNotNull { it.toolRecord?.call?.name })
                    assertTrue(updates.mapNotNull { it.toolRecord }.all { !it.result.contains("file_path") })
                } finally { IrioHttp.resetForTests() }
            } }
        }
    }

    private fun toolFrame(protocol: ProviderProtocol, id: String, name: String, args: String): MockResponse {
        val body = when (protocol) {
            ProviderProtocol.MINIMAX_VIDEO -> error("Video protocol is not chat")
            ProviderProtocol.CHAT_COMPLETIONS -> {
                val call = JSONObject().put("index", 0).put("id", id).put("function", JSONObject().put("name", name).put("arguments", args))
                val frame = JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("delta", JSONObject()
                    .put("tool_calls", org.json.JSONArray().put(call))).put("finish_reason", "tool_calls")))
                "data: $frame\n\ndata: [DONE]\n\n"
            }
            ProviderProtocol.RESPONSES -> {
                val item = JSONObject().put("type", "function_call").put("id", id).put("call_id", id).put("name", name).put("arguments", args)
                "data: ${JSONObject().put("type", "response.output_item.added").put("item", item)}\n\n" +
                    "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n"
            }
            ProviderProtocol.ANTHROPIC -> {
                val start = JSONObject().put("type", "content_block_start").put("index", 0).put("content_block", JSONObject()
                    .put("type", "tool_use").put("id", id).put("name", name).put("input", JSONObject()))
                val delta = JSONObject().put("type", "content_block_delta").put("index", 0).put("delta", JSONObject()
                    .put("type", "input_json_delta").put("partial_json", args))
                "event: content_block_start\ndata: $start\n\nevent: content_block_delta\ndata: $delta\n\n" +
                    "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"}}\n\n"
            }
            ProviderProtocol.GEMINI -> {
                val call = JSONObject().put("functionCall", JSONObject().put("name", name).put("args", JSONObject(args)))
                val candidate = JSONObject().put("content", JSONObject().put("parts", org.json.JSONArray().put(call))).put("finishReason", "STOP")
                "data: ${JSONObject().put("candidates", org.json.JSONArray().put(candidate))}\n\n"
            }
            ProviderProtocol.GEMINI_INTERACTIONS -> {
                val step = JSONObject().put("type", "function_call").put("id", id).put("name", name).put("arguments", JSONObject(args))
                "data: ${JSONObject().put("event_type", "step.start").put("index", 0).put("step", step)}\n\n" +
                    "data: ${JSONObject().put("event_type", "step.delta").put("index", 0).put("delta", JSONObject().put("type", "arguments_delta").put("arguments", args))}\n\n" +
                    "data: {\"event_type\":\"step.stop\",\"index\":0}\n\n" +
                    "data: {\"event_type\":\"interaction.completed\",\"interaction\":{\"status\":\"completed\"}}\n\n"
            }
        }
        return MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)
    }

    @Test fun invalidInputsCancelledCallsAndDisabledToolsNeverMakeNetworkRequests() = runBlocking {
        MockWebServer().use { server ->
            routeTo(server)
            try {
                val tools = CommonsImageToolProvider()
                val closed = ToolExecutionContext(config, "model", appContext = app)
                val blocked = tools.execute(ChatToolCall("off", "image_search", """{"query":"panda"}"""), closed)
                assertTrue(blocked.text.contains("当前不可用"))
                val blockedShow = tools.execute(ChatToolCall("off", "show_images", """{"image_ids":["commons:11"]}"""), closed)
                assertTrue(blockedShow.text.contains("当前不可用"))
                val context = closed.copy(webSearch = true, imageSearchApiKey = "serp-secret")
                for (args in listOf("{}", """{"query":" "}""", """{"query":"x","limit":11}""", """{"query":"x","limit":1.5}""")) {
                    val result = tools.execute(ChatToolCall("bad", "image_search", args), context)
                    assertEquals("invalid_arguments", JSONObject(result.text).getJSONObject("error").getString("code"))
                }
                val cancellation = ChatRequestCancellation().apply { cancel() }
                val updates = mutableListOf<ChatStreamUpdate>()
                val stopped = tools.execute(ChatToolCall("stop", "show_images", """{"image_ids":["commons:11"]}"""),
                    context.copy(cancellation = cancellation, onUpdate = { updates += it }))
                assertEquals("cancelled", JSONObject(stopped.text).getJSONObject("error").getString("code"))
                assertEquals(listOf(true, false), updates.mapNotNull { it.toolActive })
                assertEquals(ToolOutcome.STOPPED, updates.last().toolOutcome)
                val registry = ToolRegistry(listOf(tools), preferences = ToolPreferences(setOf("show_images", "view_images", "image_search")))
                for (name in listOf("image_search", "show_images", "view_images")) {
                    assertTrue(registry.execute(ChatToolCall("off", name, "{}"), context).text.contains("disabled"))
                }
                assertEquals(0, server.requestCount)
            } finally { IrioHttp.resetForTests() }
        }
    }

    @Test fun apiAndHttpFailuresDoNotLookLikeSuccessfulSearches() = runBlocking {
        MockWebServer().use { server ->
            routeTo(server)
            try {
                val context = ToolExecutionContext(config, "model", webSearch = true, imageSearchApiKey = "serp-secret", appContext = app)
                for ((response, expected) in listOf(
                    MockResponse().setResponseCode(429) to "http_429",
                    MockResponse().setBody("""{"error":{"code":"maxlag","info":"Please wait"}}""") to "maxlag",
                    MockResponse().setBody("not json") to "search_failed",
                )) {
                    server.enqueue(response)
                    val updates = mutableListOf<ChatStreamUpdate>()
                    val result = CommonsImageToolProvider().execute(ChatToolCall("s", "image_search", """{"query":"panda"}"""),
                        context.copy(onUpdate = { updates += it }))
                    assertEquals(expected, JSONObject(result.text).getJSONObject("error").getString("code"))
                    assertEquals(listOf(true, false), updates.mapNotNull { it.toolActive })
                    assertEquals("图片搜索失败", MemoryTool.doneLabel("image_search", result.text))
                }
            } finally { IrioHttp.resetForTests() }
        }
    }

    @Test fun nonImagesAndUntrustedMediaHostsAreExcludedBeforeAnyImageDownload() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(response
                .replace("https://cdn.example/first.png", "http://127.0.0.1/private")
                .replace("https://cdn.example/first-thumb.png", "http://127.0.0.1/thumb")
                .replace("https://cdn.example/second.png", "http://127.0.0.1/second")
                .replace("https://cdn.example/second-thumb.png", "http://127.0.0.1/second-thumb")))
            routeTo(server)
            try {
                val result = CommonsImageToolProvider().execute(ChatToolCall("s", "image_search", """{"query":"panda"}"""),
                    ToolExecutionContext(config, "model", webSearch = true, imageSearchApiKey = "serp-secret", appContext = app))
                assertEquals(0, JSONObject(result.text).getJSONArray("images").length())
                assertEquals(1, server.requestCount)
            } finally { IrioHttp.resetForTests() }
        }
    }

    @Test fun imageToolsFollowTheWebSearchSwitchWithoutTheirOwnKey() {
        org.robolectric.shadows.ShadowEnvironment.addExternalDir(app.cacheDir.resolve("external").absolutePath)
        val names = listOf("image_search", "show_images", "view_images")
        val off = ToolCatalog.entries(ToolFeatureState())
        names.forEach { assertEquals(ToolBlockedReason.WEB_SEARCH, off.single { entry -> entry.name == it }.blockedReason) }
        val missingKey = ToolCatalog.entries(ToolFeatureState(searchEnabled = true))
        names.forEach { assertEquals(ToolBlockedReason.IMAGE_SEARCH_KEY, missingKey.single { entry -> entry.name == it }.blockedReason) }
        val ready = ToolCatalog.entries(ToolFeatureState(searchEnabled = true, imageSearchConfigured = true))
        names.forEach { assertNull(ready.single { entry -> entry.name == it }.blockedReason) }
        for (enabled in listOf(true, false)) {
            names.forEach { ToolPreferencesStore.saveEnabled(app, it, enabled) }
            for (hints in listOf(true, false)) {
                ToolStore.saveSmartToolHints(app, hints)
                val closed = chatToolRegistry(app, false, "")
                val open = chatToolRegistry(app, true, "", imageSearch = true)
                for (protocol in ProviderProtocol.entries) {
                    val closedSchemas = closed.schemas(protocol).map { it.optJSONObject("function") ?: it }
                    val openSchemas = open.schemas(protocol).map { it.optJSONObject("function") ?: it }
                    names.forEach { name ->
                        assertFalse(closedSchemas.any { it.optString("name") == name })
                        assertEquals(enabled, openSchemas.any { it.optString("name") == name })
                    }
                    openSchemas.singleOrNull { it.optString("name") == "show_images" }?.let {
                        assertFalse("Silent display must not request a UI status label", it.toString().contains(SmartToolHints.ARGUMENT))
                    }
                }
            }
        }
        names.forEach { ToolPreferencesStore.saveEnabled(app, it, true) }
    }

    @Test fun savedDisplayEventsNeverAutomaticallyBecomeModelImageInputOrLeakLocalPaths() {
        val image = """{"images":[{"id":"commons:11","file_path":"/private/attachments/commons-local.png","source_url":"https://commons.wikimedia.org/w/index.php?curid=11"}]}"""
        val message = ChatMessage(1, "A panda", false, process = listOf(
            ProcessEvent(1, ProcessEventKind.TOOL, toolName = "show_images", content = image)))
        val request = modelRequestMessages(listOf(message))
        assertTrue(request.all { it.attachments.isEmpty() })
        assertFalse("Local display paths are not model context", request.any { it.text.contains("/private/") })
        assertTrue(request.any { it.text.contains("commons:11") })
    }

    private val response = """{
      "serpapi_pagination":{"current":4,"next":"https://serpapi.com/search.json?ijn=5"},
      "images_results":[
        {"position":1,"title":"First","source":"Example","link":"https://example.com/first","original":"https://cdn.example/first.png","thumbnail":"https://cdn.example/first-thumb.png","original_width":800,"original_height":600},
        {"position":2,"title":"Second","source":"Other","link":"https://example.com/second","original":"https://cdn.example/second.png","thumbnail":"https://cdn.example/second-thumb.png","original_width":640,"original_height":480}
      ]}
    """.trimIndent()
}
