package com.irio

import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test

class ToolStatusColorTest {
    private val muted = Color(0xFF777777)

    @Test fun failedTextUsesTheThemedErrorRed() {
        for (dark in listOf(false, true)) {
            val error = if (dark) darkErrorTextColor else Color(0xFFDC2626)
            assertEquals(error, toolStatusTextColor(ToolOutcome.FAILED, dark, muted, error))
        }
    }

    @Test fun stoppedTextIsYellowAndReadableOnLightDarkAndAmoledBackgrounds() {
        for ((dark, background) in listOf(false to Color.White, true to Color(0xFF141414), true to Color.Black)) {
            val color = toolStatusTextColor(ToolOutcome.STOPPED, dark, muted, darkErrorTextColor)
            assertNotEquals(muted, color)
            assertTrue(color.red > color.blue && color.green > color.blue)
            assertTrue("Stopped text needs readable yellow contrast", wcagContrastRatio(color, background) >= 4.5f)
            assertEquals(1f, color.alpha)
        }
    }

    @Test fun successfulAndLegacyTextKeepTheirNeutralColor() {
        for (outcome in listOf(ToolOutcome.SUCCESS, null)) {
            for (dark in listOf(false, true)) {
                assertEquals(muted, toolStatusTextColor(outcome, dark, muted, darkErrorTextColor))
            }
        }
    }
}
