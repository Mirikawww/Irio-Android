package com.irio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.composables.ui.components.ButtonStyle
import com.composables.ui.components.Text
import com.composables.ui.theme.colors
import com.composables.ui.theme.onPanelColor
import com.composeunstyled.theme.Theme

@Composable
internal fun McpRemovalConfirmationDialog(
    visible: Boolean,
    integrationName: String,
    removing: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    IrioAlertDialog(visible = visible, onDismissRequest = { if (!removing) onDismiss() }) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            DialogTitle(tr("移除集成"))
            Text(tr("确定要移除“%s”集成吗？").format(integrationName), color = Theme[colors][onPanelColor])
            DialogButtonRow(
                confirmLabel = tr(if (removing) "移除中" else "确认移除"),
                confirmStyle = ButtonStyle.Destructive,
                confirmEnabled = !removing,
                cancelEnabled = !removing,
                onConfirm = onConfirm,
                onCancel = onDismiss,
            )
        }
    }
}
