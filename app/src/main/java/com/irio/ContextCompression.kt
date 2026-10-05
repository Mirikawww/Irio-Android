package com.irio

import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.EncodingType

/** 压缩边界。NONE 是普通消息；只有 SUCCESS 才把更早的消息隔在模型的新对话之外。界面只画状态行，不画摘要正文。 */
enum class SummaryState {
    NONE,
    RUNNING,
    SUCCESS,
    FAILED,
}

/** 消息列表里的状态文案。不带摘要正文。 */
internal fun compressionStatusLabel(state: SummaryState): String? = when (state) {
    SummaryState.RUNNING -> "上下文压缩中"
    SummaryState.SUCCESS -> "已压缩上下文"
    SummaryState.FAILED -> "上下文压缩失败"
    SummaryState.NONE -> null
}

/** 本地粗算。接口的 usage 要等整轮结束才到，触发和圆环在那之前靠这个。 */
object TokenEstimator {
    private val encoding by lazy {
        Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.O200K_BASE)
    }

    fun count(text: String): Int {
        if (text.isEmpty()) return 0
        return runCatching { encoding.countTokens(text) }.getOrElse { (text.length + 3) / 4 }
    }
}

internal fun tokensOfChatMessage(message: ChatMessage): Int = 4 + TokenEstimator.count(message.text)

/** 粗算即将发送的请求。正文之外，思考和工具调用的名字、参数也占上下文。工具结果在对应消息的 text 里。 */
internal fun estimateRequestTokens(messages: List<ChatRequestMessage>): Int {
    return messages.sumOf { message ->
        var count = 4 + TokenEstimator.count(message.text) + TokenEstimator.count(message.thinking)
        message.toolCalls.forEach { call ->
            count += TokenEstimator.count(call.name) + TokenEstimator.count(call.arguments)
        }
        count
    }
}
