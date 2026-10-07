package com.irio

/** 引用使用普通 Markdown 引用块传给各协议，正文仍独立用于显示和编辑。 */
internal fun ChatMessage.requestText(): String {
    if (!fromUser || quote.isBlank()) return text
    val quoted = quote.lineSequence().joinToString("\n") { "> $it" }
    return if (text.isEmpty()) quoted else "$quoted\n\n$text"
}
