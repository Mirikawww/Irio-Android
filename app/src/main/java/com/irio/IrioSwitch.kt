package com.irio

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.composables.ui.components.Switch as ComposablesSwitch

/** Preserve the existing switch while acknowledging actual user changes in either direction. */
@Composable
internal fun IrioSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accessibilityLabel: String? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: (@Composable RowScope.() -> Unit)? = null,
) {
    val context = LocalContext.current
    ComposablesSwitch(
        checked = checked,
        onCheckedChange = { next ->
            if (enabled && next != checked) {
                // A master-off action gets its last pulse before persistence disables feedback.
                // A master-on action can acknowledge the new opt-in after persistence instead.
                val event = if (next) HapticEvent.SWITCH_ON else HapticEvent.SWITCH_OFF
                val feedbackBefore = HapticPreferences.isEnabled(context, event)
                if (feedbackBefore) AppHaptics.perform(context, event)
                onCheckedChange(next)
                if (!feedbackBefore) AppHaptics.perform(context, event)
            }
        },
        modifier = modifier,
        enabled = enabled,
        accessibilityLabel = accessibilityLabel,
        interactionSource = interactionSource,
        content = content,
    )
}
