package com.irio

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.ui.components.Text
import com.composables.ui.theme.colors
import com.composables.ui.theme.onBackgroundColor
import com.composables.ui.theme.onPanelColor
import com.composables.ui.theme.onPrimaryColor
import com.composables.ui.theme.panelColor
import com.composables.ui.theme.primaryColor
import com.composeunstyled.theme.Theme

/** The font selector's capsule, shared with text scaling without introducing a new control. */
@Composable
internal fun <T> AppearanceChoiceSetting(
    title: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(
        modifier = modifier.settingsSwitchRow(),
        content = {
            Text(title, color = Theme[colors][onPanelColor])
            AppearanceChoiceTabs(title, options, selected, optionLabel, onSelect)
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val titleLabel = measurables[0].measure(loose)
        val tabs = measurables[1].measure(loose)
        val width = constraints.maxWidth
        val stacked = titleLabel.width + 12.dp.roundToPx() + tabs.width > width
        val gap = if (stacked) 8.dp.roundToPx() else 0
        val height = if (stacked) titleLabel.height + gap + tabs.height else maxOf(titleLabel.height, tabs.height)
        layout(width, height) {
            titleLabel.placeRelative(0, if (stacked) 0 else (height - titleLabel.height) / 2)
            tabs.placeRelative(width - tabs.width, if (stacked) titleLabel.height + gap else (height - tabs.height) / 2)
        }
    }
}

@Composable
private fun <T> AppearanceChoiceTabs(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    val progress by animateFloatAsState(
        targetValue = options.indexOf(selected).toFloat(),
        animationSpec = irioSpring<Float>(stiffness = 520f),
        label = "appearanceChoiceTab",
    )
    val selectedFill = Theme[colors][primaryColor]
    val selectedInk = Theme[colors][onPrimaryColor]
    val idleInk = Theme[colors][onBackgroundColor]
    Box(
        modifier = Modifier
            .semantics { contentDescription = label }
            .selectableGroup()
            .clip(ContinuousCapsule)
            .background(Theme[colors][panelColor], ContinuousCapsule)
            .padding(4.dp),
    ) {
        Layout(
            content = {
                Box(Modifier.fillMaxSize().clip(ContinuousCapsule).background(selectedFill))
                options.forEach { option ->
                    val ink by animateColorAsState(
                        if (option == selected) selectedInk else idleInk,
                        irioSpring(),
                        label = "appearanceChoiceTabInk",
                    )
                    Box(
                        modifier = Modifier
                            .heightIn(min = 26.dp)
                            .clip(ContinuousCapsule)
                            .selectable(
                                selected = option == selected,
                                role = Role.Tab,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onSelect(option) },
                            )
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(optionLabel(option), color = ink, fontSize = 12.sp, lineHeight = 16.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            },
        ) { measurables, constraints ->
            val count = options.size
            val loose = constraints.copy(minWidth = 0, minHeight = 0,
                maxWidth = constraints.maxWidth / count)
            val labels = Array(count) { index -> measurables[index + 1].measure(loose) }
            val segment = labels.maxOf { it.width }
            val height = labels.maxOf { it.height }
            val indicator = measurables[0].measure(Constraints.fixed(segment, height))
            layout(segment * count, height) {
                indicator.placeRelative((progress * segment).toInt(), 0)
                labels.forEachIndexed { index, label ->
                    label.placeRelative(index * segment + (segment - label.width) / 2, (height - label.height) / 2)
                }
            }
        }
    }
}
