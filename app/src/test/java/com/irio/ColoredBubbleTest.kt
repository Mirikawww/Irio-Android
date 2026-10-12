package com.irio

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

class ColoredBubbleTest {
    @Test fun modelsCanChooseAnyRgbColorWithoutAPresetEnum() = runBlocking {
        val updates = mutableListOf<ChatStreamUpdate>()
        val context = ToolExecutionContext(ProviderConfig("p", "P", "https://example.test", ""), "model", onUpdate = { updates += it })
        val result = ColoredBubbleToolProvider().execute(ChatToolCall("c", ColoredBubbleTool.NAME,
            """{"bubbles":[{"text":"青色","color":"#17bebb"},{"text":"亮黄","color":"#FFF700"},{"text":"深色","color":"#123"}]}"""), context)
        assertFalse(result.text.startsWith("Invalid"))
        assertEquals(listOf("#17BEBB", "#FFF700", "#112233"), updates.single().coloredBubbles.map { it.color })
        for (protocol in ProviderProtocol.entries) {
            val wire = ColoredBubbleTool.schemaFor(protocol)
            val definition = wire.optJSONObject("function") ?: wire
            val parameters = definition.optJSONObject("parameters") ?: definition.optJSONObject("input_schema")
                ?: definition.getJSONObject("parametersJsonSchema")
            val color = parameters.getJSONObject("properties").getJSONObject("bubbles").getJSONObject("items")
                .getJSONObject("properties").getJSONObject("color")
            assertFalse("$protocol must not constrain the palette", color.has("enum"))
        }
    }

    @Test fun coloredBubblesAreAvailableToModelsAcrossAllProtocols() {
        val registry = ToolRegistry.default()
        assertTrue(registry.handles("send_colored_bubbles"))
        assertTrue(registry.systemPrompt(null)!!.contains("send_colored_bubbles"))
        for (protocol in ProviderProtocol.entries) {
            assertTrue("$protocol", registry.definitions(protocol).any { it.toString().contains("send_colored_bubbles") })
        }
    }
    @Test fun repeatedCallsPublishOrderedBubblesWithoutToolStatusUpdates() = runBlocking {
        val updates = mutableListOf<ChatStreamUpdate>()
        val registry = ToolRegistry(listOf(ColoredBubbleToolProvider()), smartToolHints = true)
        val context = ToolExecutionContext(ProviderConfig("p", "P", "https://example.test", ""), "model", onUpdate = { updates += it })
        repeat(2) {
            registry.execute(ChatToolCall("bubble-$it", "send_colored_bubbles",
                """{"bubbles":[{"text":"第一颗💗","color":"pink"},{"text":"第二颗💙","color":"blue"}],"_display_text":"发送气泡"}"""), context)
        }
        assertEquals(listOf("第一颗💗", "第二颗💙", "第一颗💗", "第二颗💙"), updates.flatMap { it.coloredBubbles }.map { it.text })
        assertTrue(updates.all { it.toolActive == null && it.searching == null && it.toolName.isEmpty() })
    }

    @Test fun invalidBatchAndCancellationProduceNoPartialOutput() = runBlocking {
        val updates = mutableListOf<ChatStreamUpdate>()
        val cancellation = ChatRequestCancellation()
        val context = ToolExecutionContext(ProviderConfig("p", "P", "https://example.test", ""), "model",
            cancellation = cancellation, onUpdate = { updates += it })
        val provider = ColoredBubbleToolProvider()
        for (args in listOf("{}", """{"bubbles":[]}""", """{"bubbles":[{"text":"ok","color":"pink"},{"text":"bad","color":"unknown"}]}""")) {
            assertTrue(provider.execute(ChatToolCall("c", "send_colored_bubbles", args), context).text.startsWith("Invalid"))
        }
        cancellation.cancel()
        provider.execute(ChatToolCall("c", "send_colored_bubbles", """{"bubbles":[{"text":"ok","color":"blue"}]}"""), context)
        assertTrue(updates.isEmpty())
    }

    @Test fun bubblesStayBetweenBeforeAndAfterTextAndCountAsReplyContent() {
        val tracker = ProcessTracker()
        tracker.onOutputStarted(textLength = 2)
        tracker.onColoredBubbles(listOf(ColoredBubble("粉色", "pink"), ColoredBubble("蓝色", "blue")))
        tracker.onOutputStarted(textLength = 4)
        tracker.finishAll()
        val events = tracker.snapshot()
        assertEquals(listOf("粉色", "蓝色"), events.map { it.content })
        assertTrue(events.all { it.kind == ProcessEventKind.BUBBLE && !it.live && it.textOffset == 2 })
        val pieces = interleaveTranscript("前文后文", events)
        assertEquals(4, pieces.size)
        assertEquals(TranscriptPiece.Text("前文"), pieces.first())
        assertEquals(TranscriptPiece.Text("后文"), pieces.last())
        assertTrue(hasReplyContent("", emptyList(), events))
        assertEquals("前文\n\n粉色\n\n蓝色\n\n后文", ChatMessage(1, "前文后文", false, process = events).visibleText())
        val loaded = processEventsFrom(JSONObject().put("process", org.json.JSONArray().put(JSONObject()
            .put("id", 1).put("kind", "BUBBLE").put("content", "粉色").put("bubbleColor", "pink").put("belowText", true).put("textOffset", 2))))
        assertEquals("pink", loaded.single().bubbleColor)
    }
    @Test fun persistedBubblesSurviveRealConversationJsonRoundTrip() {
        val message = ChatMessage(1, "前文后文", false, process = listOf(
            ProcessEvent(1, ProcessEventKind.BUBBLE, content = "💗 第一颗", belowText = true, textOffset = 2, bubbleColor = "pink"),
            ProcessEvent(2, ProcessEventKind.BUBBLE, content = "💙 第二颗", belowText = true, textOffset = 2, bubbleColor = "blue"),
        ))
        val conversation = Conversation("fixture", "气泡", 1, listOf(message))
        val serialize = ConversationStore::class.java.getDeclaredMethod("toJson", Conversation::class.java).apply { isAccessible = true }
        val parse = ConversationStore::class.java.getDeclaredMethod("conversationFromJson", JSONObject::class.java).apply { isAccessible = true }
        val json = serialize.invoke(ConversationStore, conversation) as JSONObject
        val restored = parse.invoke(ConversationStore, JSONObject(json.toString())) as Conversation
        assertEquals(message.process, restored.messages.single().process)
        assertEquals(message.visibleText(), restored.messages.single().visibleText())
        assertTrue(restored.messages.single().contextText().contains("💗 第一颗"))
    }

    @Test fun arbitraryRgbColorsRetainTheirHueAndReadableContrast() {
        val cyan = androidx.compose.ui.graphics.Color(0xFF17BEBB)
        assertEquals(cyan.softened(0.12f), coloredBubbleColors("#17BEBB", false).background)
        for (red in 0..255 step 17) for (green in 0..255 step 17) for (blue in 0..255 step 17) {
            val hex = "#%02X%02X%02X".format(red, green, blue)
            for (dark in listOf(false, true)) {
                val palette = coloredBubbleColors(hex, dark)
                assertTrue("$hex dark=$dark", wcagContrastRatio(palette.background, palette.content) >= 4.5f)
            }
        }
        assertNull(normalizeColoredBubbleColor("#GGHHII"))
        assertNull(normalizeColoredBubbleColor("#12345"))
        assertEquals("#AABBCC", normalizeColoredBubbleColor(" #abc "))
    }

    @Test fun everyPaletteHasReadableContrastInLightAndDarkThemes() {
        for (dark in listOf(false, true)) {
            for (color in listOf("#FFFFFF", "#000000", "#FFF700", "#17BEBB", "#E8DAF0", "#FF003C", "#003CFF")) {
                val palette = coloredBubbleColors(color, dark)
                assertTrue("$color dark=$dark", wcagContrastRatio(palette.background, palette.content) >= 4.5f)
            }
        }
    }

    @Test fun actualChatLoopCanSendConsecutiveBubbleOnlyRepliesSilently() = runBlocking {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            IrioHttp.resetForTests(IrioHttp.defaultClient())
            try {
                for (color in listOf("pink", "blue")) {
                    val arguments = JSONObject().put("bubbles", org.json.JSONArray().put(JSONObject().put("text", color).put("color", color)))
                    val delta = JSONObject().put("choices", org.json.JSONArray().put(JSONObject()
                        .put("delta", JSONObject().put("tool_calls", org.json.JSONArray().put(JSONObject()
                            .put("index", 0).put("id", color).put("function", JSONObject()
                                .put("name", ColoredBubbleTool.NAME).put("arguments", arguments.toString())))))
                        .put("finish_reason", "tool_calls")))
                    server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "text/event-stream")
                        .setBody("data: $delta\n\ndata: [DONE]\n\n"))
                }
                server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBody("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
                val updates = mutableListOf<ChatStreamUpdate>()
                val result = ChatApi.streamChat(ProviderConfig("p", "P", server.url("/v1").toString(), "fixture"),
                    "model", listOf(ChatRequestMessage("user", "连续发彩色气泡")), ReasoningEffort.OFF, false,
                    toolRegistry = ToolRegistry(listOf(ColoredBubbleToolProvider())),
                    cancellation = ChatRequestCancellation(), onUpdate = { updates += it })
                assertNull(result.error)
                assertTrue(result.content.isEmpty())
                assertEquals(listOf("#A82462", "#2258A5"), updates.flatMap { it.coloredBubbles }.map { it.color })
                assertTrue(updates.none { it.toolActive != null || it.searching != null })
                assertEquals(2, updates.count { it.toolRecord != null })
                val first = JSONObject(server.takeRequest().body.readUtf8())
                assertEquals(ColoredBubbleTool.NAME, first.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("name"))
                val second = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
                assertEquals("pink", second.getJSONObject(second.length() - 1).getString("tool_call_id"))
                val third = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
                assertEquals("blue", third.getJSONObject(third.length() - 1).getString("tool_call_id"))
            } finally { IrioHttp.resetForTests() }
        }
    }
}
