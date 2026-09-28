package com.irio

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.mikepenz.markdown.annotator.DefaultAnnotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.model.markdownAnnotator
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
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
    fun streamingTailWithClosedEmphasisRendersLive() {
        assertTrue(hasClosedEmphasis("**文本**"))
        assertTrue(hasClosedEmphasis("***文本***"))
        assertTrue(hasClosedEmphasis("这是**加粗**文字"))
        assertFalse(hasClosedEmphasis("**文本"))
        assertFalse(hasClosedEmphasis("plain tail"))
        val bold = markdownSegments("**文本**").single() as MarkdownSegment.Text_
        assertTrue(bold.markdown)
        assertEquals("b0", bold.key)
        assertEquals("**文本**", bold.text)
        val boldItalic = markdownSegments("***文本***").single() as MarkdownSegment.Text_
        assertTrue(boldItalic.markdown)
        val cjk = markdownSegments("这是**加粗**文字").single() as MarkdownSegment.Text_
        assertTrue(cjk.markdown)
        assertEquals("这是 **加粗** 文字", cjk.text)
        val unclosed = markdownSegments("**文本").single() as MarkdownSegment.Text_
        assertEquals("b0", unclosed.key)
        assertTrue(unclosed.markdown)
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
    fun gfmParsesBoldAndBoldItalic() {
        val cases = listOf(
            "**文本**",
            "***文本***",
            "**bold**",
            "***bold italic***",
            "这是**加粗**文字",
            "这是***加粗斜体***文字",
            "*斜体*",
        )
        cases.forEach { src ->
            val raw = gfmHtml(src)
            val normalized = gfmHtml(normalizeMarkdownEmphasis(src))
            println("RAW  [$src] -> $raw")
            println("NORM [$src] -> $normalized")
        }
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
    fun annotatorDropsEmphasisMarkers() {
        listOf("**文本**", "***文本***", "*斜体*", "这是**加粗**文字", "这是 ***加粗*** 文字").forEach { src ->
            val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(src)
            println("AST [$src]\n${dumpAst(tree, src)}")
            val annotated = annotate(src)
            println("ANN [$src] text=${annotated.text} spans=${annotated.spanStyles.map { "${it.start}..${it.end}:${it.item.fontWeight}/${it.item.fontStyle}" }}")
            assertFalse("markers in $src -> ${annotated.text}", annotated.text.contains("**") || annotated.text.contains("*斜体*"))
        }
        val bold = annotate("**文本**")
        assertEquals("文本", bold.text.trim())
        assertTrue(bold.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        val boldItalic = annotate("***文本***")
        assertEquals("文本", boldItalic.text.trim())
        assertTrue(boldItalic.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertTrue(boldItalic.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
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

    private fun annotate(src: String) = src.buildMarkdownAnnotatedString(
        style = TextStyle(),
        annotatorSettings = DefaultAnnotatorSettings(
            linkTextSpanStyle = TextLinkStyles(style = SpanStyle()),
            codeSpanStyle = SpanStyle(),
            annotator = markdownAnnotator(),
        ),
    )

    private fun dumpAst(node: ASTNode, src: String, indent: Int = 0): String {
        val slice = src.substring(node.startOffset, node.endOffset.coerceAtMost(src.length))
        val line = " ".repeat(indent) + "${node.type} [${node.startOffset},${node.endOffset}] parent=${node.parent?.type} '$slice'\n"
        return line + node.children.joinToString("") { dumpAst(it, src, indent + 2) }
    }
}
