package com.irio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.BaselineShift
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
import com.composables.ui.components.Switch
import com.composables.ui.components.Text
import com.composables.ui.components.TextField
import com.composables.ui.theme.backgroundColor
import com.composables.ui.theme.colors
import com.composables.ui.theme.fieldColor
import com.composables.ui.theme.fieldShape
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.onBackgroundColor
import com.composables.ui.theme.onPanelColor
import com.composables.ui.theme.panelColor
import com.composables.ui.theme.shapes
import com.composeunstyled.theme.Theme
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ProviderSettingsScreen(
    onBack: () -> Unit,
    onOpenProvider: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val pendingEnabled = remember { ConcurrentHashMap<String, Boolean>() }
    val enabledSaveLock = remember { Any() }
    val background = Theme[colors][backgroundColor]
    val content = Theme[colors][onBackgroundColor]
    var providers by remember { mutableStateOf(ProviderStore.loadProviders()) }
    var dialogOpen by rememberSaveable { mutableStateOf(false) }
    var saving by rememberSaveable { mutableStateOf(false) }
    var formError by rememberSaveable { mutableStateOf<String?>(null) }
    var protocol by rememberSaveable { mutableStateOf(ProviderProtocol.CHAT_COMPLETIONS) }
    var protocolMenuOpen by remember { mutableStateOf(false) }
    val name = rememberTextFieldState()
    val endpoint = rememberTextFieldState()
    val apiKey = rememberTextFieldState()

    fun reload() {
        providers = ProviderStore.loadProviders()
    }

    DisposableEffect(Unit) {
        val listener = { reload() }
        ProviderStore.addChangeListener(listener)
        onDispose { ProviderStore.removeChangeListener(listener) }
    }

    fun resetAddForm() {
        name.clearText()
        endpoint.clearText()
        protocol = ProviderProtocol.CHAT_COMPLETIONS
        apiKey.clearText()
        formError = null
        protocolMenuOpen = false
    }

    SettingsHazeScaffold(
        title = tr("提供商"),
        onBack = onBack,
    ) { topInset, bottomInset ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = bottomInset)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .padding(top = topInset)
                .padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            providers.forEach { provider ->
                ProviderEnabledRow(
                    name = provider.name,
                    enabled = provider.enabled,
                    onOpen = { onOpenProvider(provider.id) },
                    onCheckedChange = { on ->
                        providers = providers.map { current ->
                            if (current.id == provider.id) current.copy(enabled = on) else current
                        }
                        pendingEnabled[provider.id] = on
                        val id = provider.id
                        scope.launch(Dispatchers.IO) {
                            synchronized(enabledSaveLock) {
                                if (pendingEnabled[id] != on) return@synchronized
                                ProviderStore.saveProviderEnabled(id, on)
                            }
                        }
                    },
                )
            }
            ListRowButton(
                label = tr("添加提供商"),
                icon = Icons.Rounded.Add,
                onClick = {
                    resetAddForm()
                    dialogOpen = true
                },
            )
        }
    }

    IrioAlertDialog(
        visible = dialogOpen,
        onDismissRequest = { if (!saving) dialogOpen = false },
        backgroundColor = Theme[colors][panelColor],
        contentColor = Theme[colors][onPanelColor],
    ) {
        Column {
            DialogTitle(tr("添加提供商"))
            Spacer(Modifier.height(16.dp))
            ProviderField(tr("名称"), name, KeyboardType.Text)
            ProtocolPicker(
                protocol = protocol,
                expanded = protocolMenuOpen,
                onExpandedChange = { protocolMenuOpen = it },
                onSelect = { protocol = it },
            )
            Spacer(Modifier.height(12.dp))
            ProviderField(tr("端点 URL"), endpoint, KeyboardType.Uri)
            ProviderField(tr("密钥"), apiKey, KeyboardType.Password, mask = true)
            if (formError != null) {
                Text(formError!!)
                Spacer(Modifier.height(8.dp))
            }
            DialogButtonRow(
                confirmLabel = if (saving) tr("保存中") else tr("保存"),
                confirmEnabled = !saving &&
                    name.text.isNotBlank() &&
                    endpoint.text.toString().isNotBlank() &&
                    apiKey.text.isNotBlank(),
                onConfirm = {
                    val config = ProviderConfig(
                        id = UUID.randomUUID().toString(),
                        name = name.text.toString().trim(),
                        endpointUrl = endpoint.text.toString().trim(),
                        apiKey = apiKey.text.toString(),
                        protocol = protocol,
                    )
                    saving = true
                    formError = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            ProviderApi.fetchModels(config)
                        }
                        if (result.error == null) {
                            ProviderStore.saveProvider(config.copy(models = result.models))
                            ProviderStore.saveActiveProviderId(config.id)
                            reload()
                            dialogOpen = false
                        } else {
                            formError = result.error
                        }
                        saving = false
                    }
                },
                onCancel = { if (!saving) dialogOpen = false },
                cancelEnabled = !saving,
            )
        }
    }
}

@Composable
internal fun ProviderField(
    label: String,
    state: TextFieldState,
    keyboardType: KeyboardType,
    mask: Boolean = false,
    readOnly: Boolean = false,
    trailingSpace: Boolean = true,
) {
    TextField(
        state = state,
        modifier = Modifier.fillMaxWidth().then(rememberInputFieldBorder()),
        readOnly = readOnly,
        accessibilityLabel = label,
        placeholder = {
            Text(
                label,
                style = FieldTextStyle.copy(baselineShift = BaselineShift(0.12f)),
            )
        },
        textStyle = FieldTextStyle,
        lineHeight = 20.sp,
        fontSize = 16.sp,
        shape = Theme[shapes][fieldShape],
        contentPadding = PaddingValues(12.dp),
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = ImeAction.Next
        ),
        outputTransformation = if (mask) PasswordMask else null,
    )
    if (trailingSpace) Spacer(Modifier.height(12.dp))
}

@Composable
internal fun ProtocolPicker(
    protocol: ProviderProtocol,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (ProviderProtocol) -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = Modifier.fillMaxWidth(),
        alignment = DropdownMenuAlignment.End,
        panel = {
            DropdownMenuPanel {
                ProviderProtocol.entries.forEach { option ->
                    DropdownMenuItem(
                        onClick = {
                            onSelect(option)
                            onExpandedChange(false)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(tr(option.label), color = Theme[colors][onPanelColor]) }
                }
            }
        },
    ) {
        val interactionSource = remember { MutableInteractionSource() }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Theme[colors][fieldColor], Theme[shapes][fieldShape])
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = { onExpandedChange(!expanded) },
                )
                .padding(start = 12.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(tr("协议"), color = Theme[colors][onPanelColor])
                Text(tr(protocol.label), color = Theme[colors][mutedColor], fontSize = 13.sp)
            }
            IconButton(
                onClick = { onExpandedChange(!expanded) },
                style = ButtonStyle.Ghost,
                buttonSize = ButtonSize.Small,
                shape = CircleShape,
                modifier = Modifier.size(32.dp),
                interactionSource = interactionSource,
            ) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = tr("选择协议"),
                    tint = Theme[colors][onPanelColor],
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
internal fun DialogButtonRow(
    confirmLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    confirmEnabled: Boolean = true,
    cancelEnabled: Boolean = true,
    confirmStyle: ButtonStyle = ButtonStyle.Primary,
    cancelLabel: String = tr("取消"),
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = onCancel,
            enabled = cancelEnabled,
            modifier = Modifier.weight(1f),
            style = ButtonStyle.Secondary,
        ) { Text(cancelLabel) }
        Button(
            onClick = onConfirm,
            enabled = confirmEnabled,
            modifier = Modifier.weight(1f),
            style = confirmStyle,
        ) { Text(confirmLabel) }
    }
}

@Composable
private fun ProviderEnabledRow(
    name: String,
    enabled: Boolean,
    onOpen: () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListRowButton(
        label = name,
        onClick = onOpen,
        trailing = {
            Switch(checked = enabled, onCheckedChange = onCheckedChange)
        },
    )
}

internal object PasswordMask : OutputTransformation {
    override fun TextFieldBuffer.transformOutput() {
        if (length > 0) {
            replace(0, length, "•".repeat(length))
        }
    }
}

internal val FieldTextStyle = TextStyle(
    fontSize = 16.sp,
    lineHeight = 20.sp,
)

@Composable
internal fun DialogTitle(text: String) {
    Text(
        text,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
    )
}
