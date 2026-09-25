package com.irio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() {
    application {
        LinkRuntime.ensure()
        Window(
            onCloseRequest = ::exitApplication,
            title = "Irio",
            state = rememberWindowState(width = 1100.dp, height = 760.dp),
        ) {
            AppTheme {
                IrioApp()
            }
        }
    }
}

@Composable
private fun IrioApp() {
    var screen by remember { mutableStateOf<Screen>(Screen.Chat) }
    Box(Modifier.fillMaxSize()) {
        ChatScreen(onOpenSettings = { screen = Screen.Settings })
        when (val current = screen) {
            Screen.Chat -> Unit
            Screen.Settings -> SettingsScreen(
                onBack = { screen = Screen.Chat },
                onProviders = { screen = Screen.Providers },
                onLink = { screen = Screen.Link },
                onModel = { screen = Screen.Model },
                onAppearance = { screen = Screen.Appearance },
                onMemory = { screen = Screen.Memory },
                onOther = { screen = Screen.Other },
            )
            Screen.Link -> LinkSettingsScreen(
                onBack = { screen = Screen.Settings },
            )
            Screen.Model -> ModelSettingsScreen(
                onBack = { screen = Screen.Settings },
            )
            Screen.Appearance -> AppearanceSettingsScreen(
                onBack = { screen = Screen.Settings },
            )
            Screen.Memory -> MemorySettingsScreen(
                onBack = { screen = Screen.Settings },
            )
            Screen.Other -> OtherSettingsScreen(
                onBack = { screen = Screen.Settings },
            )
            Screen.Providers -> ProviderSettingsScreen(
                onBack = { screen = Screen.Settings },
                onOpenProvider = { screen = Screen.ProviderDetail(it) },
            )
            is Screen.ProviderDetail -> ProviderDetailScreen(
                providerId = current.id,
                onBack = { screen = Screen.Providers },
                onDeleted = { screen = Screen.Providers },
            )
        }
    }
}
