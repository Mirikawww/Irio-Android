package com.irio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import java.util.Locale
import kotlin.math.roundToInt

class SpeechSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setIrioContent {
            SpeechSettingsScreen(onBack = { finish() })
        }
    }
}

@Composable
internal fun SpeechSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var voiceId by remember { mutableStateOf(SpeechStore.loadVoiceId(context)) }
    var speechRate by remember { mutableFloatStateOf(SpeechStore.loadSpeechRate(context)) }
    var voices by remember { mutableStateOf<List<SpeechVoice>?>(null) }
    var missing by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val savedSttProvider = remember {
        SttStore.loadActiveProvider(context) ?: SttStore.loadProviders(context).firstOrNull()
    }
    var providerKind by remember { mutableStateOf(savedSttProvider?.kind ?: SttProviderKind.GROQ) }
    var providerMenuOpen by remember { mutableStateOf(false) }
    var providerModel by remember {
        mutableStateOf(savedSttProvider?.model ?: SttStore.defaultModel(SttProviderKind.GROQ))
    }
    var providerModelMenuOpen by remember { mutableStateOf(false) }
    var providerLanguage by remember { mutableStateOf(savedSttProvider?.language ?: "en") }
    var providerLanguageMenuOpen by remember { mutableStateOf(false) }
    val providerApiKey = rememberTextFieldState(savedSttProvider?.apiKey.orEmpty())
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) {
        val listed = SpeechPlayer.loadVoices(context)
        if (listed == null) missing = true else voices = listed
    }
    fun persistSttProvider() {
        val saved = SttStore.loadProviders(context).firstOrNull { it.kind == providerKind }
        val config = SttProviderConfig(
            id = saved?.id ?: java.util.UUID.randomUUID().toString(),
            kind = providerKind,
            name = providerKind.label,
            apiKey = providerApiKey.text.toString(),
            model = providerModel,
            enabled = saved?.enabled ?: true,
            language = providerLanguage,
        )
        SttStore.saveProvider(
            context,
            config,
        )
        if (config.enabled) SttStore.saveActiveProvider(context, config.id)
    }
    fun switchProvider(next: SttProviderKind) {
        if (next == providerKind) {
            providerMenuOpen = false
            return
        }
        persistSttProvider()
        providerKind = next
        providerMenuOpen = false
        val saved = SttStore.loadProviders(context).firstOrNull { it.kind == next }
        providerApiKey.edit { replace(0, length, saved?.apiKey.orEmpty()) }
        providerModel = saved?.model ?: SttStore.defaultModel(next)
        providerLanguage = saved?.language ?: "en"
    }
    DisposableEffect(lifecycleOwner, providerApiKey) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) persistSttProvider()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            persistSttProvider()
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    val selected = when {
        missing -> tr("未找到系统朗读引擎")
        voices == null -> tr("正在读取音色")
        voiceId.isBlank() -> tr("系统默认")
        else -> voices.orEmpty().firstOrNull { it.id == voiceId }?.menuLabel() ?: voiceId
    }
    val canPick = !missing && voices != null

    DismissComposerFocusWhenImeHides()
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
            Spacer(Modifier.height(12.dp))
            SttSettingsSectionCard {
                SettingsSlider(
                    label = tr("朗读速度"),
                    value = (speechRate * 10).roundToInt(),
                    valueRange = (SpeechStore.MIN_SPEECH_RATE * 10).toInt()..(SpeechStore.MAX_SPEECH_RATE * 10).toInt(),
                    onValueChange = {
                        speechRate = it / 10f
                        SpeechStore.saveSpeechRate(context, speechRate)
                    },
                    valueLabel = { String.format(Locale.getDefault(), "%.1f×", it / 10f) },
                )
            }
            Spacer(Modifier.height(16.dp))
            SttSettingsSectionCard {
                SttProviderMenu(
                    provider = providerKind,
                    expanded = providerMenuOpen,
                    onExpandedChange = { providerMenuOpen = it },
                    onSelect = ::switchProvider,
                )
                Column(Modifier.padding(vertical = 8.dp)) {
                    ProviderField(
                        label = tr("%s API 密钥").format(providerKind.label),
                        state = providerApiKey,
                        keyboardType = KeyboardType.Password,
                        mask = true,
                        trailingSpace = false,
                    )
                }
                SttModelMenu(
                    provider = providerKind,
                    model = providerModel,
                    expanded = providerModelMenuOpen,
                    onExpandedChange = { providerModelMenuOpen = it },
                    onSelect = {
                        providerModel = it
                        persistSttProvider()
                    },
                )
                if (providerKind == SttProviderKind.GLADIA && providerModel == "solaria-3") {
                    SttLanguageMenu(
                        language = providerLanguage,
                        expanded = providerLanguageMenuOpen,
                        onExpandedChange = { providerLanguageMenuOpen = it },
                        onSelect = {
                            providerLanguage = it
                            persistSttProvider()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SttSettingsSectionCard(
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Theme[colors][secondaryColor], Theme[shapes][largeShape])
            .padding(horizontal = 16.dp, vertical = SettingsCardInset),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) { content() }
}

@Composable
private fun SttProviderMenu(
    provider: SttProviderKind,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (SttProviderKind) -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = Modifier.fillMaxWidth(),
        alignment = DropdownMenuAlignment.End,
        panel = {
            DropdownMenuPanel {
                    SttProviderKind.entries.forEach { option ->
                        DropdownMenuItem(
                            onClick = { onSelect(option); onExpandedChange(false) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(option.label, color = Theme[colors][onPanelColor]) }
                }
            }
        },
    ) {
        val interactionSource = remember { MutableInteractionSource() }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = { onExpandedChange(!expanded) },
                )
                .settingsDropdownInset(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(tr("STT 提供商"), color = Theme[colors][onPanelColor])
                Text(provider.label, color = Theme[colors][mutedColor], fontSize = 13.sp)
            }
            IconButton(
                onClick = { onExpandedChange(!expanded) },
                style = ButtonStyle.Ghost,
                buttonSize = ButtonSize.Small,
                shape = ContinuousCapsule,
                modifier = Modifier.size(32.dp),
                interactionSource = interactionSource,
            ) {
                Icon(
                    Icons.Rounded.KeyboardArrowDown,
                    contentDescription = tr("选择提供商"),
                    modifier = Modifier.size(20.dp),
                    tint = Theme[colors][onPanelColor],
                )
            }
        }
    }
}

@Composable
private fun SttModelMenu(
    provider: SttProviderKind,
    model: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = Modifier.fillMaxWidth(),
        alignment = DropdownMenuAlignment.End,
        panel = {
            DropdownMenuPanel {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    SttStore.models(provider).forEach { option ->
                        DropdownMenuItem(
                            onClick = { onSelect(option); onExpandedChange(false) },
                            modifier = Modifier.fillMaxWidth(),
                            leading = {
                                if (option == model) Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                else Spacer(Modifier.size(16.dp))
                            },
                        ) { Text(option) }
                    }
                }
            }
        },
    ) {
        val interactionSource = remember { MutableInteractionSource() }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = { onExpandedChange(!expanded) },
                )
                .settingsDropdownInset(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(tr("模型"), color = Theme[colors][onPanelColor])
                Text(model, color = Theme[colors][mutedColor], fontSize = 13.sp)
            }
            IconButton(
                onClick = { onExpandedChange(!expanded) },
                style = ButtonStyle.Ghost,
                buttonSize = ButtonSize.Small,
                shape = ContinuousCapsule,
                modifier = Modifier.size(32.dp),
                interactionSource = interactionSource,
            ) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = tr("选择模型"),
                    modifier = Modifier.size(20.dp),
                    tint = Theme[colors][onPanelColor],
                )
            }
        }
    }
}

private val GladiaLanguages = listOf(
    "en" to "English",
    "fr" to "Français",
    "de" to "Deutsch",
    "es" to "Español",
    "it" to "Italiano",
)

@Composable
private fun SttLanguageMenu(
    language: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = Modifier.fillMaxWidth(),
        alignment = DropdownMenuAlignment.End,
        panel = {
            DropdownMenuPanel {
                GladiaLanguages.forEach { (code, label) ->
                    DropdownMenuItem(
                        onClick = { onSelect(code); onExpandedChange(false) },
                        modifier = Modifier.fillMaxWidth(),
                        leading = {
                            if (code == language) Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            else Spacer(Modifier.size(16.dp))
                        },
                    ) { Text(label) }
                }
            }
        },
    ) {
        val interactionSource = remember { MutableInteractionSource() }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = { onExpandedChange(!expanded) },
                )
                .settingsDropdownInset(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(tr("语言"), color = Theme[colors][onPanelColor])
                Text(GladiaLanguages.first { it.first == language }.second, color = Theme[colors][mutedColor], fontSize = 13.sp)
            }
            IconButton(
                onClick = { onExpandedChange(!expanded) },
                style = ButtonStyle.Ghost,
                buttonSize = ButtonSize.Small,
                shape = ContinuousCapsule,
                modifier = Modifier.size(32.dp),
                interactionSource = interactionSource,
            ) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = tr("选择语言"),
                    modifier = Modifier.size(20.dp),
                    tint = Theme[colors][onPanelColor],
                )
            }
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
