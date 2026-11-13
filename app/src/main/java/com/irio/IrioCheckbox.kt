package com.irio

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.composables.ui.components.Checkbox as ComposablesCheckbox

/** Keep the compact labeled checkbox; only user changes produce feedback. */
@Composable
internal fun IrioCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accessibilityLabel: String? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: (@Composable RowScope.() -> Unit)? = null,
) {
    val context = LocalContext.current
    ComposablesCheckbox(
        checked = checked,
        onCheckedChange = { next ->
            if (enabled && next != checked) {
                val event = if (next) HapticEvent.CHECKBOX_ON else HapticEvent.CHECKBOX_OFF
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
