package com.mikepenz.markdown.compose.elements

/** 每个源码行的起始下标。末尾换行也算一行，和文本布局的空行一致。 */
internal fun codeLineStartOffsets(text: String): IntArray {
    if (text.isEmpty()) return intArrayOf(0)
    val starts = ArrayList<Int>(text.count { it == '\n' } + 1)
    starts.add(0)
    text.forEachIndexed { index, ch ->
        if (ch == '\n') starts.add(index + 1)
    }
    return starts.toIntArray()
}

/**
 * 每个源码行占了几条视觉行。[lineForOffset] 与 [androidx.compose.ui.text.TextLayoutResult.getLineForOffset] 相同。
 * 折行只增加该源码行的行数，不新开一个行号。
 */
internal fun codeSourceLineVisualCounts(
    text: String,
    lineForOffset: (Int) -> Int,
): IntArray {
    val starts = codeLineStartOffsets(text)
    return IntArray(starts.size) { index ->
        val startOffset = starts[index].coerceAtMost(text.length)
        val startLine = lineForOffset(startOffset)
        val endOffset = if (index + 1 < starts.size) {
            (starts[index + 1] - 1).coerceAtLeast(startOffset)
        } else {
            text.length
        }
        val endLine = lineForOffset(endOffset.coerceAtMost(text.length))
        (endLine - startLine + 1).coerceAtLeast(1)
    }
}

/** 行号基线对齐代码该行的基线。 */
internal fun codeLineBaselines(
    text: String,
    lineForOffset: (Int) -> Int,
    baselineOfLine: (Int) -> Int,
): IntArray {
    val starts = codeLineStartOffsets(text)
    return IntArray(starts.size) { index ->
        baselineOfLine(lineForOffset(starts[index].coerceAtMost(text.length)))
    }
}

internal fun codeLineNumberTop(codeBaselinePx: Int, numberBaselinePx: Int): Int =
    codeBaselinePx - numberBaselinePx

/** 行号文本。某一源码行折成多行时，后面补空行，让下一个行号仍落在下一源码行。 */
internal fun codeLineNumberLabel(visualLinesPerSourceLine: IntArray): String = buildString {
    visualLinesPerSourceLine.forEachIndexed { index, visualLines ->
        if (index > 0) append('\n')
        append(index + 1)
        repeat((visualLines - 1).coerceAtLeast(0)) { append('\n') }
    }
}
