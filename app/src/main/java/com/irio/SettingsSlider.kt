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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.composables.ui.components.Text
import com.composables.ui.theme.colors
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.onBackgroundColor
import com.composables.ui.theme.primaryColor
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
    modifier: Modifier = Modifier,
    preview: (@Composable (Int, Boolean) -> Unit)? = null,
) {
    require(step > 0)
    val content = Theme[colors][onBackgroundColor]
    val accentFill = Theme[colors][primaryColor]
    val muted = Theme[colors][mutedColor]
    val min = valueRange.first
    val max = valueRange.last
    val current = value.coerceIn(min, max)
    val interactionSource = remember { MutableInteractionSource() }
    var previewTap by remember { mutableIntStateOf(0) }
    var valueBounds by remember { mutableStateOf(Rect.Zero) }
    Column(
        modifier = modifier.settingsSliderInset(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, modifier = Modifier.weight(1f), color = content)
            Text(valueLabel?.invoke(current) ?: "$current", color = muted,
                modifier = Modifier.onGloballyPositioned { valueBounds = it.boundsInWindow() })
        }
        ScrollSafeSlider(
            value = current.toFloat(),
            onValueChange = {
                onValueChange((min + ((it - min) / step).roundToInt() * step).coerceIn(min, max))
            },
            onValueChangeFinished = onValueChangeFinished,
            onTrackTap = if (preview != null) { { previewTap++ } } else null,
            // A transient touch preview must not start focus-driven page scrolling.
            focusOnTap = preview == null,
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
            thumb = { state ->
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(ContinuousCapsule)
                        .background(accentFill),
                ) {
                    if (preview != null) {
                        SliderValuePreview(dragging = state.isDragging, tapSequence = previewTap, valueBounds = valueBounds) { covered ->
                            preview(current, covered)
                        }
                    }
                }
            },
        )
    }
}
