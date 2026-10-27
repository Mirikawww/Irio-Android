package com.irio

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.ui.components.Text
import com.composables.ui.components.Button
import com.composables.ui.components.ButtonStyle
import com.composables.ui.theme.colors
import com.composables.ui.theme.onPanelColor
import com.composeunstyled.LocalContentColor
import com.composeunstyled.theme.Theme

/** Short operation failures are transient; long API diagnostics remain available in full. */
@Composable
internal fun BoxScope.OperationErrorSnackbar(
    visible: Boolean,
    message: String,
    bottomInset: Dp,
    onDismiss: () -> Unit,
    restartKey: Int = 0,
) {
    var retainedMessage by remember { mutableStateOf(message) }
    LaunchedEffect(message) { if (message.isNotBlank()) retainedMessage = message }
    val fullMessage = message.ifBlank { retainedMessage }
    val needsDetails = fullMessage.length > 200 || fullMessage.lines().size > 3
    val summary = if (needsDetails) fullMessage.lineSequence().firstOrNull { it.isNotBlank() }
        .orEmpty().take(160) + "…" else fullMessage
    var details by remember { mutableStateOf<String?>(null) }
    IrioSnackbar(
        visible = visible, message = summary, bottomInset = bottomInset,
        onDismiss = onDismiss, restartKey = restartKey,
        actionLabel = if (needsDetails) tr("查看") else null,
        onAction = if (needsDetails) ({ details = fullMessage }) else null,
    )
    IrioAlertDialog(visible = details != null, onDismissRequest = { details = null }, modifier = Modifier.imePadding()) {
        val maxHeight = with(LocalDensity.current) { (LocalWindowInfo.current.containerSize.height * 0.75f).toDp() }
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            DialogTitle(tr("错误详情"))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                SelectionContainer { Text(details.orEmpty(), color = Theme[colors][onPanelColor]) }
            }
            Button(onClick = { details = null }, style = ButtonStyle.Secondary, modifier = Modifier.fillMaxWidth()) {
                Text(if (ThemeStore.language == AppLanguage.EN) "Close" else "关闭", color = LocalContentColor.current)
            }
        }
    }
}
