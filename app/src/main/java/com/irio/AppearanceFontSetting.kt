package com.irio

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.ui.components.Text
import com.composables.ui.theme.borderColor
import com.composables.ui.theme.colors
import com.composables.ui.theme.onPanelColor
import com.composables.ui.theme.panelColor
import com.composables.ui.theme.primaryColor
import com.composables.ui.theme.largeShape
import com.composables.ui.theme.shapes
import com.composeunstyled.theme.Theme
import kotlin.math.roundToInt

@Composable
internal fun AppearanceFontSetting() {
    val context = LocalContext.current
    val anchor = Modifier.settingsSearchAnchor("appearance.font", edge = SettingsSearchEdge.Top)
    if (!ThemeStore.betterSettingsDisplay) {
        AppearanceChoiceSetting(
            title = tr("字体"),
            options = AppFont.entries,
            selected = ThemeStore.appFont,
            optionLabel = { tr(it.label) },
            onSelect = { ThemeStore.saveAppFont(context, it) },
            modifier = anchor,
        )
        return
    }
    val systemWeight = rememberSystemFontWeightScale(enabled = true)
    CompositionLocalProvider(LocalSystemFontWeightScale provides systemWeight) {
        val serif = rememberAppFontFamily(AppFont.SERIF) ?: LoraRegularFamily
        val shape = Theme[shapes][largeShape]
        val accent = Theme[colors][primaryColor]
        val foreground = Theme[colors][onPanelColor]
        val panel = Theme[colors][panelColor]
        val cardHeight = 84.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
        Column(anchor.settingsSwitchRow(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("字体"), color = foreground)
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppFont.entries.forEach { font ->
                    val selected = ThemeStore.appFont == font
                    val previewBackground = if (selected) accent.copy(alpha = .08f).compositeOver(panel) else panel
                    val nameBackground = (if (selected) accent.copy(alpha = .18f) else foreground.copy(alpha = .08f))
                        .compositeOver(panel)
                    Column(
                        modifier = Modifier.weight(1f)
                            .clip(shape)
                            .border(1.dp, if (selected) accent else Theme[colors][borderColor], shape)
                            .selectable(selected = selected, role = Role.RadioButton,
                                onClick = { ThemeStore.saveAppFont(context, font) })
                            .height(cardHeight),
                    ) {
                        Box(Modifier.fillMaxWidth().weight(2f).background(previewBackground)
                            .testTag("font-${font.stored}-preview"), contentAlignment = Alignment.Center) {
                            FontPreviewSample(if (font == AppFont.SANS) FontFamily.Default else serif, foreground)
                        }
                        Box(Modifier.fillMaxWidth().weight(1f).background(nameBackground)
                            .testTag("font-${font.stored}-name"), contentAlignment = Alignment.Center) {
                            Text(tr(font.label), modifier = Modifier.padding(horizontal = 8.dp),
                                color = foreground, fontSize = 12.sp, lineHeight = 16.sp)
                        }
                    }
                }
            }
        }
    }
}

/** Center the visible sample glyphs, not each font's different ascent/descent box. */
@Composable
private fun FontPreviewSample(family: FontFamily, color: Color) {
    val resolver = LocalFontFamilyResolver.current
    val typeface = resolver.resolve(family, FontWeight.Normal).value as Typeface
    val sizePx = with(LocalDensity.current) { 28.sp.toPx() }
    val inkCenter = remember(typeface, sizePx) {
        val bounds = Rect()
        Paint().apply {
            this.typeface = typeface
            textSize = sizePx
        }.getTextBounds("Aa", 0, 2, bounds)
        (bounds.top + bounds.bottom) / 2f
    }
    Layout(content = {
        Text("Aa", color = color, fontSize = 28.sp, lineHeight = 36.sp,
            fontFamily = family, fontWeight = FontWeight.Normal)
    }) { measurables, constraints ->
        val sample = measurables.single().measure(constraints)
        val offset = (sample.height / 2f - sample[FirstBaseline] - inkCenter).roundToInt()
        layout(sample.width, sample.height) {
            sample.placeRelative(0, offset)
        }
    }
}
