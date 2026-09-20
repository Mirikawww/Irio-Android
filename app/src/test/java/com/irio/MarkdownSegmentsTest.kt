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
        val first = markdownSegments("Hello world\n\nMore", streaming = true)
        assertEquals(2, first.size)
        assertEquals("b0", first[0].key)
        assertEquals("pending", first[1].key)
        val frozen = (first[0] as MarkdownSegment.Text_).text

        val second = markdownSegments("Hello world\n\nMore text arriving", streaming = true)
        assertEquals("b0", second[0].key)
        assertEquals(frozen, (second[0] as MarkdownSegment.Text_).text)
        assertEquals("pending", second[1].key)
    }

    @Test
    fun completedTailUpgradesOnceStreamingEnds() {
        val streaming = markdownSegments("Hello world", streaming = true)
        assertEquals("pending", streaming.single().key)
        assertEquals(false, (streaming.single() as MarkdownSegment.Text_).markdown)

        val done = markdownSegments("Hello world", streaming = false)
        assertEquals("b0", done.single().key)
        assertTrue((done.single() as MarkdownSegment.Text_).markdown)
    }

    @Test
    fun cjkEmphasisGetsFlankingSpaces() {
        assertEquals("这是 **加粗** 文字", normalizeMarkdownEmphasis("这是**加粗**文字"))
        assertEquals("**加粗**", normalizeMarkdownEmphasis("**加粗**"))
        assertEquals("hello **bold** world", normalizeMarkdownEmphasis("hello **bold** world"))
        assertEquals("`**keep**`", normalizeMarkdownEmphasis("`**keep**`"))
        val segment = markdownSegments("这是**加粗**文字", streaming = false).single() as MarkdownSegment.Text_
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
