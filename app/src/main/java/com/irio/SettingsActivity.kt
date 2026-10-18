package com.irio

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.ui.components.ButtonStyle
import com.composables.ui.components.Icon
import com.composables.ui.components.IconButton
import com.composables.ui.components.Text
import com.composables.ui.theme.backgroundColor
import com.composables.ui.theme.colors
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.onBackgroundColor
import com.composeunstyled.theme.Theme

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setIrioContent {
            SettingsScreen(onBack = { finish() })
        }
    }
}

@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val background = Theme[colors][backgroundColor]
    val content = Theme[colors][onBackgroundColor]
    val softwareInfo = remember { androidSoftwareInfo(context) }

    SettingsHazeScaffold(
        title = tr("设置"),
        onBack = onBack,
    ) { topInset, bottomInset ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = bottomInset),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(top = topInset)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            ) {
                ListRowButton(
                    label = tr("提供商"),
                    icon = Icons.Rounded.DataObject,
                    onClick = {
                        context.startActivity(Intent(context, ProviderSettingsActivity::class.java))
                    },
                )
                Spacer(Modifier.height(12.dp))
                ListRowButton(
                    label = tr("模型"),
                    icon = Icons.Rounded.SmartToy,
                    onClick = {
                        context.startActivity(Intent(context, ModelSettingsActivity::class.java))
                    },
                )
                Spacer(Modifier.height(12.dp))
                ListRowButton(
                    label = "TTS/STT",
                    icon = Icons.Rounded.RecordVoiceOver,
                    onClick = {
                        context.startActivity(Intent(context, SpeechSettingsActivity::class.java))
                    },
                )
                Spacer(Modifier.height(12.dp))
                ListRowButton(
                    label = tr("记忆"),
                    icon = Icons.Rounded.Memory,
                    onClick = {
                        context.startActivity(Intent(context, MemorySettingsActivity::class.java))
                    },
                )
                Spacer(Modifier.height(12.dp))
                ListRowButton(
                    label = "MCP",
                    iconPainter = painterResource(R.drawable.ic_mcp),
                    onClick = { context.startActivity(Intent(context, McpSettingsActivity::class.java)) },
                )
                Spacer(Modifier.height(12.dp))
                ListRowButton(
                    label = tr("外观"),
                    icon = Icons.Rounded.Palette,
                    onClick = {
                        context.startActivity(Intent(context, AppearanceSettingsActivity::class.java))
                    },
                )
                Spacer(Modifier.height(12.dp))
                ListRowButton(
                    label = tr("互连"),
                    icon = Icons.Rounded.Devices,
                    onClick = {
                        context.startActivity(Intent(context, LinkSettingsActivity::class.java))
                    },
                )
                Spacer(Modifier.height(12.dp))
                ListRowButton(
                    label = tr("Linux 沙盒"),
                    icon = Icons.Rounded.Terminal,
                    onClick = {
                        context.startActivity(Intent(context, LinuxSandboxSettingsActivity::class.java))
                    },
                )
                Spacer(Modifier.height(12.dp))
                ListRowButton(
                    label = tr("其他"),
                    icon = Icons.Rounded.MoreHoriz,
                    onClick = {
                        context.startActivity(Intent(context, OtherSettingsActivity::class.java))
                    },
                )
            }
            Text(
                softwareInfo,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                color = Theme[colors][mutedColor],
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}
