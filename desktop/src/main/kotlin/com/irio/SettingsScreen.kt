package com.irio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
) {
    val background = Theme[colors][backgroundColor]
    val content = Theme[colors][onBackgroundColor]

    Scaffold(
        containerColor = background,
        topBar = {
            IrioToolbar(
                title = { Text("设置") },
                backgroundColor = background,
                contentColor = content,
                leading = {
                    IrioBackButton(onBack)
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
                label = "提供商",
                icon = Icons.Rounded.DataObject,
                onClick = onProviders,
            )
            Spacer(Modifier.height(12.dp))
            ListRowButton(
                label = "模型",
                icon = Icons.Rounded.SmartToy,
                onClick = onModel,
            )
            Spacer(Modifier.height(12.dp))
            ListRowButton(
                label = "记忆",
                icon = Icons.Rounded.Memory,
                onClick = onMemory,
            )
            Spacer(Modifier.height(12.dp))
            ListRowButton(
                label = "外观",
                icon = Icons.Rounded.Palette,
                onClick = onAppearance,
            )
            Spacer(Modifier.height(12.dp))
            ListRowButton(
                label = "互连",
                icon = Icons.Rounded.Devices,
                onClick = onLink,
            )
        }
    }
}
