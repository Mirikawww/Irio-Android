package com.irio

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.composables.ui.components.Button
import com.composables.ui.components.ButtonStyle
import com.composables.ui.components.Icon
import com.composables.ui.components.IconButton
import com.composables.ui.components.ProgressIndicator
import com.composables.ui.components.Switch
import com.composables.ui.components.Text
import com.composables.ui.theme.backgroundColor
import com.composables.ui.theme.colors
import com.composables.ui.theme.largeShape
import com.composables.ui.theme.onBackgroundColor
import com.composables.ui.theme.onPanelColor
import com.composables.ui.theme.panelColor
import com.composables.ui.theme.secondaryColor
import com.composables.ui.theme.shapes
import com.composeunstyled.theme.Theme
import com.composeunstyled.LocalContentColor
import kotlinx.coroutines.delay

class LinuxSandboxSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setIrioContent { LinuxSandboxSettingsScreen(onBack = { finish() }) }
    }
}

@Composable
private fun LinuxSandboxSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val layout = remember { LinuxSandboxLayout.of(context.filesDir) }
    var enabled by remember { mutableStateOf(LinuxSandboxStore.loadEnabled(layout.stateFile)) }
    var installed by remember {
        mutableStateOf(layout.rootfs.isDirectory && layout.installedVersion() != null)
    }
    var allFiles by remember { mutableStateOf(LinuxSandboxAccess.hasAllFiles()) }
    var awaitingPermission by remember { mutableStateOf(false) }
    var confirmRebuild by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var operationError by remember { mutableStateOf<String?>(null) }
    var errorNonce by remember { mutableIntStateOf(0) }
    var showPrepare by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var offerRebuild by remember { mutableStateOf(false) }
    var commandsPerCall by remember {
        mutableIntStateOf(LinuxSandboxStore.loadCommandsPerCall(layout.stateFile))
    }
    var backgroundCommands by remember {
        mutableStateOf(LinuxSandboxStore.loadBackgroundEnabled(layout.stateFile))
    }
    val background = Theme[colors][backgroundColor]
    val content = Theme[colors][onBackgroundColor]
    val lifecycleOwner = LocalLifecycleOwner.current

    fun refresh() {
        val bundled = runCatching { loadLinuxSandboxCatalog(context).alpineVersion }.getOrNull()
        val installedVersion = layout.installedVersion()
        installed = layout.rootfs.isDirectory && installedVersion != null
        offerRebuild = linuxSandboxOfferRebuild(installedVersion, bundled, layout.shellPresent())
    }

    fun beginPrepare(rebuild: Boolean) {
        val extract = linuxSandboxShouldExtract(layout.installedVersion(), layout.rootfs.isDirectory, rebuild)
        if (!extract) {
            LinuxSandboxSession.kick(context, rebuild)
            refresh()
            return
        }
        operationError = null
        showPrepare = true
        LinuxSandboxSession.kick(context, rebuild)
        revision++
    }

    fun openPermissionSettings() {
        val specific = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        try {
            context.startActivity(specific)
        } catch (_: ActivityNotFoundException) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            allFiles = LinuxSandboxAccess.hasAllFiles()
            if (awaitingPermission && allFiles) {
                LinuxSandboxStore.saveEnabled(layout.stateFile, true)
                enabled = true
            }
            val granted = awaitingPermission && allFiles
            awaitingPermission = false
            if (granted) beginPrepare(rebuild = false)
            refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        if (enabled && LinuxSandboxAccess.hasAllFiles()) beginPrepare(rebuild = false)
    }

    LaunchedEffect(revision) {
        if (!showPrepare) return@LaunchedEffect
        val started = System.nanoTime()
        var sawPreparing = false
        while (true) {
            if (LinuxSandboxSession.preparing) sawPreparing = true
            val elapsed = System.nanoTime() - started
            if (sawPreparing && !LinuxSandboxSession.preparing) break
            if (!sawPreparing && elapsed > 2_000_000_000L) break
            if (elapsed > 120_000_000_000L) break
            delay(100)
        }
        refresh()
        val error = LinuxSandboxSession.lastError
        if (error.isNullOrBlank()) {
            showPrepare = false
        } else {
            showPrepare = false
            operationError = error
            errorNonce++
        }
    }

    LaunchedEffect(deleting) {
        if (!deleting) return@LaunchedEffect
        enabled = false
        awaitingPermission = false
        try {
            LinuxSandboxSession.delete(context)
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            operationError = error.message?.takeIf { it.isNotBlank() } ?: tr("删除失败")
            errorNonce++
        } finally {
            enabled = LinuxSandboxStore.loadEnabled(layout.stateFile)
            deleting = false
            refresh()
        }
    }

    SettingsHazeScaffold(
        title = tr("Linux 沙盒"),
        onBack = onBack,
    ) { topInset, bottomInset ->
        Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = bottomInset)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .padding(top = topInset)
                .padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier
                    .settingsRow()
                    .background(Theme[colors][secondaryColor], Theme[shapes][largeShape])
                    .settingsSearchAnchor("linux.enabled", horizontalOutset = 0.dp, edge = SettingsSearchEdge.All, verticalOutset = 0.dp)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(tr("启用 Linux 沙盒"), modifier = Modifier.weight(1f), color = Theme[colors][onPanelColor])
                Switch(
                    checked = enabled,
                    enabled = !deleting,
                    onCheckedChange = { want ->
                        if (!want) {
                            LinuxSandboxStore.saveEnabled(layout.stateFile, false)
                            enabled = false
                            LinuxBackgroundJobs.stopAll()
                            refresh()
                            return@Switch
                        }
                        if (LinuxSandboxAccess.hasAllFiles()) {
                            LinuxSandboxStore.saveEnabled(layout.stateFile, true)
                            enabled = true
                            allFiles = true
                            beginPrepare(rebuild = false)
                        } else {
                            awaitingPermission = true
                            openPermissionSettings()
                        }
                    },
                )
            }
            if (enabled && !allFiles) {
                ListRowButton(searchId = "linux.permission", label = tr("授予权限"), onClick = {
                    awaitingPermission = true
                    openPermissionSettings()
                })
            }
            if (offerRebuild) {
                ListRowButton(searchId = "linux.rebuild", label = tr("用安装包里的版本重新准备"), onClick = { confirmRebuild = true })
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Theme[colors][secondaryColor], Theme[shapes][largeShape])
                    .padding(horizontal = 16.dp, vertical = SettingsCardInset),
            ) {
                SettingsSlider(
                    modifier = Modifier.settingsSearchAnchor("linux.commands", edge = SettingsSearchEdge.All),
                    label = tr("单次命令数"),
                    value = commandsPerCall,
                    valueRange = LinuxSandboxStore.COMMANDS_UNLIMITED..LinuxSandboxStore.COMMANDS_MAX,
                    valueLabel = { count ->
                        if (count == LinuxSandboxStore.COMMANDS_UNLIMITED) tr("无限") else "$count"
                    },
                    onValueChange = { next ->
                        val clamped = linuxSandboxClampCommandsPerCall(next)
                        commandsPerCall = clamped
                        LinuxSandboxStore.saveCommandsPerCall(layout.stateFile, clamped)
                    },
                )
            }
            Row(
                modifier = Modifier
                    .settingsRow()
                    .background(Theme[colors][secondaryColor], Theme[shapes][largeShape])
                    .settingsSearchAnchor("linux.background", horizontalOutset = 0.dp, edge = SettingsSearchEdge.All, verticalOutset = 0.dp)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(tr("后台命令"), modifier = Modifier.weight(1f), color = Theme[colors][onPanelColor])
                Switch(
                    checked = backgroundCommands,
                    onCheckedChange = { want ->
                        LinuxSandboxStore.saveBackgroundEnabled(layout.stateFile, want)
                        backgroundCommands = want
                        if (!want) LinuxBackgroundJobs.stopAll()
                    },
                )
            }
            Button(
                onClick = { confirmDelete = true },
                enabled = LinuxSandboxPolicy.canDelete(installed, preparing = showPrepare, deleting = deleting),
                modifier = Modifier.settingsSearchAnchor("linux.delete", horizontalOutset = 0.dp, edge = SettingsSearchEdge.All, verticalOutset = 0.dp).fillMaxWidth(),
                style = ButtonStyle.Destructive,
            ) { Text(tr("删除 Linux 沙盒"), color = LocalContentColor.current) }
        }
        IrioSnackbar(
            visible = operationError != null,
            message = operationError.orEmpty(),
            bottomInset = bottomInset,
            onDismiss = { operationError = null },
            restartKey = errorNonce,
        )
        }
    }

    IrioAlertDialog(
        visible = confirmDelete,
        onDismissRequest = { confirmDelete = false },
    ) {
        Column {
            DialogTitle(tr("删除 Linux 沙盒"))
            Spacer(Modifier.height(12.dp))
            Text(tr("确定要删除 Linux 沙盒吗？沙盒内的软件包和文件将被永久删除，正在运行的命令会停止。设备共享存储里的文件不会删除。此操作无法撤销，再次启用会重新准备环境。"), color = Theme[colors][onPanelColor])
            Spacer(Modifier.height(16.dp))
            DialogButtonRow(
                confirmLabel = tr("删除"),
                confirmEnabled = LinuxSandboxPolicy.canDelete(installed, preparing = showPrepare, deleting = deleting),
                onConfirm = {
                    confirmDelete = false
                    operationError = null
                    deleting = true
                },
                onCancel = { confirmDelete = false },
                confirmStyle = ButtonStyle.Destructive,
            )
        }
    }

    IrioAlertDialog(
        visible = deleting,
        onDismissRequest = {},
    ) {
        Column {
            DialogTitle(tr("正在删除 Linux 沙盒"))
            Spacer(Modifier.height(16.dp))
            ProgressIndicator(progress = 0.5f, modifier = Modifier.fillMaxWidth())
        }
    }

    IrioAlertDialog(
        visible = confirmRebuild,
        onDismissRequest = { confirmRebuild = false },
        backgroundColor = Theme[colors][panelColor],
        contentColor = Theme[colors][onPanelColor],
    ) {
        Column {
            DialogTitle(tr("重新准备环境"))
            Spacer(Modifier.height(12.dp))
            Text(tr("用安装包里的 Alpine 重新准备会删掉当前环境，包括之后安装的软件包。设备共享存储里的文件不会删，关闭开关也不会删。"), color = Theme[colors][onPanelColor])
            Spacer(Modifier.height(16.dp))
            DialogButtonRow(
                confirmLabel = tr("重新准备"),
                onConfirm = {
                    confirmRebuild = false
                    beginPrepare(rebuild = true)
                },
                onCancel = { confirmRebuild = false },
                confirmStyle = ButtonStyle.Destructive,
            )
        }
    }

    IrioAlertDialog(
        visible = showPrepare,
        onDismissRequest = {},
        backgroundColor = Theme[colors][panelColor],
        contentColor = Theme[colors][onPanelColor],
    ) {
        Column {
            DialogTitle(tr("正在准备环境"))
            Spacer(Modifier.height(16.dp))
            ProgressIndicator(progress = 0.5f, modifier = Modifier.fillMaxWidth())
        }
    }
}
