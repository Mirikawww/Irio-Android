package com.irio

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.composables.ui.components.AlertDialog
import com.composables.ui.theme.colors
import com.composables.ui.theme.onPanelColor
import com.composables.ui.theme.panelColor
import com.composeunstyled.theme.Theme

/** AlertDialog with Escape/Back keyboard dismissal on desktop. */
@Composable
fun IrioAlertDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    backgroundColor: Color = Theme[colors][panelColor],
    contentColor: Color = Theme[colors][onPanelColor],
    content: @Composable () -> Unit,
) {
    val dismissModifier = modifier.onPreviewKeyEvent { event ->
        if (
            visible &&
            event.type == KeyEventType.KeyDown &&
            (event.key == Key.Escape || event.key == Key.Back)
        ) {
            onDismissRequest()
            true
        } else {
            false
        }
    }
    AlertDialog(
        visible = visible,
        onDismissRequest = onDismissRequest,
        modifier = dismissModifier,
        backgroundColor = backgroundColor,
        contentColor = contentColor,
        content = content,
    )
}
