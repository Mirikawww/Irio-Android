package com.irio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.irio.IrioSwitch as Switch
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
        AndroidComposeRecyclerList(
            items = listOf("appearance", "haptics"),
            key = { it },
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topInset + 16.dp, bottom = bottomInset + 16.dp),
            itemSpacing = 12.dp,
        ) { section ->
            if (section == "haptics") {
                HapticSettingsCard()
            } else {
                SettingsSectionCard(title = tr("外观")) {
                    Row(
                        modifier = Modifier.settingsSwitchRow()
                            .settingsSearchAnchor("laboratory.betterSettingsDisplay", edge = SettingsSearchEdge.Top),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(tr("更好的设置项展示"), modifier = Modifier.weight(1f), color = Theme[colors][onBackgroundColor])
                        Switch(
                            checked = ThemeStore.betterSettingsDisplay,
                            accessibilityLabel = tr("更好的设置项展示"),
                            onCheckedChange = { ThemeStore.saveBetterSettingsDisplay(context, it) },
                        )
                    }
                    Row(
                        modifier = Modifier.settingsSwitchRow()
                            .settingsSearchAnchor("laboratory.glyph", edge = SettingsSearchEdge.Bottom),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ExperimentalLabel(
                            tr("文本淡入"),
                            color = Theme[colors][onBackgroundColor],
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = ThemeStore.glyphFade,
                            accessibilityLabel = tr("文本淡入"),
                            onCheckedChange = { ThemeStore.saveGlyphFade(context, it) },
                        )
                    }
                }
            }
        }
    }
}
