package com.irio

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReactionToolTest {
    private val provider = ProviderConfig("test", "Fixture", "https://example.test/v1", "")
    private val tool = ReactionToolProvider()

    private fun call(id: Any = "123", emoji: Any = "❤️") = ChatToolCall(
        "reaction", ReactionTool.NAME, JSONObject().put("message_id", id).put("emoji", emoji).toString(),
    )

    @Test
    fun schemasExposeOneReplaceableRemovableReactionForEveryProtocol() {
        ProviderProtocol.entries.forEach { protocol ->
            val registry = ToolRegistry(listOf(tool))
            val definition = registry.definitions(protocol).single()
            val function = definition.optJSONObject("function") ?: definition
            assertEquals(ReactionTool.NAME, function.getString("name"))
            assertTrue(function.getString("description").contains("ONE"))
            assertTrue(function.getString("description").contains("17.0"))
            assertTrue(function.getString("description").contains("empty string removes"))
            assertTrue(registry.handles(ReactionTool.NAME))
        }
    }

    @Test
    fun validReactionAndRemovalEmitOnlyNotificationsWithoutLifecycleEvents() = runBlocking {
        val updates = mutableListOf<ChatStreamUpdate>()
        val reactions = mutableListOf<Pair<Long, String>>()
        val context = ToolExecutionContext(
            provider, "model", history = listOf(ChatRequestMessage("user", "hi", reactionTargetId = 123L)),
            onUpdate = { updates += it }, onReaction = { id, emoji -> reactions += id to emoji; true },
        )
        val first = tool.execute(call(emoji = "👍🏿"), context)
        assertEquals("👍", JSONObject(first.text).getString("emoji"))
        tool.execute(call(emoji = "❤"), context)
        tool.execute(call(emoji = ""), context)
        assertEquals(listOf(123L to "👍", 123L to "❤️", 123L to ""), reactions)
        // Each successful change notifies the background UI, without any chat lifecycle payload.
        assertEquals(List(3) { ChatStreamUpdate(notifyTool = ReactionTool.NAME) }, updates)
    }

    @Test
    fun unicode17ReactionsReachTheCallbackWithDefaultYellowNormalization() = runBlocking {
        val updates = mutableListOf<ChatStreamUpdate>()
        val reactions = mutableListOf<Pair<Long, String>>()
        val context = ToolExecutionContext(
            provider, "model", history = listOf(ChatRequestMessage("user", "hi", reactionTargetId = 123L)),
            onUpdate = { updates += it }, onReaction = { id, emoji -> reactions += id to emoji; true },
        )
        val cases = listOf("🫪" to "🫪", "🧑🏿‍🩰" to "🧑‍🩰", "👨🏻‍🫯‍👨🏿" to "🤼‍♂️")
        for ((input, expected) in cases) {
            val result = tool.execute(call(emoji = input), context)
            assertEquals(expected, JSONObject(result.text).getString("emoji"))
        }
        assertEquals(cases.map { 123L to it.second }, reactions)
        assertEquals(List(cases.size) { ChatStreamUpdate(notifyTool = ReactionTool.NAME) }, updates)
    }

    @Test
    fun malformedIdsForeignTargetsAndUnsupportedEmojiNeverReachTheCallback() = runBlocking {
        var called = false
        val context = ToolExecutionContext(
            provider, "model", history = listOf(
                ChatRequestMessage("user", "hi", reactionTargetId = 123L),
                ChatRequestMessage("assistant", "answer", reactionTargetId = 456L),
                ChatRequestMessage("user", "internal control"),
            ),
            onReaction = { _, _ -> called = true; true },
        )
        listOf("0", "0123", "+123", " 123", "123 ", "123.0", "9223372036854775808", 123, "456", "999")
            .forEach { id ->
                val result = tool.execute(call(id = id), context)
                assertFalse(result.text.startsWith("{"))
            }
        listOf("🫪🫪", "😀😀", "text", " ", 1, JSONObject.NULL).forEach { emoji ->
            assertFalse(tool.execute(call(emoji = emoji), context).text.startsWith("{"))
        }
        assertFalse(tool.execute(ChatToolCall("bad", ReactionTool.NAME, "not JSON"), context).text.startsWith("{"))
        assertFalse(called)
    }

    @Test
    fun cancelledRequestsDoNotApplyReactions() = runBlocking {
        val cancellation = ChatRequestCancellation().apply { cancel() }
        var called = false
        val result = tool.execute(call(), ToolExecutionContext(
            provider, "model", history = listOf(ChatRequestMessage("user", "hi", reactionTargetId = 123L)),
            cancellation = cancellation, onReaction = { _, _ -> called = true; true },
        ))
        assertTrue(result.text.contains("cancelled"))
        assertFalse(called)
    }

    @Test
    fun vanishedTargetReturnsRecoverableErrorWithoutLifecycleEvents() = runBlocking {
        val updates = mutableListOf<ChatStreamUpdate>()
        val result = tool.execute(call(), ToolExecutionContext(
            provider, "model", history = listOf(ChatRequestMessage("user", "hi", reactionTargetId = 123L)),
            onUpdate = { updates += it }, onReaction = { _, _ -> false },
        ))
        assertTrue(result.text.contains("no longer available"))
        assertTrue(updates.isEmpty())
    }

    @Test(timeout = 10_000)
    fun streamedModelReactionIsSilentAndNextRoundReceivesTheToolResult() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            IrioHttp.resetForTests(IrioHttp.defaultClient())
            try {
                val toolDelta = JSONObject().put("choices", JSONArray().put(JSONObject()
                    .put("delta", JSONObject().put("tool_calls", JSONArray().put(JSONObject()
                        .put("index", 0).put("id", "react1").put("type", "function")
                        .put("function", JSONObject().put("name", ReactionTool.NAME).put("arguments", call().arguments)))))
                    .put("finish_reason", "tool_calls")))
                server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBody("data: $toolDelta\n\ndata: [DONE]\n\n"))
                server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"回答\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
                val updates = mutableListOf<ChatStreamUpdate>()
                val reactions = mutableListOf<Pair<Long, String>>()
                val result = ChatApi.streamChat(
                    provider = provider.copy(endpointUrl = server.url("/v1").toString()), model = "test-model",
                    messages = listOf(ChatRequestMessage("user", "你好", reactionTargetId = 123L)),
                    reasoningEffort = ReasoningEffort.OFF, fastMode = false,
                    toolRegistry = ToolRegistry(listOf(tool)), cancellation = ChatRequestCancellation(),
                    onUpdate = { updates += it }, onReaction = { id, emoji -> reactions += id to emoji; true },
                )
                assertNull(result.error)
                assertEquals("回答", result.content)
                assertEquals(listOf(123L to "❤️"), reactions)
                assertTrue(updates.none { it.toolActive != null || it.searching != null })
                assertEquals(
                    listOf(ChatStreamUpdate(notifyTool = ReactionTool.NAME)),
                    updates.filter { it.notifyTool.isNotEmpty() },
                )
                val first = JSONObject(server.takeRequest().body.readUtf8())
                assertTrue(first.getJSONArray("messages").getJSONObject(0).getString("content")
                    .endsWith("[Irio message_id: 123]"))
                assertEquals(ReactionTool.NAME, first.getJSONArray("tools").getJSONObject(0)
                    .getJSONObject("function").getString("name"))
                val next = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
                val returned = next.getJSONObject(next.length() - 1)
                assertEquals("tool", returned.getString("role"))
                assertEquals("react1", returned.getString("tool_call_id"))
                assertEquals("❤️", JSONObject(returned.getString("content")).getString("emoji"))
            } finally {
                IrioHttp.resetForTests()
            }
        }
    }
}
