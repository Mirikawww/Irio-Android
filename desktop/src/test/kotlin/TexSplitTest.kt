import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse

// Mirrors the exact splitting helpers in ChatScreen.kt (kept in sync by copy).
private const val TEX_LINK_PREFIX = "irio-tex:"

private fun texBlockContent(block: String): String? {
    val trimmed = block.trim()
    val doubleDollar = Regex("^\\$\\$(.+)\\$\\$$", RegexOption.DOT_MATCHES_ALL)
    val bracket = Regex("^\\\\\\[(.+)\\\\\\]$", RegexOption.DOT_MATCHES_ALL)
    doubleDollar.find(trimmed)?.let { return it.groupValues[1].trim() }
    bracket.find(trimmed)?.let { return it.groupValues[1].trim() }
    return null
}

private fun rewriteInlineTex(block: String): String {
    var result = block
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

private fun String.escapeForTexLink(): String = replace(" ", "%20").replace(")", "%29")

private fun scaleCjkForDensity(latex: String, pxPerPt: Float): String {
    if (pxPerPt <= 1.01f || latex.isEmpty()) return latex
    val scale = formatTexScale(pxPerPt)
    val out = StringBuilder(latex.length + 16)
    var index = 0
    while (index < latex.length) {
        val code = latex.codePointAt(index)
        val width = Character.charCount(code)
        if (isTexCjkCode(code)) {
            val start = index
            index += width
            while (index < latex.length) {
                val next = latex.codePointAt(index)
                if (!isTexCjkCode(next)) break
                index += Character.charCount(next)
            }
            out.append("\\scalebox{").append(scale).append("}{")
            out.append(latex, start, index)
            out.append('}')
        } else {
            out.appendCodePoint(code)
            index += width
        }
    }
    return out.toString()
}

private fun formatTexScale(value: Float): String {
    val rounded = (value * 1000f).toInt() / 1000f
    return if (rounded == rounded.toInt().toFloat()) {
        rounded.toInt().toString()
    } else {
        rounded.toString()
    }
}

private fun isTexCjkCode(code: Int): Boolean {
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

class TexSplitTest {
    @Test fun blockDoubleDollar() {
        assertEquals("\\frac{1}{2}", texBlockContent("\$\$\n\\frac{1}{2}\n\$\$"))
        assertEquals("x", texBlockContent("\$\$x\$\$"))
    }
    @Test fun blockBracket() {
        assertEquals("E = mc^2", texBlockContent("\\[ E = mc^2 \\]"))
    }
    @Test fun notABlock() {
        assertNull(texBlockContent("plain text with \$x^2\$ inside"))
        assertNull(texBlockContent("## Heading"))
        assertNull(texBlockContent("- list item"))
    }
    @Test fun inlineDollar() {
        assertEquals("value ![](irio-tex:x^2%20+%20y^2) here", rewriteInlineTex("value \$x^2 + y^2\$ here"))
        assertEquals("a ![](irio-tex:b) c ![](irio-tex:d) e", rewriteInlineTex("a \$b\$ c \$d\$ e"))
    }
    @Test fun inlineParen() {
        assertEquals("value ![](irio-tex:x^2) here", rewriteInlineTex("value \\(x^2\\) here"))
        assertEquals("![](irio-tex:\\frac{a}{b})", rewriteInlineTex("\\(\\frac{a}{b}\\)"))
    }
    @Test fun currencyNotFormula() {
        assertFalse(rewriteInlineTex("costs 5\$ and 10\$ total").contains("irio-tex"))
    }
    @Test fun escapedDollarStays() {
        assertFalse(rewriteInlineTex("paid \\\$5 for it").contains("irio-tex"))
    }

    @Test fun cjkUnchangedAtDensity1() {
        assertEquals("动能=\\frac{1}{2}mv^2", scaleCjkForDensity("动能=\\frac{1}{2}mv^2", 1f))
    }

    @Test fun cjkScaledAtDensity3() {
        assertEquals(
            "\\scalebox{3}{动能}=\\frac{1}{2}mv^2",
            scaleCjkForDensity("动能=\\frac{1}{2}mv^2", 3f),
        )
    }

    @Test fun latinUnchangedAtDensity3() {
        assertEquals("x^2 + y^2", scaleCjkForDensity("x^2 + y^2", 3f))
    }
}
