package com.irio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import com.composables.ui.components.Button
import com.composables.ui.components.ButtonSize
import com.composables.ui.components.ButtonStyle
import com.composables.ui.components.DropdownMenu
import com.composables.ui.components.DropdownMenuAlignment
import com.composables.ui.components.DropdownMenuItem
import com.composables.ui.components.DropdownMenuPanel
import com.composables.ui.components.Icon
import com.composables.ui.components.IconButton
import com.composables.ui.components.ProgressIndicator
import com.composables.ui.components.Text
import com.composables.ui.components.Switch
import com.composables.ui.components.TextField
import com.composables.ui.theme.backgroundColor
import com.composables.ui.theme.colors
import com.composables.ui.theme.destructiveColor
import com.composables.ui.theme.fieldColor
import com.composables.ui.theme.fieldShape
import com.composables.ui.theme.largeShape
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.onBackgroundColor
import com.composables.ui.theme.onPanelColor
import com.composables.ui.theme.secondaryColor
import com.composables.ui.theme.shapes
import com.composeunstyled.theme.Theme

class LinkSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LinkRuntime.ensure(this)
        setIrioContent {
            LinkSettingsScreen(onBack = { finish() })
        }
    }
}

@Composable
private fun LinkSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val background = Theme[colors][backgroundColor]
    val content = Theme[colors][onBackgroundColor]
    val muted = Theme[colors][mutedColor]
    val state by LinkRuntime.state.collectAsState()
    val codeState = rememberTextFieldState()
    var modeMenuOpen by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val shake = remember { Animatable(0f) }
    var pairInvalid by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        LinkRuntime.ensure(context)
        LinkRuntime.refreshPairing()
    }

    LaunchedEffect(state.pairErrorNonce) {
        if (state.pairErrorNonce == 0) {
            pairInvalid = false
            return@LaunchedEffect
        }
        pairInvalid = true
        shake.snapTo(0f)
        listOf(-10f, 10f, -8f, 8f, -4f, 4f, 0f).forEach { target ->
            shake.animateTo(target, tween(36))
        }
    }

    LaunchedEffect(state.pairSuccessNonce) {
        if (state.pairSuccessNonce == 0) return@LaunchedEffect
        codeState.clearText()
        pairInvalid = false
    }

    SettingsHazeScaffold(
        title = tr("互连"),
        onBack = onBack,
    ) { topInset, bottomInset ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = bottomInset)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .padding(top = topInset)
                .padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LinkCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(tr("启用互连"), modifier = Modifier.weight(1f), color = Theme[colors][onPanelColor])
                    Switch(
                        checked = state.settings.enabled,
                        onCheckedChange = { LinkRuntime.setEnabled(it) },
                    )
                }
            }
            Text(
                tr("两端必须在同一 Wi-Fi。已发送的文件不会同步。"),
                color = muted,
                fontSize = 13.sp,
            )
            if (state.settings.enabled) {
                LinkCard {
                    Text(
                        tr("本机配对码"),
                        modifier = Modifier.fillMaxWidth(),
                        color = muted,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                    )
                    val pairingText = if (state.pairingHost.isBlank()) {
                        state.pairingCode.ifBlank { tr("正在获取局域网地址") }
                    } else {
                        LinkProtocol.encodeCode(state.pairingCode, state.pairingHost, state.pairingPort)
                    }
                    BasicText(
                        text = pairingText,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                clipboard.setText(AnnotatedString(pairingText))
                            },
                        style = TextStyle(
                            color = Theme[colors][onPanelColor],
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.Center,
                        ),
                        maxLines = 1,
                        autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = 28.sp),
                    )
                }
                TextField(
                    state = codeState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            rememberInputFieldBorder(
                                errorColor = if (pairInvalid) irioErrorTextColor() else null,
                            ),
                        )
                        .offset { IntOffset(shake.value.toInt(), 0) },
                    accessibilityLabel = tr("输入配对码"),
                    placeholder = {
                        Text(
                            tr("输入对方配对码"),
                            style = PairingFieldTextStyle.copy(baselineShift = BaselineShift(0.12f)),
                            color = muted,
                        )
                    },
                    textStyle = PairingFieldTextStyle,
                    lineHeight = 16.sp,
                    fontSize = 16.sp,
                    shape = Theme[shapes][fieldShape],
                    contentPadding = PaddingValues(12.dp),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                )
                Button(
                    onClick = { LinkRuntime.pairWithCode(codeState.text.toString()) },
                    modifier = Modifier.fillMaxWidth(),
                    style = ButtonStyle.Secondary,
                ) { Text(tr("配对")) }

                DropdownMenu(
                    expanded = modeMenuOpen,
                    onExpandedChange = { modeMenuOpen = it },
                    modifier = Modifier.fillMaxWidth(),
                    alignment = DropdownMenuAlignment.End,
                    panel = {
                        DropdownMenuPanel {
                            LinkSyncMode.entries.forEach { option ->
                                DropdownMenuItem(
                                    onClick = {
                                        LinkRuntime.setSyncMode(option)
                                        modeMenuOpen = false
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(tr(option.label), color = Theme[colors][onPanelColor]) }
                            }
                        }
                    },
                ) {
                    val interactionSource = remember { MutableInteractionSource() }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Theme[colors][fieldColor], Theme[shapes][fieldShape])
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null,
                                onClick = { modeMenuOpen = !modeMenuOpen },
                            )
                            .padding(start = 12.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(tr("同步方式"), color = Theme[colors][onPanelColor])
                            Text(tr(state.settings.syncMode.label), color = Theme[colors][mutedColor], fontSize = 13.sp)
                        }
                        IconButton(
                            onClick = { modeMenuOpen = !modeMenuOpen },
                            style = ButtonStyle.Ghost,
                            buttonSize = ButtonSize.Small,
                            shape = CircleShape,
                            modifier = Modifier.size(32.dp),
                            interactionSource = interactionSource,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.KeyboardArrowDown,
                                contentDescription = tr("选择同步方式"),
                                tint = Theme[colors][onPanelColor],
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
                LinkCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(tr("自动同步数据"), modifier = Modifier.weight(1f), color = Theme[colors][onPanelColor])
                        Switch(
                            checked = state.settings.autoSync,
                            onCheckedChange = { LinkRuntime.setAutoSync(it) },
                        )
                    }
                }

                Button(
                    onClick = { LinkRuntime.syncNow() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.syncing,
                ) { Text(if (state.syncing) tr("正在同步") else tr("立即同步")) }
                if (state.syncing) {
                    ProgressIndicator(
                        progress = state.syncProgress.coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Text(tr("已配对设备"), color = muted, fontSize = 12.sp)
                if (state.settings.peers.isEmpty()) {
                    Text(tr("还没有设备"), color = muted)
                } else {
                    state.settings.peers.forEach { peer ->
                        PeerRow(
                            peer = peer,
                            connection = state.connections[peer.id] ?: LinkConnectionState.DISCONNECTED,
                            onForget = { LinkRuntime.forget(peer.id) },
                        )
                    }
                }
            }
            state.status?.let { message ->
                Text(message, color = irioErrorTextColor(), fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun LinkCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
                    .background(Theme[colors][secondaryColor], Theme[shapes][largeShape])
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = { content() },
    )
}

@Composable
private fun PeerRow(
    peer: LinkedPeer,
    connection: LinkConnectionState,
    onForget: () -> Unit,
) {
    val content = Theme[colors][onBackgroundColor]
    val muted = Theme[colors][mutedColor]
    val icon: ImageVector = if (peer.platform == LinkPlatform.PHONE) {
        Icons.Rounded.PhoneAndroid
    } else {
        Icons.Rounded.Computer
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = tr(peer.platform.label),
            modifier = Modifier.size(20.dp),
            tint = content,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(peer.name, color = content)
            Text("%s · %s".format(tr(peer.platform.label), tr(connection.label)), color = muted, fontSize = 12.sp)
        }
        IconButton(
            onClick = onForget,
            style = ButtonStyle.Ghost,
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = tr("删除设备"),
                tint = muted,
            )
        }
    }
}

private val PairingFieldTextStyle = TextStyle(
    fontSize = 16.sp,
    lineHeight = 16.sp,
)
