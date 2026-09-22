package com.irio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.ui.components.Button
import com.composables.ui.components.ButtonSize
import com.composables.ui.components.ButtonStyle
import com.composables.ui.components.DropdownMenu
import com.composables.ui.components.DropdownMenuAlignment
import com.composables.ui.components.DropdownMenuItem
import com.composables.ui.components.DropdownMenuPanel
import com.composables.ui.components.Icon
import com.composables.ui.components.IconButton
import com.composables.ui.components.Text
import com.composables.ui.components.Switch
import com.composables.ui.theme.backgroundColor
import com.composables.ui.theme.colors
import com.composables.ui.theme.fieldShape
import com.composables.ui.theme.largeShape
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.onBackgroundColor
import com.composables.ui.theme.onPanelColor
import com.composables.ui.theme.secondaryColor
import com.composables.ui.theme.shapes
import com.composeunstyled.theme.Theme

@Composable
private fun AccentSwatch(color: Color) {
    Box(
        modifier = Modifier
            .size(12.dp)
            .background(color, CircleShape),
    )
}

class AppearanceSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setIrioContent {
            AppearanceSettingsScreen(onBack = { finish() })
        }
    }
}

@Composable
private fun AppearanceSettingsScreen(onBack: () -> Unit) {
    val background = Theme[colors][backgroundColor]
    val content = Theme[colors][onBackgroundColor]
    val context = LocalContext.current
    var texScalePercent by remember { mutableIntStateOf((ProviderStore.loadTexScale(context) * 100).toInt()) }
    val themePreference = ThemeStore.preference
    val accent = ThemeStore.accent
    val autoAmoled = ThemeStore.autoAmoled
    var themeMenuOpen by remember { mutableStateOf(false) }
    var accentMenuOpen by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = background,
        contentWindowInsets = irioSystemUiInsets(),
        topBar = {
            IrioToolbar(
                title = { Text("外观") },
                backgroundColor = background,
                contentColor = content,
                leading = {
                    IrioBackButton(onBack)
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Theme[colors][secondaryColor], Theme[shapes][largeShape])
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("主题", color = Theme[colors][mutedColor], fontSize = 13.sp)
                DropdownMenu(
                    expanded = themeMenuOpen,
                    onExpandedChange = { themeMenuOpen = it },
                    alignment = DropdownMenuAlignment.Start,
                    panel = {
                        DropdownMenuPanel {
                            ThemePreference.entries.forEach { option ->
                                DropdownMenuItem(
                                    onClick = {
                                        ThemeStore.save(context, option)
                                        themeMenuOpen = false
                                    },
                                    leading = {
                                        if (themePreference == option) {
                                            Icon(
                                                imageVector = Icons.Rounded.Check,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        } else {
                                            Spacer(Modifier.size(16.dp))
                                        }
                                    },
                                ) { Text(option.label) }
                            }
                        }
                    },
                ) {
                    Button(
                        onClick = { themeMenuOpen = !themeMenuOpen },
                        modifier = Modifier.fillMaxWidth(),
                        style = ButtonStyle.Secondary,
                        shape = Theme[shapes][fieldShape],
                        buttonSize = ButtonSize.Regular,
                    ) {
                        Text(themePreference.label)
                        Spacer(Modifier.weight(1f))
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowDown,
                            contentDescription = "选择主题",
                        )
                    }
                }
                Text("强调色", color = Theme[colors][mutedColor], fontSize = 13.sp)
                DropdownMenu(
                    expanded = accentMenuOpen,
                    onExpandedChange = { accentMenuOpen = it },
                    alignment = DropdownMenuAlignment.Start,
                    panel = {
                        DropdownMenuPanel {
                            AccentColor.entries.forEach { option ->
                                DropdownMenuItem(
                                    onClick = {
                                        ThemeStore.saveAccent(context, option)
                                        accentMenuOpen = false
                                    },
                                    leading = {
                                        if (accent == option) {
                                            Icon(
                                                imageVector = Icons.Rounded.Check,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        } else {
                                            Spacer(Modifier.size(16.dp))
                                        }
                                    },
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        AccentSwatch(option.color)
                                        Spacer(Modifier.width(8.dp))
                                        Text(option.label)
                                    }
                                }
                            }
                        }
                    },
                ) {
                    Button(
                        onClick = { accentMenuOpen = !accentMenuOpen },
                        modifier = Modifier.fillMaxWidth(),
                        style = ButtonStyle.Secondary,
                        shape = Theme[shapes][fieldShape],
                        buttonSize = ButtonSize.Regular,
                    ) {
                        AccentSwatch(accent.color)
                        Spacer(Modifier.width(8.dp))
                        Text(accent.label)
                        Spacer(Modifier.weight(1f))
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowDown,
                            contentDescription = "选择强调色",
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("自动 AMOLED", color = Theme[colors][onPanelColor])
                    }
                    Switch(
                        checked = autoAmoled,
                        onCheckedChange = { enabled ->
                            ThemeStore.saveAutoAmoled(context, enabled)
                        },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("上下文圆环", color = Theme[colors][mutedColor])
                    }
                    Switch(
                        checked = false,
                        onCheckedChange = {},
                        enabled = false,
                    )
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Theme[colors][secondaryColor], Theme[shapes][largeShape])
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("公式", color = Theme[colors][mutedColor], fontSize = 13.sp)
                SettingsSlider(
                    label = "公式大小",
                    value = texScalePercent,
                    valueRange = 60..160,
                    onValueChange = { percent ->
                        texScalePercent = percent
                    },
                    valueLabel = { "$it%" },
                    onValueChangeFinished = {
                        ProviderStore.saveTexScale(context, texScalePercent / 100f)
                    },
                )
            }
        }
    }
}
