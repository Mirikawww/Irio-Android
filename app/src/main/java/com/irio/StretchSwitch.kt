/*
 * Copyright (c) 2026 Composable Horizons
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.irio

// Adapted from Composables UI Switch 0.2.0 (MIT).
// Preserve upstream styling and interaction; widen the moving thumb slightly.

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.ui.components.focusRing
import com.composables.ui.theme.alphas
import com.composables.ui.theme.colors
import com.composables.ui.theme.disabledAlpha
import com.composables.ui.theme.onPanelColor
import com.composables.ui.theme.ringColor
import com.composables.ui.theme.switchSelectedTrackColor
import com.composables.ui.theme.switchThumbColor
import com.composables.ui.theme.switchTrackColor
import com.composeunstyled.FocusRingVisibility
import com.composeunstyled.ProvideContentColor
import com.composeunstyled.Thumb
import com.composeunstyled.Track
import com.composeunstyled.UnstyledSwitch
import com.composeunstyled.theme.Theme
import kotlin.math.abs

@Composable
internal fun StretchSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    accessibilityLabel: String?,
    interactionSource: MutableInteractionSource,
    content: (@Composable RowScope.() -> Unit)?,
) {
    val trackShape = RoundedCornerShape(999.dp)
    val trackColor by animateColorAsState(
        if (checked) Theme[colors][switchSelectedTrackColor] else Theme[colors][switchTrackColor],
    )
    // Match Thumb's spring and phase, including interruptions and reverse toggles.
    // Initial composition is already at its endpoint, so mounting never stretches.
    val position by animateDpAsState(if (checked) 1.dp else 0.dp, spring())
    val stretch = (1f - abs(position.value * 2f - 1f)).coerceIn(0f, 1f)

    UnstyledSwitch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        interactionSource = interactionSource,
        accessibilityLabel = accessibilityLabel,
        modifier = modifier.alpha(if (enabled) 1f else Theme[alphas][disabledAlpha]),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Track(
                modifier = Modifier
                    .focusRing(interactionSource = interactionSource, color = Theme[colors][ringColor],
                        shape = trackShape, visibility = FocusRingVisibility.Focused)
                    .background(trackColor, trackShape)
                    .border(Dp.Hairline, Theme[colors][ringColor], trackShape)
                    .size(width = 44.dp, height = 24.dp)
                    .padding(2.dp),
            ) {
                Thumb(animationSpec = spring()) {
                    Box(
                        Modifier.size(width = 20.dp + (5f * stretch).dp, height = 20.dp)
                            .background(Theme[colors][switchThumbColor], CircleShape)
                            .border(Dp.Hairline, Theme[colors][ringColor], CircleShape),
                    )
                }
            }
            if (content != null) {
                ProvideContentColor(Theme[colors][onPanelColor]) { content() }
            }
        }
    }
}
