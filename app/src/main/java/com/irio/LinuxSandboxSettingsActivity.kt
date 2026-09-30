package com.irio

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
    var allFiles by remember { mutableStateOf(LinuxSandboxAccess.hasAllFiles()) }
    var awaitingPermission by remember { mutableStateOf(false) }
    var confirmRebuild by remember { mutableStateOf(false) }
    var showPrepare by remember { mutableStateOf(false) }
    var prepareError by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var offerRebuild by remember { mutableStateOf(false) }
    val background = Theme[colors][backgroundColor]
    val content = Theme[colors][onBackgroundColor]
    val lifecycleOwner = LocalLifecycleOwner.current

    fun refresh() {
        val bundled = runCatching { loadLinuxSandboxCatalog(context).alpineVersion }.getOrNull()
        offerRebuild = linuxSandboxOfferRebuild(layout.installedVersion(), bundled, layout.shellPresent())
    }

    fun beginPrepare(rebuild: Boolean) {
        val extract = linuxSandboxShouldExtract(layout.installedVersion(), layout.rootfs.isDirectory, rebuild)
        if (!extract) {
            LinuxSandboxSession.kick(context, rebuild)
            refresh()
            return
        }
        prepareError = null
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
            prepareError = null
            showPrepare = false
        } else {
            prepareError = error
        }
    }

    Scaffold(
        containerColor = background,
        contentWindowInsets = irioSystemUiInsets(),
        topBar = {
            IrioToolbar(
                title = {
                    Text(tr("Linux 沙盒"))
                    Spacer(Modifier.width(8.dp))
                    ExperimentalBadge()
                },
                backgroundColor = background,
                contentColor = content,
                leading = {
                    IconButton(onClick = onBack, style = ButtonStyle.Ghost) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = tr("返回"),
                            tint = content,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Theme[colors][secondaryColor], Theme[shapes][largeShape])
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(tr("启用 Linux 沙盒"), modifier = Modifier.weight(1f), color = Theme[colors][onPanelColor])
                Switch(
                    checked = enabled,
                    onCheckedChange = { want ->
                        if (!want) {
                            LinuxSandboxStore.saveEnabled(layout.stateFile, false)
                            enabled = false
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
                ListRowButton(label = tr("授予权限"), onClick = {
                    awaitingPermission = true
                    openPermissionSettings()
                })
            }
            if (offerRebuild) {
                ListRowButton(label = tr("用安装包里的版本重新准备"), onClick = { confirmRebuild = true })
            }
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
            Text(tr("用安装包里的 Alpine 重新准备会删掉当前环境，包括之后安装的软件包。设备共享存储里的文件不会删，关闭开关也不会删。"))
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
        onDismissRequest = {
            if (prepareError != null) {
                prepareError = null
                showPrepare = false
            }
        },
        backgroundColor = Theme[colors][panelColor],
        contentColor = Theme[colors][onPanelColor],
    ) {
        Column {
            if (prepareError == null) {
                DialogTitle(tr("正在准备环境"))
                Spacer(Modifier.height(16.dp))
                ProgressIndicator(progress = 0.5f, modifier = Modifier.fillMaxWidth())
            } else {
                DialogTitle(tr("准备失败"))
                Spacer(Modifier.height(12.dp))
                Text(prepareError.orEmpty())
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        prepareError = null
                        showPrepare = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(tr("知道了")) }
            }
        }
    }
}
