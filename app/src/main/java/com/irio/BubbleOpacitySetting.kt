package com.irio

import androidx.compose.foundation.background

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset

import androidx.compose.foundation.layout.padding

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.ui.components.Text
import com.composables.ui.theme.backgroundColor
import com.composables.ui.theme.colors
import com.composables.ui.theme.largeShape
import com.composables.ui.theme.mutedColor

import com.composables.ui.theme.onPanelColor
import com.composables.ui.theme.onPrimaryColor
import com.composables.ui.theme.primaryColor
import com.composables.ui.theme.shapes
import com.composeunstyled.theme.Theme
import com.kyant.shapes.RoundedRectangle

@Composable
internal fun BubbleOpacitySetting() {
    val context = androidx.compose.ui.platform.LocalContext.current
    SettingsSlider(
        modifier = Modifier.settingsSearchAnchor("appearance.bubbleOpacity"),
        label = tr("气泡透明度"),
        value = ThemeStore.bubbleOpacity,
        valueRange = 0..100,
        valueLabel = { "$it%" },
        onValueChange = { ThemeStore.saveBubbleOpacity(context, it) },
        preview = if (ThemeStore.betterSettingsDisplay) { value, covered -> BubbleOpacityPreview(value, covered) } else null,
    )
}

@Composable
internal fun BubbleOpacityPreview(value: Int, showPercentage: Boolean = false) {
    val background = Theme[colors][backgroundColor]
    val bubble = userBubbleColors(background, Theme[colors][primaryColor], Theme[colors][onPanelColor],
        Theme[colors][onPrimaryColor], editing = false, opacityPercent = value)
    Box(Modifier.background(background, Theme[shapes][largeShape])
        .padding(12.dp).testTag("bubble-opacity-preview")) {
            Text(tr("这是一条预览消息"), color = bubble.content, fontSize = 16.sp, lineHeight = 22.sp,
                fontFamily = rememberAppFontFamily(),
                modifier = Modifier.background(bubble.background, RoundedRectangle(16.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .testTag("bubble-opacity-preview-message").semantics { stateDescription = "$value%" })
            if (showPercentage) {
                // Overlay the corner padding: never resize the native Popup while
                // its position provider is reporting a change in value coverage.
                Box(Modifier.matchParentSize().offset(y = (-10).dp), contentAlignment = Alignment.TopEnd) {
                    Text("$value%", color = Theme[colors][mutedColor], fontSize = 12.sp, lineHeight = 16.sp,
                        modifier = Modifier.background(background, ContinuousCapsule).padding(horizontal = 4.dp))
                }
            }
    }
}
