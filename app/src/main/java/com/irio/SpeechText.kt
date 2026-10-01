package com.irio

internal data class SpeechVoice(
    val id: String,
    val label: String,
    val online: Boolean = false,
)

internal data class SpeechPlayback(
    val messageId: Long? = null,
    val active: Boolean = false,
    val playing: Boolean = false,
    val preparing: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
)

internal fun formatSpeechClock(ms: Int): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    return "%d:%02d".format(total / 60, total % 60)
}

/** 朗读用正文：去掉代码块、公式和标记，链接只留文字。 */
internal fun speechPlainText(markdown: String): String {
    if (markdown.isBlank()) return ""
    var text = markdown.replace("\r\n", "\n")
    text = FENCED_CODE.replace(text, " ")
    text = BLOCK_MATH.replace(text, " ")
    text = INLINE_MATH.replace(text, " ")
    text = IMAGE.replace(text, " ")
    text = LINK.replace(text, "$1")
    text = HEADING.replace(text, "")
    text = BLOCKQUOTE.replace(text, "")
    text = HTML.replace(text, " ")
    text = text.replace(MARKDOWN_MARKS, "")
    text = text.replace('|', ' ')
    text = text.replace(Regex("[ \\t]+"), " ")
    text = text.replace(Regex(" *\\n *"), "\n")
    text = text.replace(Regex("\\n{3,}"), "\n\n")
    return text.trim()
}

/**
 * 系统 TTS 单次合成有长度上限。按句读切开，方便第一段先出声，后面的段继续合成。
 */
internal fun speechChunks(text: String, limit: Int = SPEECH_CHUNK_LIMIT): List<String> {
    require(limit > 0)
    val clean = text.trim()
    if (clean.isEmpty()) return emptyList()
    if (clean.length <= limit) return listOf(clean)
    val chunks = mutableListOf<String>()
    var rest = clean
    while (rest.isNotEmpty()) {
        if (rest.length <= limit) {
            chunks += rest
            break
        }
        val window = rest.substring(0, limit)
        val first = window.indexOfAny(BREAK_CHARS)
        val last = window.lastIndexOfAny(BREAK_CHARS)
        val punct = when {
            first >= limit / 4 -> first
            last >= limit / 4 -> last
            else -> -1
        }
        val breakAt = if (punct >= 0) punct + 1 else limit
        val piece = rest.substring(0, breakAt).trim()
        rest = rest.substring(breakAt).trimStart()
        if (piece.isNotEmpty()) chunks += piece
    }
    return chunks
}

internal const val SPEECH_CHUNK_LIMIT = 450

private val BREAK_CHARS = charArrayOf('\n', '。', '！', '？', '.', '!', '?', '；', ';', '，', ',')
private val FENCED_CODE = Regex("```[\\s\\S]*?```")
private val BLOCK_MATH = Regex("\\$\\$[\\s\\S]*?\\$\\$")
private val INLINE_MATH = Regex("(?<!\\\\)\\$[^\\n$]+?\\$")
private val IMAGE = Regex("!\\[[^\\]]*\\]\\([^)]*\\)")
private val LINK = Regex("\\[([^\\]]+)\\]\\([^)]*\\)")
private val HEADING = Regex("(?m)^#{1,6}\\s*")
private val BLOCKQUOTE = Regex("(?m)^>\\s*")
private val HTML = Regex("<[^>\\n]+>")
private val MARKDOWN_MARKS = Regex("[*_`~]")
