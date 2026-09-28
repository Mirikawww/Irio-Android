package com.irio

import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownSegmentsTest {
    @Test
    fun frozenBlockKeepsStableKeyWhileTailGrows() {
        val first = markdownSegments("Hello world\n\nMore")
        assertEquals(2, first.size)
        assertEquals("b0", first[0].key)
        assertEquals("b1", first[1].key)
        val frozen = (first[0] as MarkdownSegment.Text_).text

        val second = markdownSegments("Hello world\n\nMore text arriving")
        assertEquals("b0", second[0].key)
        assertEquals(frozen, (second[0] as MarkdownSegment.Text_).text)
        assertEquals("b1", second[1].key)
        assertEquals("More text arriving", (second[1] as MarkdownSegment.Text_).text)
        assertTrue((second[1] as MarkdownSegment.Text_).markdown)
    }

    @Test
    fun finishedTextUsesTheSameSegmentAsTheStream() {
        val streaming = markdownSegments("Hello world")
        assertEquals("b0", streaming.single().key)
        assertTrue((streaming.single() as MarkdownSegment.Text_).markdown)
        assertEquals(streaming, markdownSegments("Hello world"))
    }

    @Test
    fun cjkEmphasisGetsFlankingSpaces() {
        assertEquals("这是 **加粗** 文字", normalizeMarkdownEmphasis("这是**加粗**文字"))
        assertEquals("**加粗**", normalizeMarkdownEmphasis("**加粗**"))
        assertEquals("hello **bold** world", normalizeMarkdownEmphasis("hello **bold** world"))
        assertEquals("`**keep**`", normalizeMarkdownEmphasis("`**keep**`"))
        val segment = markdownSegments("这是**加粗**文字").single() as MarkdownSegment.Text_
        assertEquals("这是 **加粗** 文字", segment.text)
    }

    @Test
    fun gfmParsesBoldAndBoldItalic() {
        assertTrue("standalone bold", gfmHtml("**文本**").contains("<strong>"))
        assertTrue("standalone bold-italic", gfmHtml("***文本***").contains("<strong>"))
        assertTrue("standalone italic", gfmHtml("*斜体*").contains("<em>"))
        assertTrue(
            "cjk-adjacent bold after normalize",
            gfmHtml(normalizeMarkdownEmphasis("这是**加粗**文字")).contains("<strong>"),
        )
        assertTrue(
            "cjk-adjacent bold-italic after normalize",
            gfmHtml(normalizeMarkdownEmphasis("这是***加粗斜体***文字")).contains("<strong>"),
        )
        assertFalse("markers should not survive standalone bold", gfmHtml("**文本**").contains("**文本**"))
        assertFalse("markers should not survive standalone bold-italic", gfmHtml("***文本***").contains("***文本***"))
    }

    @Test
    fun closedInlineFormulaRendersBeforeTheParagraphCloses() {
        val dollar = "$"
        val open = markdownSegments("energy ${dollar}E=mc^2 now").single() as MarkdownSegment.Text_
        assertFalse(open.text.contains("irio-tex:"))
        val closed = markdownSegments("energy ${dollar}E=mc^2${dollar} now").single() as MarkdownSegment.Text_
        assertTrue(closed.text.contains("irio-tex:"))
        assertEquals("b0", closed.key)
        val display = markdownSegments("${dollar}${dollar}\nE=mc^2\n${dollar}${dollar}").single()
        assertTrue(display is MarkdownSegment.TexBlock)
        val unclosedDisplay = markdownSegments("${dollar}${dollar}\nE=mc^2").single()
        assertTrue(unclosedDisplay is MarkdownSegment.Text_)
    }

    @Test
    fun tableSeparatorAlignments() {
        val aligns = parseTableAlignments("|:---|:---:|---:|")
        assertEquals(
            listOf(MarkdownTableAlign.START, MarkdownTableAlign.CENTER, MarkdownTableAlign.END),
            aligns,
        )
        assertEquals(
            listOf(MarkdownTableAlign.START, MarkdownTableAlign.START),
            parseTableAlignments("| --- | --- |"),
        )
    }

    private fun gfmHtml(src: String): String {
        val flavour = GFMFlavourDescriptor()
        val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(src)
        return HtmlGenerator(src, tree, flavour, false).generateHtml()
    }
}
