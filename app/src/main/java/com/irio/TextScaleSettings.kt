package com.irio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

@Composable
internal fun TextScaleSettings() {
    val context = LocalContext.current
    AppearanceChoiceSetting(
        title = tr("文本缩放"),
        options = TextScaleMode.entries,
        selected = ThemeStore.textScaleMode,
        optionLabel = { tr(it.label) },
        onSelect = { ThemeStore.saveTextScaleMode(context, it) },
        modifier = Modifier.settingsSearchAnchor("appearance.textScale"),
    )
    // Match the long-text-paste threshold's expansion/collapse, including interrupted transitions.
    AnimatedVisibility(
        visible = ThemeStore.textScaleMode == TextScaleMode.CUSTOM,
        enter = expandVertically(animationSpec = irioSpring(), expandFrom = Alignment.Top) + fadeIn(animationSpec = irioSpring()),
        exit = shrinkVertically(animationSpec = irioSpring(), shrinkTowards = Alignment.Top) + fadeOut(animationSpec = irioSpring()),
    ) {
        var pendingPercent by remember(ThemeStore.textScalePercent) {
            mutableIntStateOf(ThemeStore.textScalePercent)
        }
        SettingsSlider(
            label = tr("缩放比例"),
            value = pendingPercent,
            valueRange = TextScalePercentRange,
            valueLabel = { "$it%" },
            onValueChange = { pendingPercent = it },
            onValueChangeFinished = { ThemeStore.saveTextScalePercent(context, pendingPercent) },
        )
    }
}
