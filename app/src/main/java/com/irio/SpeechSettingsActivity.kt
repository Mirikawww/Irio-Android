package com.irio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.ui.components.ButtonSize
import com.composables.ui.components.ButtonStyle
import com.composables.ui.components.DropdownMenu
import com.composables.ui.components.DropdownMenuAlignment
import com.composables.ui.components.DropdownMenuItem
import com.composables.ui.components.DropdownMenuPanel
import com.composables.ui.components.DropdownMenuPanelContentScope
import com.composables.ui.components.Icon
import com.composables.ui.components.IconButton
import com.composables.ui.components.Text
import com.composables.ui.theme.colors
import com.composables.ui.theme.largeShape
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.onPanelColor
import com.composables.ui.theme.secondaryColor
import com.composables.ui.theme.shapes
import com.composeunstyled.theme.Theme

class SpeechSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setIrioContent {
            SpeechSettingsScreen(onBack = { finish() })
        }
    }
}

@Composable
private fun SpeechSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var voiceId by remember { mutableStateOf(SpeechStore.loadVoiceId(context)) }
    var voices by remember { mutableStateOf<List<SpeechVoice>?>(null) }
    var missing by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val listed = SpeechPlayer.loadVoices(context)
        if (listed == null) missing = true else voices = listed
    }
    val selected = when {
        missing -> tr("未找到系统朗读引擎")
        voices == null -> tr("正在读取音色")
        voiceId.isBlank() -> tr("系统默认")
        else -> voices.orEmpty().firstOrNull { it.id == voiceId }?.menuLabel() ?: voiceId
    }
    val canPick = !missing && voices != null

    SettingsHazeScaffold(
        title = "TTS/STT",
        onBack = onBack,
    ) { topInset, bottomInset ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = bottomInset)
                .verticalScroll(rememberScrollState())
                .padding(top = topInset)
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            VoiceMenu(
                expanded = menuOpen && canPick,
                onExpandedChange = { if (canPick) menuOpen = it },
                selected = selected,
                voiceId = voiceId,
                voices = voices.orEmpty(),
                enabled = canPick,
                onSelect = { id ->
                    voiceId = id
                    SpeechStore.saveVoiceId(context, id)
                    menuOpen = false
                },
            )
        }
    }
}

@Composable
private fun VoiceMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    selected: String,
    voiceId: String,
    voices: List<SpeechVoice>,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = Modifier.fillMaxWidth(),
        alignment = DropdownMenuAlignment.End,
        panel = {
            DropdownMenuPanel(modifier = Modifier.width(280.dp)) {
                Column(
                    modifier = Modifier
                        .width(280.dp)
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    VoiceItem(
                        label = tr("系统默认"),
                        selected = voiceId.isBlank(),
                        onClick = { onSelect("") },
                    )
                    voices.forEach { voice ->
                        VoiceItem(
                            label = voice.menuLabel(),
                            selected = voice.id == voiceId,
                            onClick = { onSelect(voice.id) },
                        )
                    }
                }
            }
        },
    ) {
        val interactionSource = remember { MutableInteractionSource() }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Theme[colors][secondaryColor], Theme[shapes][largeShape])
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    onClick = { onExpandedChange(!expanded) },
                )
                .padding(start = 16.dp, end = 16.dp, top = 10.5.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(tr("朗读音色"), color = Theme[colors][onPanelColor])
                Text(
                    selected,
                    color = Theme[colors][mutedColor],
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = { onExpandedChange(!expanded) },
                enabled = enabled,
                style = ButtonStyle.Ghost,
                buttonSize = ButtonSize.Small,
                shape = ContinuousCapsule,
                modifier = Modifier.size(32.dp),
                interactionSource = interactionSource,
            ) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = tr("选择音色"),
                    tint = Theme[colors][onPanelColor],
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun DropdownMenuPanelContentScope.VoiceItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        leading = {
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = Theme[colors][onPanelColor],
                    modifier = Modifier.size(16.dp),
                )
            } else {
                Spacer(Modifier.size(16.dp))
            }
        },
    ) {
        Text(
            label,
            color = Theme[colors][onPanelColor],
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun SpeechVoice.menuLabel(): String =
    if (online) "$label · ${tr("在线")}" else label
