package com.irio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
internal fun ProviderCustomModelDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onAdd: (String) -> String?,
) {
    val modelId = rememberTextFieldState()
    var error by remember { mutableStateOf<String?>(null) }
    var errorNonce by remember { mutableIntStateOf(0) }
    var buttonsHeight by remember { mutableIntStateOf(0) }
    LaunchedEffect(visible) {
        if (visible) {
            modelId.edit { replace(0, length, "") }
            error = null
        }
    }
    IrioAlertDialog(visible = visible, onDismissRequest = onDismiss, modifier = Modifier.imePadding()) {
        Box {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                DialogTitle(tr("添加模型"))
                ProviderField(tr("模型 ID"), modelId, KeyboardType.Text, trailingSpace = false, multiline = true)
                Box(Modifier.onSizeChanged { buttonsHeight = it.height }) {
                    DialogButtonRow(
                        confirmLabel = tr("添加"),
                        onConfirm = {
                            error = onAdd(modelId.text.toString())
                            if (error == null) onDismiss() else errorNonce++
                        },
                        onCancel = onDismiss,
                    )
                }
            }
            OperationErrorSnackbar(
                visible = error != null, message = error?.let(::tr).orEmpty(),
                bottomInset = with(LocalDensity.current) { buttonsHeight.toDp() },
                onDismiss = { error = null }, restartKey = errorNonce,
            )
        }
    }
}
