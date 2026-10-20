package com.irio

import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Test

class AppFontTest {
    @Test
    fun systemWeightAdjustsTheRequestedWeight() {
        assertEquals(400, resolvedVariableWeight(FontWeight.Normal, 0))
        assertEquals(700, resolvedVariableWeight(FontWeight.Normal, 300))
        assertEquals(800, resolvedVariableWeight(FontWeight.Medium, 300))
        assertEquals(1000, resolvedVariableWeight(FontWeight.Black, 300))
    }

    @Test
    fun missingSystemWeightFallsBackToRegular() {
        assertEquals(400, resolvedVariableWeight(FontWeight.Medium, null))
        assertEquals(400, resolvedVariableWeight(FontWeight.Normal, null))
        assertEquals(400, resolvedVariableWeight(FontWeight.Normal, 0))
    }

    @Test
    fun fontChoiceDefaultsToSans() {
        assertEquals(AppFont.SANS, AppFont.fromStored(null))
        assertEquals(AppFont.SANS, AppFont.fromStored("nope"))
        assertEquals(AppFont.SERIF, AppFont.fromStored("serif"))
        assertEquals("Sans", translate(AppLanguage.EN, "非衬线体"))
        assertEquals("Serif", translate(AppLanguage.EN, "衬线体"))
    }
}
