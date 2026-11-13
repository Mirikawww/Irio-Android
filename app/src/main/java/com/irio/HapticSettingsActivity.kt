package com.irio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.irio.IrioCheckbox as Checkbox
import com.composables.ui.components.Text
import com.composables.ui.theme.colors
import com.composables.ui.theme.onPanelColor
import com.composeunstyled.theme.Theme

class HapticSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setIrioContent { HapticSettingsScreen(onBack = { finish() }) }
    }
}

@Composable
internal fun HapticSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(HapticPreferences.load(context)) }
    SettingsHazeScaffold(title = tr("触感反馈"), onBack = onBack) { topInset, bottomInset ->
        AndroidComposeRecyclerList(
            items = listOf("locations"),
            key = { it },
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp,
                top = topInset + 16.dp, bottom = bottomInset + 16.dp),
        ) {
            SettingsSectionCard {
                HapticEvent.entries.forEach { event ->
                    Checkbox(
                        checked = settings.isEnabled(event),
                        onCheckedChange = { enabled ->
                            HapticPreferences.saveEvent(context, event, enabled)
                            settings = HapticPreferences.load(context)
                        },
                        modifier = Modifier.settingsSearchAnchor(event.searchId)
                            .fillMaxWidth().padding(vertical = 2.dp),
                        accessibilityLabel = tr(event.label),
                    ) {
                        Text(tr(event.label), color = Theme[colors][onPanelColor])
                    }
                }
            }
        }
    }
}
