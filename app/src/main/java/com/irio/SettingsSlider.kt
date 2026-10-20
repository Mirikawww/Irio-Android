package com.irio

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.composables.ui.components.Text
import com.composables.ui.theme.colors
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.onBackgroundColor
import com.composables.ui.theme.primaryColor
import com.composeunstyled.UnstyledSlider
import com.composeunstyled.theme.Theme
import kotlin.math.roundToInt

@Composable
fun SettingsSlider(
    label: String,
    value: Int,
    valueRange: IntRange,
    onValueChange: (Int) -> Unit,
    valueLabel: ((Int) -> String)? = null,
    onValueChangeFinished: (() -> Unit)? = null,
    step: Int = 1,
) {
    require(step > 0)
    val content = Theme[colors][onBackgroundColor]
    val accentFill = Theme[colors][primaryColor]
    val muted = Theme[colors][mutedColor]
    val min = valueRange.first
    val max = valueRange.last
    val current = value.coerceIn(min, max)
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier.settingsSliderInset(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, modifier = Modifier.weight(1f), color = content)
            Text(valueLabel?.invoke(current) ?: "$current", color = muted)
        }
        UnstyledSlider(
            value = current.toFloat(),
            onValueChange = {
                onValueChange((min + ((it - min) / step).roundToInt() * step).coerceIn(min, max))
            },
            onValueChangeFinished = onValueChangeFinished,
            valueRange = min.toFloat()..max.toFloat(),
            steps = ((max - min) / step - 1).coerceAtLeast(0),
            interactionSource = interactionSource,
            modifier = Modifier.fillMaxWidth(),
            track = { state ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(ContinuousCapsule)
                            .background(muted.copy(alpha = 0.28f)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(state.fraction)
                                .background(accentFill),
                        )
                    }
                }
            },
            thumb = {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(ContinuousCapsule)
                        .background(accentFill),
                )
            },
        )
    }
}
