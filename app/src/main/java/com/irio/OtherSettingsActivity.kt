package com.irio

import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.ImportExport
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.composables.ui.components.ButtonStyle
import com.composables.ui.components.Icon
import com.composables.ui.components.IconButton
import com.composables.ui.components.Text
import com.composables.ui.theme.backgroundColor
import com.composables.ui.theme.colors
import com.composables.ui.theme.onBackgroundColor
import com.composeunstyled.theme.Theme

class OtherSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setIrioContent { OtherSettingsScreen(onBack = { finish() }) }
    }
}

@Composable
private fun OtherSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var showClearHistoryDialog by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        context.contentResolver.openOutputStream(uri)?.use { BackupStore.export(context, it) }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        context.contentResolver.openInputStream(uri)?.use { BackupStore.import(context, it) }
        (context as? OtherSettingsActivity)?.recreate()
    }
    val background = Theme[colors][backgroundColor]
    val content = Theme[colors][onBackgroundColor]

    Scaffold(
        containerColor = background,
        contentWindowInsets = irioSystemUiInsets(),
        topBar = {
            IrioToolbar(
                title = { Text("其他") },
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
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
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
                onClick = { exportLauncher.launch("irio-${System.currentTimeMillis()}.irio") },
            )
            Spacer(Modifier.height(12.dp))
            ListRowButton(
                label = "导入数据",
                icon = Icons.Rounded.ImportExport,
                onClick = { importLauncher.launch(arrayOf("application/octet-stream", "application/zip", "*/*")) },
            )
        }
    }

    IrioAlertDialog(
        visible = showClearHistoryDialog,
        onDismissRequest = { showClearHistoryDialog = false },
        backgroundColor = Theme[colors][com.composables.ui.theme.panelColor],
        contentColor = Theme[colors][com.composables.ui.theme.onPanelColor],
    ) {
        Column {
            DialogTitle("删除所有历史")
            Spacer(Modifier.height(12.dp))
            Text("确定要删除所有历史聊天记录吗？此操作无法撤销。")
            Spacer(Modifier.height(16.dp))
            DialogButtonRow(
                confirmLabel = "删除",
                onConfirm = {
                    ConversationStore.deleteAll(context)
                    showClearHistoryDialog = false
                },
                onCancel = { showClearHistoryDialog = false },
                confirmStyle = ButtonStyle.Destructive,
            )
        }
    }
}