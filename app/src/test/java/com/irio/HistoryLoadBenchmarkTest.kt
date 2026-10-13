package com.irio

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 大会话打开路径的耗时基准：构造 N 条消息的合成会话，测量
 * 序列化 / 解析 / 转录构建 / 模型请求组装 / token 估算五段耗时。
 * 只打印比较值，不做性能断言（CI 机器性能不稳定）。
 */
class HistoryLoadBenchmarkTest {
    private val messageCount = 2000

    private fun syntheticConversation(): Conversation {
        val messages = ArrayList<ChatMessage>(messageCount)
        var id = 1L
        repeat(messageCount / 2) { round ->
            messages += ChatMessage(
                id = id++,
                text = "用户提问 $round " + "背景描述。".repeat(20),
                fromUser = true,
            )
            val body = buildString {
                append("这是第 $round 轮的模型回复。\n\n")
                append("```kotlin\nfun answer$round() = $round\n```\n\n")
                append("结论说明。".repeat(40))
            }
            messages += ChatMessage(
                id = id++,
                text = body,
                fromUser = false,
                process = listOf(
                    ProcessEvent(1L, ProcessEventKind.THINKING, content = "思考过程。".repeat(30), millis = 1200L),
                    ProcessEvent(2L, ProcessEventKind.TOOL, content = "工具输出 $round", toolName = "get_time"),
                ),
                toolRecords = listOf(
                    ChatToolRecord(ChatToolCall("c$round", "get_time", "{}"), "工具输出 $round"),
                ),
                finishedAt = System.currentTimeMillis(),
            )
        }
        return Conversation("benchmark", "基准", 1L, messages)
    }

    private fun measureMillis(rounds: Int, warmup: Int = 2, block: () -> Unit): Double {
        repeat(warmup) { block() }
        val times = LongArray(rounds) {
            val start = System.nanoTime()
            block()
            System.nanoTime() - start
        }
        return times.average() / 1_000_000.0
    }

    @Test
    fun largeConversationOpenPathBenchmark() {
        val conversation = syntheticConversation()
        var json = ""
        val serializeMs = measureMillis(3) { json = ConversationStore.conversationJson(conversation).toString() }
        var parsed: Conversation? = null
        val parseMs = measureMillis(5) { parsed = ConversationStore.parseConversation(JSONObject(json)) }
        val loaded = parsed!!
        assertEquals(messageCount, loaded.messages.size)
        val presentationMs = measureMillis(5) { buildTranscriptPresentation("benchmark", loaded.messages) }
        val requestMs = measureMillis(5) { modelRequestMessages(contextMessages(loaded.messages)) }
        val estimateMs = measureMillis(5) {
            estimateRequestTokens(modelRequestMessages(contextMessages(loaded.messages)))
        }
        println(
            "HistoryLoadBenchmark messages=$messageCount " +
                "serialize=${"%.1f".format(serializeMs)}ms " +
                "parse=${"%.1f".format(parseMs)}ms " +
                "presentation=${"%.1f".format(presentationMs)}ms " +
                "request=${"%.1f".format(requestMs)}ms " +
                "estimate=${"%.1f".format(estimateMs)}ms",
        )
    }
}
