package com.irio

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.text.TextRunShaper
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppFontGlyphFallbackTest {
    @Test fun composeResolvedSerifUsesBundledNotoForChineseAndLoraForLatin() {
        val context = RuntimeEnvironment.getApplication() as Context
        val resolver = createFontFamilyResolver(context)
        val noto = Font.Builder(context.resources, R.font.noto_serif_cjk_sc_variable).build()
        for (family in listOf(LoraFontFamily, LoraRegularFamily)) {
            for (weight in listOf(100, 400, 700, 900)) for (style in listOf(FontStyle.Normal, FontStyle.Italic)) {
                val typeface = resolver.resolve(family, FontWeight(weight), style).value as Typeface
                val expectedWeight = if (family === LoraRegularFamily) 400 else weight
                for (text in listOf("中", "漢", "あ", "한")) {
                    val cjk = shapedFont(typeface, text)
                    assertTrue("CJK must use bundled Noto Serif CJK, not the system fallback ($text/$weight/$style)",
                        noto.buffer == cjk.buffer)
                    assertEquals("CJK must keep its own 100..900 variable axis", expectedWeight.toFloat(),
                        requireNotNull(cjk.axes).single { it.tag == "wght" }.styleValue, 0.01f)
                }
                val loraResource = if (style == FontStyle.Italic) R.font.lora_italic_variable else R.font.lora_variable
                val lora = Font.Builder(context.resources, loraResource).build()
                assertTrue("Latin must keep Lora ($weight/$style)", lora.buffer == shapedFont(typeface, "A").buffer)
            }
        }
    }

    private fun shapedFont(typeface: Typeface, text: String): Font {
        val paint = Paint().apply { this.typeface = typeface; textSize = 32f }
        val glyphs = TextRunShaper.shapeTextRun(text, 0, text.length, 0, text.length, 0f, 0f, false, paint)
        assertEquals(1, glyphs.glyphCount())
        assertTrue("The glyph must exist", glyphs.getGlyphId(0) != 0)
        return glyphs.getFont(0)
    }
}
