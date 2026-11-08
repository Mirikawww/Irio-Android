package com.irio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

class LaboratorySettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setIrioContent { LaboratorySettingsScreen(onBack = { finish() }) }
    }
}

@Composable
internal fun LaboratorySettingsScreen(onBack: () -> Unit) {
    SettingsHazeScaffold(
        title = tr("实验室"),
        onBack = onBack,
    ) { topInset, bottomInset ->
        Box(Modifier.fillMaxSize().padding(top = topInset, bottom = bottomInset))
    }
}
