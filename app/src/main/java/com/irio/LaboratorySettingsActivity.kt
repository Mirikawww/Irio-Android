package com.irio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.composables.ui.components.Switch
import com.composables.ui.components.Text
import com.composables.ui.theme.colors
import com.composables.ui.theme.onBackgroundColor
import com.composeunstyled.theme.Theme

class LaboratorySettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setIrioContent { LaboratorySettingsScreen(onBack = { finish() }) }
    }
}

@Composable
internal fun LaboratorySettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    ThemeStore.ensureLoaded(context)
    SettingsHazeScaffold(
        title = tr("实验室"),
        onBack = onBack,
    ) { topInset, bottomInset ->
        Column(Modifier.fillMaxSize().padding(top = topInset, bottom = bottomInset).padding(16.dp)) {
            SettingsSectionCard(title = tr("外观")) {
                Row(
                    modifier = Modifier.settingsSwitchRow()
                        .settingsSearchAnchor("laboratory.betterSettingsDisplay"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(tr("更好的设置项展示"), modifier = Modifier.weight(1f), color = Theme[colors][onBackgroundColor])
                    Switch(
                        checked = ThemeStore.betterSettingsDisplay,
                        onCheckedChange = { ThemeStore.saveBetterSettingsDisplay(context, it) },
                    )
                }
            }
        }
    }
}
