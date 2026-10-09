package com.irio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier

class McpSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setIrioContent {
            SettingsHazeScaffold(
                title = "MCP",
                onBack = { finish() },
                titleTrailing = { ExperimentalBadge() },
            ) { topInset, bottomInset ->
                Box(Modifier.fillMaxSize().padding(top = topInset, bottom = bottomInset))
            }
        }
    }
}
