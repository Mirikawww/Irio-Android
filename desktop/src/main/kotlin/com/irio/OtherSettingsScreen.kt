package com.irio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.ImportExport
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composables.ui.components.ButtonStyle
import com.composables.ui.components.Icon
import com.composables.ui.components.IconButton
import com.composables.ui.components.Text
import com.composables.ui.theme.backgroundColor
import com.composables.ui.theme.colors
import com.composables.ui.theme.onBackgroundColor
import com.composables.ui.theme.onPanelColor
import com.composables.ui.theme.panelColor
import com.composeunstyled.theme.Theme

@Composable
fun OtherSettingsScreen(onBack: () -> Unit) {
    var showClearHistoryDialog by remember { mutableStateOf(false) }
    val background = Theme[colors][backgroundColor]
    val content = Theme[colors][onBackgroundColor]

    SettingsPageScaffold(
        title = "其他",
        onBack = onBack,
        background = background,
        content = content,
    ) {
        ListRowButton(
            label = "删除所有历史",
            icon = Icons.Rounded.DeleteSweep,
            onClick = { showClearHistoryDialog = true },
        )
        Spacer(Modifier.height(12.dp))
        ListRowButton(
            label = "导出数据",
            icon = Icons.Rounded.ImportExport,
            onClick = {
                FilePickers.saveBackup("irio-${System.currentTimeMillis()}.irio")?.let { file ->
                    file.outputStream().use { BackupStore.export(it) }
                }
            },
        )
        Spacer(Modifier.height(12.dp))
        ListRowButton(
            label = "导入数据",
            icon = Icons.Rounded.ImportExport,
            onClick = {
                FilePickers.openBackup()?.let { file ->
                    file.inputStream().use { BackupStore.import(it) }
                }
            },
        )
    }

    IrioAlertDialog(
        visible = showClearHistoryDialog,
        onDismissRequest = { showClearHistoryDialog = false },
        backgroundColor = Theme[colors][panelColor],
        contentColor = Theme[colors][onPanelColor],
    ) {
        Column {
            DialogTitle("删除所有历史")
            Spacer(Modifier.height(12.dp))
            Text("确定要删除所有历史聊天记录吗？此操作无法撤销。")
            Spacer(Modifier.height(16.dp))
            DialogButtonRow(
                confirmLabel = "删除",
                onConfirm = {
                    ConversationStore.deleteAll()
                    showClearHistoryDialog = false
                },
                onCancel = { showClearHistoryDialog = false },
                confirmStyle = ButtonStyle.Destructive,
            )
        }
    }
}

@Composable
private fun SettingsPageScaffold(
    title: String,
    onBack: () -> Unit,
    background: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
    body: @Composable () -> Unit,
) {
    androidx.compose.material3.Scaffold(
        containerColor = background,
        topBar = {
            IrioToolbar(
                title = { Text(title) },
                backgroundColor = background,
                contentColor = content,
                leading = {
                    IconButton(onClick = onBack, style = ButtonStyle.Ghost) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回",
                            tint = content,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) { body() }
    }
}