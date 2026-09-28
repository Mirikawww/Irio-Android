package com.irio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composables.ui.components.ButtonStyle
import com.composables.ui.components.Icon
import com.composables.ui.components.IconButton
import com.composables.ui.components.Text
import com.composables.ui.theme.backgroundColor
import com.composables.ui.theme.colors
import com.composables.ui.theme.onBackgroundColor
import com.composeunstyled.theme.Theme

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onProviders: () -> Unit,
    onLink: () -> Unit,
    onModel: () -> Unit,
    onAppearance: () -> Unit,
    onMemory: () -> Unit,
    onOther: () -> Unit,
) {
    val background = Theme[colors][backgroundColor]
    val content = Theme[colors][onBackgroundColor]

    Scaffold(
        containerColor = background,
        topBar = {
            IrioToolbar(
                title = { Text(tr("设置")) },
                backgroundColor = background,
                contentColor = content,
                leading = {
                    IconButton(
                        onClick = onBack,
                        style = ButtonStyle.Ghost,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = tr("返回"),
                            tint = content,
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            ListRowButton(
                label = tr("提供商"),
                icon = Icons.Rounded.DataObject,
                onClick = onProviders,
            )
            Spacer(Modifier.height(12.dp))
            ListRowButton(
                label = tr("模型"),
                icon = Icons.Rounded.SmartToy,
                onClick = onModel,
            )
            Spacer(Modifier.height(12.dp))
            ListRowButton(
                label = tr("记忆"),
                icon = Icons.Rounded.Memory,
                onClick = onMemory,
            )
            Spacer(Modifier.height(12.dp))
            ListRowButton(
                label = tr("外观"),
                icon = Icons.Rounded.Palette,
                onClick = onAppearance,
            )
            Spacer(Modifier.height(12.dp))
            ListRowButton(
                label = tr("互连"),
                icon = Icons.Rounded.Devices,
                onClick = onLink,
            )
            Spacer(Modifier.height(12.dp))
            ListRowButton(
                label = tr("其他"),
                icon = Icons.Rounded.MoreHoriz,
                onClick = onOther,
            )
        }
    }
}
