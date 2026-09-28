package com.irio

internal const val TEX_LINK_PREFIX = "irio-tex:"

internal sealed interface MarkdownSegment {
    val key: String

    data class Text_(override val key: String, val text: String, val markdown: Boolean) : MarkdownSegment
    data class TexBlock(override val key: String, val latex: String) : MarkdownSegment
}

/**
 * Block split shared by the streaming tail and the finished message.
 * Keys stay on the block index for the whole generation, and every text block is
 * real Markdown — including the still-open tail. A raw-text tail that later
 * upgrades to Markdown remounts the paragraph (the flash). Closed `$$` blocks
 * become formulas; an unclosed delimiter stays in the text block until it closes.
 */
internal fun markdownSegments(text: String): List<MarkdownSegment> {
    if (text.isEmpty()) return emptyList()
    val blocks = splitMarkdownBlocks(text)
    val segments = ArrayList<MarkdownSegment>(blocks.size)
    blocks.forEachIndexed { index, block ->
        val tex = texBlockContent(block)
        if (tex != null && texBlockClosed(block)) {
            segments += MarkdownSegment.TexBlock("tex$index", tex)
            return@forEachIndexed
        }
        val rewritten = if (block.trimStart().startsWith("```")) {
            block
        } else {
            normalizeMarkdownEmphasis(rewriteInlineTex(block))
        }
        segments += MarkdownSegment.Text_(
            key = "b$index",
            text = rewritten,
            markdown = true,
        )
    }
    return segments
}

/** Content of a standalone formula block (`$$…$$` or `\[…\]`), or null. */
internal fun texBlockContent(block: String): String? {
    val trimmed = block.trim()
    val doubleDollar = Regex("^\\$\\$(.+)\\$\\$$", RegexOption.DOT_MATCHES_ALL)
    val bracket = Regex("^\\\\\\[(.+)\\\\\\]$", RegexOption.DOT_MATCHES_ALL)
    doubleDollar.find(trimmed)?.let { return it.groupValues[1].trim() }
    bracket.find(trimmed)?.let { return it.groupValues[1].trim() }
    return null
}

/** True when the block is a formula block whose closing delimiter is present. */
internal fun texBlockClosed(block: String): Boolean {
    val trimmed = block.trim()
    if (trimmed.startsWith("$$")) return trimmed.drop(2).contains("$$")
    if (trimmed.startsWith("\\[")) return trimmed.drop(2).contains("\\]")
    return true
}

/** Rewrite inline `$…$` / `\(...\)` as `![](irio-tex:…)` image links for the ImageTransformer. */
internal fun rewriteInlineTex(block: String): String {
    var result = block
    // \( … \) first: not confused with \[ … \]
    result = Regex("\\\\\\((.+?)\\\\\\)", RegexOption.DOT_MATCHES_ALL).replace(result) { m ->
        val tex = m.groupValues[1].trim()
        if (tex.isEmpty()) m.value else "![](${TEX_LINK_PREFIX}${tex.escapeForTexLink()})"
    }
    result = Regex("(?<![\\\\$])\\$(\\S(?:[^\\n$]*\\S)?)(?<!\\\\)\\$(?!\\$)").replace(result) { m ->
        val tex = m.groupValues[1].trim()
        if (tex.isEmpty()) m.value else "![](${TEX_LINK_PREFIX}${tex.escapeForTexLink()})"
    }
    return result
}

internal fun String.escapeForTexLink(): String = replace(" ", "%20").replace(")", "%29")

internal fun inlineTexContents(markdown: String): List<String> =
    Regex("!\\[\\]\\(irio-tex:([^)]+)\\)")
        .findAll(markdown)
        .map { match -> match.groupValues[1].replace("%20", " ").replace("%29", ")") }
        .toList()

internal fun renderPrewarmOrder(itemCount: Int, firstVisible: Int, lastVisible: Int): List<Int> {
    if (itemCount <= 0) return emptyList()
    val last = itemCount - 1
    val start = firstVisible.coerceIn(0, last)
    val end = lastVisible.coerceIn(start, last)
    val visible = if (firstVisible < 0 || lastVisible < firstVisible) emptyList() else (start..end).toList()
    val before = ((visible.firstOrNull() ?: itemCount) - 1 downTo 0).toList()
    val after = ((visible.lastOrNull() ?: last) + 1..last).toList()
    return if (visible.isEmpty()) (last downTo 0).toList() else visible + before + after
}

internal fun splitMarkdownBlocks(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val blocks = ArrayList<String>()
    var start = 0
    var index = 0
    var inFence = false
    val length = text.length

    fun isLineStart(pos: Int) = pos == 0 || text[pos - 1] == '\n'

    fun isFenceAt(pos: Int): Boolean {
        if (!isLineStart(pos) || pos >= length) return false
        var cursor = pos
        var spaces = 0
        while (cursor < length && text[cursor] == ' ' && spaces < 3) {
            cursor++
            spaces++
        }
        return cursor + 2 < length &&
            text[cursor] == '`' &&
            text[cursor + 1] == '`' &&
            text[cursor + 2] == '`'
    }

    fun nextLine(pos: Int): Int {
        val newline = text.indexOf('\n', pos)
        return if (newline < 0) length else newline + 1
    }

    while (index < length) {
        if (isFenceAt(index)) {
            if (!inFence) {
                if (index > start) {
                    blocks += text.substring(start, index)
                    start = index
                }
                inFence = true
            } else {
                index = nextLine(index)
                blocks += text.substring(start, index)
                start = index
                inFence = false
                continue
            }
        } else if (!inFence && text[index] == '\n' && index + 1 < length && text[index + 1] == '\n') {
            var end = index + 1
            while (end < length && text[end] == '\n') end++
            blocks += text.substring(start, end)
            start = end
            index = end
            continue
        }
        index++
    }
    if (start < length) blocks += text.substring(start)
    return blocks
}

internal fun isCompleteMarkdownBlock(block: String): Boolean {
    val trimmedStart = block.trimStart()
    if (trimmedStart.startsWith("```")) {
        val fence = Regex("(?m)^ {0,3}```")
        return fence.findAll(block).count() >= 2
    }
    return block.endsWith("\n\n")
}

internal enum class MarkdownTableAlign { START, CENTER, END }

/** Parse a GFM table separator (`|:---|:---:|---:|`) into per-column alignments. */
internal fun parseTableAlignments(separatorLine: String): List<MarkdownTableAlign> {
    val line = separatorLine.trim().trim('|')
    if (line.isEmpty() || !line.contains('-')) return emptyList()
    return line.split('|').map { cell ->
        val trimmed = cell.trim().replace(" ", "")
        val left = trimmed.startsWith(':')
        val right = trimmed.endsWith(':')
        when {
            left && right -> MarkdownTableAlign.CENTER
            right -> MarkdownTableAlign.END
            else -> MarkdownTableAlign.START
        }
    }
}

/**
 * True when [text] holds `*` / `_` / `~` marker runs and every run is paired
 * (closed inline code spans excluded). Lets a streaming tail with closed emphasis
 * (`**加粗**`, `***斜体加粗***`) render as Markdown instead of staying raw.
 */
internal fun hasClosedEmphasis(text: String): Boolean {
    if (text.isEmpty()) return false
    val protected = Regex("`+[^`\n]*`+").replace(text, " ")
    val open = BooleanArray(128)
    var saw = false
    var index = 0
    while (index < protected.length) {
        val ch = protected[index]
        if (ch == '*' || ch == '_' || ch == '~') {
            saw = true
            val start = index
            while (index < protected.length && protected[index] == ch && index - start < 3) {
                index++
            }
            open[ch.code] = !open[ch.code]
        } else {
            index += Character.charCount(protected.codePointAt(index))
        }
    }
    return saw && open.none { it }
}

/**
 * CommonMark treats CJK letters as word characters, so `这是**加粗**文字`
 * is intra-word and the emphasis is dropped. Insert spaces *outside* the marker
 * runs (`这是 **加粗** 文字`) so flanking rules match. Inline code is left as-is.
 */
internal fun normalizeMarkdownEmphasis(text: String): String {
    if (text.isEmpty()) return text
    val codes = ArrayList<String>()
    val protected = Regex("`+[^`\\n]*`+").replace(text) { match ->
        codes += match.value
        "\u0000${codes.lastIndex}\u0000"
    }
    val sb = StringBuilder(protected.length + 8)
    val open = BooleanArray(128)
    var index = 0
    while (index < protected.length) {
        val ch = protected[index]
        if (ch == '*' || ch == '_' || ch == '~') {
            val start = index
            while (index < protected.length && protected[index] == ch && index - start < 3) {
                index++
            }
            val run = protected.substring(start, index)
            val opening = !open[ch.code]
            open[ch.code] = !open[ch.code]
            val prevCjk = sb.isNotEmpty() && isCjkLetter(sb.codePointBefore(sb.length))
            val nextCjk = index < protected.length && isCjkLetter(protected.codePointAt(index))
            if (opening && prevCjk && sb.last() != ' ') sb.append(' ')
            sb.append(run)
            if (!opening && nextCjk) sb.append(' ')
        } else {
            val code = protected.codePointAt(index)
            sb.appendCodePoint(code)
            index += Character.charCount(code)
        }
    }
    var result = sb.toString()
    codes.forEachIndexed { i, code ->
        result = result.replace("\u0000$i\u0000", code)
    }
    return result
}

internal fun isCjkLetter(code: Int): Boolean {
    val block = Character.UnicodeBlock.of(code) ?: return false
    return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
        block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
        block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
        block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
        block == Character.UnicodeBlock.HIRAGANA ||
        block == Character.UnicodeBlock.KATAKANA ||
        block == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS ||
        block == Character.UnicodeBlock.HANGUL_SYLLABLES ||
        block == Character.UnicodeBlock.HANGUL_JAMO ||
        block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
}
