package com.irio

import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.sp

import androidx.compose.material3.Scaffold

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.ui.components.ButtonStyle
import com.composables.ui.components.Icon
import com.composables.ui.components.IconButton
import com.composables.ui.components.DropdownMenu
import com.composables.ui.components.DropdownMenuAlignment
import com.composables.ui.components.DropdownMenuItem
import com.irio.ScaledDropdownMenuPanel as DropdownMenuPanel
import com.composables.ui.components.ProgressIndicator
import com.composables.ui.components.Text
import com.composables.ui.theme.backgroundColor
import com.composables.ui.theme.colors
import com.composables.ui.theme.onBackgroundColor
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.panelColor
import com.composables.ui.theme.onPanelColor
import com.composeunstyled.theme.Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class OtherSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setIrioContent { OtherSettingsScreen(onBack = { finish() }) }
    }
}

@Composable
private fun OtherSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showClearHistoryDialog by remember { mutableStateOf(false) }
    var importDialogOpen by remember { mutableStateOf(false) }
    var importOverwrite by remember { mutableStateOf(true) }
    var pendingImportUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var importing by remember { mutableStateOf(false) }
    var operationError by remember { mutableStateOf<String?>(null) }
    var operationErrorNonce by remember { mutableStateOf(0) }
    var importModeMenuOpen by remember { mutableStateOf(false) }
    var importCompleted by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            val output = context.contentResolver.openOutputStream(uri) ?: error("Output stream unavailable")
            output.use { BackupStore.export(context, it) }
        }.onFailure {
            operationError = tr("导出失败")
            operationErrorNonce++
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        if (!BackupFileValidation.isIrioFile(uri.lastPathSegment.orEmpty())) {
            operationError = tr("只能导入 .irio 格式的备份文件")
            operationErrorNonce++
        } else {
            pendingImportUri = uri
            importDialogOpen = true
        }
    }
    fun startImport() {
        val uri = pendingImportUri ?: return
        importDialogOpen = false
        importing = true
        operationError = null
        val overwrite = importOverwrite
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                context.contentResolver.openInputStream(uri)?.use { BackupStore.import(context, it, overwrite = overwrite) }
                    ?: BackupImportResult(false, tr("无法读取备份文件"))
            }.getOrElse { BackupImportResult(false, it.message ?: tr("恢复失败")) }
            (context as? OtherSettingsActivity)?.runOnUiThread {
                importing = false
                if (result.success) {
                    operationError = null
                    pendingImportUri = null
                } else {
                    operationError = result.message.ifBlank { tr("恢复失败") }
                    operationErrorNonce++
                }
            }
        }
    }
    SettingsHazeScaffold(
        title = tr("其他"),
        onBack = onBack,
    ) { topInset, bottomInset ->
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = bottomInset)
                    .verticalScroll(rememberScrollState())
                    .padding(top = topInset)
                    .padding(16.dp),
            ) {
                ListRowButton(
                    searchId = "other.history",
                    label = tr("删除所有历史"),
                    icon = IrioIcons.Trash,
                    onClick = { showClearHistoryDialog = true },
                )
                Spacer(Modifier.height(12.dp))
                ListRowButton(
                    searchId = "other.export",
                    label = tr("导出数据"),
                    icon = IrioIcons.Upload,
                    experimental = true,
                    onClick = { exportLauncher.launch("irio-${System.currentTimeMillis()}.irio") },
                )
                Spacer(Modifier.height(12.dp))
                ListRowButton(
                    searchId = "other.import",
                    label = tr("导入数据"),
                    icon = IrioIcons.Download,
                    experimental = true,
                    onClick = {
                        operationError = null
                        importLauncher.launch(arrayOf("application/octet-stream", "application/zip", "*/*"))
                    },
                )
            }
            IrioSnackbar(
                visible = operationError != null,
                message = operationError.orEmpty(),
                bottomInset = bottomInset,
                onDismiss = { operationError = null },
                restartKey = operationErrorNonce,
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
            DialogTitle(tr("删除所有历史"))
            Spacer(Modifier.height(12.dp))
            Text(tr("确定要删除所有历史聊天记录吗？此操作无法撤销。"))
            Spacer(Modifier.height(16.dp))
            DialogButtonRow(
                confirmLabel = tr("删除"),
                onConfirm = {
                    ConversationStore.deleteAll(context)
                    showClearHistoryDialog = false
                },
                onCancel = { showClearHistoryDialog = false },
                confirmStyle = ButtonStyle.Destructive,
            )
        }
    }

    IrioAlertDialog(
        visible = importDialogOpen,
        onDismissRequest = { importDialogOpen = false; pendingImportUri = null },
        backgroundColor = Theme[colors][com.composables.ui.theme.panelColor],
        contentColor = Theme[colors][com.composables.ui.theme.onPanelColor],
    ) {
        Column {
            DialogTitle(tr("恢复数据"))
            Spacer(Modifier.height(12.dp))
            DropdownMenu(
                expanded = importModeMenuOpen,
                onExpandedChange = { importModeMenuOpen = it },
                modifier = Modifier.fillMaxWidth(),
                alignment = DropdownMenuAlignment.End,
                panel = {
                    DropdownMenuPanel {
                        DropdownMenuItem(onClick = { importOverwrite = false; importModeMenuOpen = false }, modifier = Modifier.fillMaxWidth()) { Text(tr("增量恢复")) }
                        DropdownMenuItem(onClick = { importOverwrite = true; importModeMenuOpen = false }, modifier = Modifier.fillMaxWidth()) { Text(tr("覆盖恢复")) }
                    }
                },
            ) {
                val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(interactionSource = interactionSource, indication = null) { importModeMenuOpen = !importModeMenuOpen },
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("恢复方式"), color = Theme[colors][com.composables.ui.theme.onPanelColor])
                        Text(if (importOverwrite) tr("覆盖恢复") else tr("增量恢复"), color = Theme[colors][com.composables.ui.theme.mutedColor], fontSize = 13.sp)
                    }
                    IconButton(
                        onClick = { importModeMenuOpen = !importModeMenuOpen },
                        style = ButtonStyle.Ghost,
                        buttonSize = com.composables.ui.components.ButtonSize.Small,
                        shape = ContinuousCapsule,
                        modifier = Modifier.size(32.dp),
                        interactionSource = interactionSource,
                    ) {
                        Icon(
                            imageVector = IrioIcons.ChevronDown,
                            contentDescription = tr("选择恢复方式"),
                            tint = Theme[colors][com.composables.ui.theme.onPanelColor],
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            DialogButtonRow(
                confirmLabel = tr("确认恢复"),
                onConfirm = ::startImport,
                onCancel = { importDialogOpen = false; pendingImportUri = null },
            )
        }
    }

    if (importing) {
        IrioAlertDialog(
            visible = true,
            onDismissRequest = {},
            backgroundColor = Theme[colors][com.composables.ui.theme.panelColor],
            contentColor = Theme[colors][com.composables.ui.theme.onPanelColor],
        ) {
            Column {
                DialogTitle(tr("正在恢复数据"))
                Spacer(Modifier.height(16.dp))
                ProgressIndicator(progress = 0.5f, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
