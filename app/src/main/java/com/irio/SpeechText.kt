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
 * 数字、小数、千分位和时刻保持整段，否则「10」会被切成「一」和「零」。
 */
internal fun speechChunks(text: String, limit: Int = SPEECH_CHUNK_LIMIT): List<String> {
    require(limit > 0)
    val clean = text.trim()
    if (clean.isEmpty()) return emptyList()
    if (clean.length <= limit) return listOf(clean)
    val chunks = mutableListOf<String>()
    var start = 0
    while (start < clean.length) {
        if (clean.length - start <= limit) {
            val tail = clean.substring(start).trim()
            if (tail.isNotEmpty()) chunks += tail
            break
        }
        val punct = punctBreak(clean, start, limit)
        var end = if (punct >= 0) swallowFollowingNumber(clean, start, punct, limit) else hardCut(clean, start, limit)
        if (end <= start) end = (start + 1).coerceAtMost(clean.length)
        val piece = clean.substring(start, end).trim()
        var next = end
        while (next < clean.length && clean[next].isWhitespace()) next++
        if (piece.isNotEmpty()) chunks += piece
        start = if (next > start) next else end
    }
    return chunks
}

/**
 * 还没合成出音频时，用正文估算整篇时长。已测到的段替换估算，并按同样的语速修正剩下的段，
 * 进度条从一开始就是整篇长度，而不是每读完一段再长出一截。
 */
internal fun speechTimelineMs(chunks: List<String>, knownMs: IntArray, speechRate: Float): Int {
    var known = 0
    var knownEstimate = 0
    var pendingEstimate = 0
    for (index in chunks.indices) {
        val measured = knownMs.getOrElse(index) { -1 }
        val estimate = estimateSpeechMs(chunks[index], speechRate)
        if (measured >= 0) {
            known += measured
            knownEstimate += estimate
        } else {
            pendingEstimate += estimate
        }
    }
    if (pendingEstimate == 0) return known
    val pending = if (known > 0 && knownEstimate > 0) {
        ((pendingEstimate.toLong() * known) / knownEstimate).toInt()
    } else {
        pendingEstimate
    }
    return known + pending
}

internal fun estimateSpeechMs(text: String, speechRate: Float): Int {
    val rate = if (speechRate.isFinite() && speechRate > 0f) speechRate else 1f
    var weight = 0
    for (ch in text) {
        weight += when {
            ch.isWhitespace() -> 0
            ch.isDigit() -> 120
            isCjkSpeech(ch) -> 200
            ch in 'A'..'Z' || ch in 'a'..'z' -> 70
            else -> 80
        }
    }
    if (weight == 0) return 0
    return (weight / rate).toInt().coerceAtLeast(1)
}

internal const val SPEECH_CHUNK_LIMIT = 450

private const val NUMBER_SLACK = 24

private fun punctBreak(text: String, start: Int, limit: Int): Int {
    val windowEnd = (start + limit).coerceAtMost(text.length)
    val minIndex = start + limit / 4
    var first = -1
    var last = -1
    for (index in start until windowEnd) {
        if (!isBreakChar(text, index)) continue
        if (first < 0) first = index
        last = index
    }
    val chosen = when {
        first >= minIndex -> first
        last >= minIndex -> last
        else -> -1
    }
    return if (chosen >= 0) chosen + 1 else -1
}

private fun swallowFollowingNumber(text: String, start: Int, breakAt: Int, limit: Int): Int {
    var index = breakAt
    while (index < text.length && text[index].isWhitespace()) index++
    if (index >= text.length || !text[index].isDigit()) return breakAt
    val numberEnd = endOfNumber(text, index)
    val after = text.getOrNull(numberEnd)
    if ((after == '.' || after == '。' || after == '、') && text.getOrNull(numberEnd + 1)?.isDigit() != true) {
        return breakAt
    }
    return if (numberEnd - start <= limit + NUMBER_SLACK) numberEnd else breakAt
}

private fun hardCut(text: String, start: Int, limit: Int): Int {
    val window = (start + limit).coerceAtMost(text.length)
    if (window >= text.length) return text.length
    val splits = cutSplitsNumber(text, window)
    val startsNumber = startsNewNumber(text, window)
    if (!splits && !startsNumber) return window
    val numberStart = if (splits) rewindToNumberStart(text, window) else window
    val numberEnd = endOfNumber(text, numberStart)
    if (numberEnd - start <= limit + NUMBER_SLACK || numberStart <= start) return numberEnd
    return numberStart
}

private fun isBreakChar(text: String, index: Int): Boolean {
    val ch = text[index]
    if (ch == '\n' || ch == '。' || ch == '！' || ch == '？' || ch == '!' || ch == '?' || ch == '；' || ch == ';') {
        return true
    }
    if (ch == '，' || ch == ',' || ch == '.') return !isNumericSeparatorAt(text, index)
    return false
}

private fun isNumberSeparator(ch: Char): Boolean = ch == '.' || ch == ',' || ch == '，' || ch == ':' || ch == '：'

private fun isNumericSeparatorAt(text: String, index: Int): Boolean {
    if (!isNumberSeparator(text[index])) return false
    val prev = text.getOrNull(index - 1) ?: return false
    val next = text.getOrNull(index + 1) ?: return false
    return prev.isDigit() && next.isDigit()
}

private fun startsNewNumber(text: String, index: Int): Boolean {
    if (index < 0 || index >= text.length || !text[index].isDigit()) return false
    if (index == 0) return true
    if (text[index - 1].isDigit()) return false
    return !(index >= 2 && isNumberSeparator(text[index - 1]) && text[index - 2].isDigit())
}

private fun cutSplitsNumber(text: String, cut: Int): Boolean {
    if (cut <= 0 || cut >= text.length) return false
    val left = text[cut - 1]
    val right = text[cut]
    if (left.isDigit() && right.isDigit()) return true
    if (left.isDigit() && isNumericSeparatorAt(text, cut)) return true
    return isNumberSeparator(left) && right.isDigit() && text.getOrNull(cut - 2)?.isDigit() == true
}

private fun rewindToNumberStart(text: String, cut: Int): Int {
    var index = cut
    while (index > 0) {
        val prev = text[index - 1]
        if (prev.isDigit()) {
            index--
            continue
        }
        if (isNumberSeparator(prev) && index >= 2 && text[index - 2].isDigit()) {
            index--
            continue
        }
        break
    }
    return if (text.getOrNull(index)?.isDigit() == true) index else cut
}

private fun endOfNumber(text: String, digitIndex: Int): Int {
    var end = digitIndex
    while (end < text.length && text[end].isDigit()) end++
    while (end + 1 < text.length && isNumberSeparator(text[end]) && text[end + 1].isDigit()) {
        end++
        while (end < text.length && text[end].isDigit()) end++
    }
    return end
}

private fun isCjkSpeech(ch: Char): Boolean =
    ch in '\u3400'..'\u4dbf' || ch in '\u4e00'..'\u9fff' || ch in '\u3040'..'\u30ff' || ch in '\uac00'..'\ud7af'

private val FENCED_CODE = Regex("```[\\s\\S]*?```")
private val BLOCK_MATH = Regex("\\$\\$[\\s\\S]*?\\$\\$")
private val INLINE_MATH = Regex("(?<!\\\\)\\$[^\\n$]+?\\$")
private val IMAGE = Regex("!\\[[^\\]]*\\]\\([^)]*\\)")
private val LINK = Regex("\\[([^\\]]+)\\]\\([^)]*\\)")
private val HEADING = Regex("(?m)^#{1,6}\\s*")
private val BLOCKQUOTE = Regex("(?m)^>\\s*")
private val HTML = Regex("<[^>\\n]+>")
private val MARKDOWN_MARKS = Regex("[*_`~]")
