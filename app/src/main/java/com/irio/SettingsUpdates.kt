package com.irio

import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.ui.components.Button
import com.composables.ui.components.ButtonStyle
import com.composables.ui.components.DropdownMenu
import com.composables.ui.components.DropdownMenuAlignment
import com.composables.ui.components.DropdownMenuItem
import com.irio.ScaledDropdownMenuPanel as DropdownMenuPanel
import com.composables.ui.components.Icon
import com.composables.ui.components.Text
import com.composeunstyled.LocalContentColor
import com.composeunstyled.theme.Theme
import com.composables.ui.theme.colors
import com.composables.ui.theme.onPanelColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.IOException

@Composable
internal fun SettingsUpdateSection(
    onNotice: (String) -> Unit,
    checkForUpdates: suspend (String, UpdateChannel) -> UpdateCheckResult = { version, channel ->
        GitHubUpdateClient().check(version, channel)
    },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var channel by remember { mutableStateOf(UpdateChannel.load(context)) }
    var checking by remember { mutableStateOf(false) }
    var available by remember { mutableStateOf<GitHubUpdate?>(null) }
    SettingsUpdateButtons(
        channel = channel,
        checking = checking,
        onChannelChange = { channel = it; it.save(context) },
        onCheck = {
            if (!checking) {
                checking = true
                val requestedChannel = channel
                scope.launch {
                    try {
                        val version = context.packageManager.getPackageInfo(context.packageName,
                            PackageManager.PackageInfoFlags.of(0)).versionName.orEmpty()
                        when (val result = checkForUpdates(version, requestedChannel)) {
                            is UpdateCheckResult.Available -> available = result.release
                            UpdateCheckResult.UpToDate -> onNotice(tr("当前已是最新版本"))
                            UpdateCheckResult.NoReleases -> onNotice(tr("所选渠道暂无已发布版本"))
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: UpdateCheckException) {
                        onNotice(tr(e.messageKey))
                    } catch (_: IOException) {
                        onNotice(tr("检查更新失败，请检查网络后重试"))
                    } finally {
                        checking = false
                    }
                }
            }
        },
    )
    GitHubUpdateDialog(
        release = available,
        onDismiss = { available = null },
        onLinkError = { onNotice(tr("无法打开链接")) },
    )
}

@Composable
internal fun GitHubUpdateDialog(release: GitHubUpdate?, onDismiss: () -> Unit, onLinkError: () -> Unit) {
    val context = LocalContext.current
    IrioAlertDialog(visible = release != null, onDismissRequest = onDismiss) {
        release?.let {
            UpdateDialogContent(
                release = release,
                onClose = onDismiss,
                onDownload = {
                    if (!context.openAboutLink(release.url)) onLinkError()
                    onDismiss()
                },
            )
        }
    }
}

@Composable
internal fun UpdateDialogContent(release: GitHubUpdate, onClose: () -> Unit, onDownload: () -> Unit) {
    val renderScope = rememberCoroutineScope()
    val maxHeight = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.height.toDp() * 0.8f
    }.coerceAtLeast(160.dp)
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight).testTag("update-dialog-content"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DialogTitle(tr("发现新版本"))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(release.tag, color = Theme[colors][onPanelColor], fontSize = 18.sp, lineHeight = 24.sp)
            Text(if (release.prerelease) UpdateChannel.PRE_RELEASE.label else UpdateChannel.RELEASE.label, color = Theme[colors][onPanelColor])
        }
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
                .testTag("update-release-notes").verticalScroll(rememberScrollState()),
        ) {
            if (release.notes.isBlank()) Text(tr("暂无更新说明"), color = Theme[colors][onPanelColor])
            else AssistantMarkdown(text = release.notes, streaming = false, renderScope = renderScope)
        }
        DialogButtonRow(
            confirmLabel = tr("前往下载"),
            onConfirm = onDownload,
            // The existing generic “关闭” translation is “Off” for switches.
            cancelLabel = if (ThemeStore.language == AppLanguage.EN) "Close" else "关闭",
            onCancel = onClose,
        )
    }
}

@Composable
internal fun SettingsUpdateButtons(
    channel: UpdateChannel,
    checking: Boolean,
    onChannelChange: (UpdateChannel) -> Unit,
    onCheck: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = onCheck,
            enabled = !checking,
            style = ButtonStyle.Secondary,
            shape = ContinuousCapsule,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("check-updates")
                .settingsSearchAnchor("settings.updates"),
        ) { Text(tr(if (checking) "正在检查更新" else "检查更新"), color = LocalContentColor.current) }
        DropdownMenu(
            expanded = menuOpen && !checking,
            onExpandedChange = { menuOpen = it },
            modifier = Modifier.wrapContentWidth(Alignment.End),
            alignment = DropdownMenuAlignment.End,
            panel = {
                DropdownMenuPanel {
                    UpdateChannel.entries.forEach { option ->
                        DropdownMenuItem(
                            onClick = { onChannelChange(option); menuOpen = false },
                            trailing = {
                                if (option == channel) Icon(IrioIcons.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                else Spacer(Modifier.size(16.dp))
                            },
                        ) { Text(option.label, color = LocalContentColor.current) }
                    }
                }
            },
        ) {
            Button(
                onClick = { menuOpen = !menuOpen },
                enabled = !checking,
                style = ButtonStyle.Secondary,
                shape = ContinuousCapsule,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                modifier = Modifier.heightIn(min = 48.dp).testTag("update-channel")
                    .settingsSearchAnchor("settings.update-channel")
                    .semantics { contentDescription = tr("检查渠道"); stateDescription = channel.label },
            ) {
                Text(channel.label, color = LocalContentColor.current)
                Icon(IrioIcons.ChevronDown, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}
