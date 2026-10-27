package com.irio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.composables.ui.components.Switch
import com.composables.ui.components.Text
import com.composables.ui.theme.colors
import com.composables.ui.theme.onPanelColor
import com.composeunstyled.theme.Theme

@Composable
internal fun ImageUploadSettings() {
    val context = LocalContext.current
    Row(Modifier.settingsSearchAnchor("appearance.imageCompression").settingsSwitchRow(),
        verticalAlignment = Alignment.CenterVertically) {
        Text(tr("图片压缩与转换"), color = Theme[colors][onPanelColor], modifier = Modifier.weight(1f))
        Switch(checked = ThemeStore.imageUploadCompression,
            onCheckedChange = { ThemeStore.saveImageUploadCompression(context, it) },
            modifier = Modifier.semantics { contentDescription = tr("图片压缩与转换") })
    }
    AnimatedVisibility(
        visible = ThemeStore.imageUploadCompression,
        enter = expandVertically(animationSpec = irioSpring(), expandFrom = Alignment.Top) + fadeIn(animationSpec = irioSpring()),
        exit = shrinkVertically(animationSpec = irioSpring(), shrinkTowards = Alignment.Top) + fadeOut(animationSpec = irioSpring()),
    ) {
        Column {
            AppearanceChoiceSetting(
                title = tr("转换格式"), options = ImageUploadFormat.entries,
                selected = ThemeStore.imageUploadFormat, optionLabel = { it.label },
                onSelect = { ThemeStore.saveImageUploadFormat(context, it) },
                modifier = Modifier.settingsSearchAnchor("appearance.imageCompression.format"),
            )
            var pendingQuality by remember(ThemeStore.imageUploadQuality) { mutableIntStateOf(ThemeStore.imageUploadQuality) }
            SettingsSlider(
                label = tr("质量"), value = pendingQuality, valueRange = 40..100,
                valueLabel = { "$it%" },
                onValueChange = { pendingQuality = it },
                onValueChangeFinished = { ThemeStore.saveImageUploadQuality(context, pendingQuality) },
                modifier = Modifier.settingsSearchAnchor("appearance.imageCompression.quality"),
            )
        }
    }
}
