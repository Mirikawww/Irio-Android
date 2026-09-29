package com.irio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.composables.ui.components.Button
import com.composables.ui.components.ButtonStyle
import com.composables.ui.components.Icon
import com.composables.ui.components.IconButton
import com.composables.ui.components.Switch
import com.composables.ui.components.Text
import com.composables.ui.theme.backgroundColor
import com.composables.ui.theme.colors
import com.composables.ui.theme.largeShape
import com.composables.ui.theme.onBackgroundColor
import com.composables.ui.theme.onPanelColor
import com.composables.ui.theme.panelColor
import com.composables.ui.theme.secondaryColor
import com.composables.ui.theme.shapes
import com.composeunstyled.theme.Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class ProviderDetailActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        setIrioContent {
            ProviderDetailScreen(
                providerId = id,
                onBack = { finish() },
                onDeleted = { finish() },
            )
        }
    }

    companion object {
        const val EXTRA_ID = "provider_id"
    }
}

@Composable
private fun ProviderDetailScreen(
    providerId: String,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val disabledSaveTicket = remember { java.util.concurrent.atomic.AtomicInteger(0) }
    val disabledSaveLock = remember { Any() }
    val background = Theme[colors][backgroundColor]
    val content = Theme[colors][onBackgroundColor]
    var provider by remember { mutableStateOf(ProviderStore.loadProvider(context, providerId)) }
    var deleteDialogOpen by rememberSaveable { mutableStateOf(false) }
    var protocolMenuOpen by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var refreshDiff by remember { mutableStateOf(ModelRefreshDiff()) }
    var formError by rememberSaveable { mutableStateOf<String?>(null) }
    val name = rememberTextFieldState(provider?.name.orEmpty())
    val endpoint = rememberTextFieldState(provider?.endpointUrl.orEmpty())
    val apiKey = rememberTextFieldState(provider?.apiKey.orEmpty())
    var protocol by remember { mutableStateOf(provider?.protocol ?: ProviderProtocol.CHAT_COMPLETIONS) }

    Scaffold(
        containerColor = background,
        contentWindowInsets = irioSystemUiInsets(),
        topBar = {
            IrioToolbar(
                title = { Text(provider?.name ?: tr("提供商")) },
                backgroundColor = background,
                contentColor = content,
                leading = {
                    IconButton(
                        onClick = onBack,
                        style = ButtonStyle.Ghost,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = tr("返回"),
                            tint = content,
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (provider == null) {
                Text(tr("找不到这个提供商"))
            } else {
                ProviderField(tr("名称"), name, KeyboardType.Text, trailingSpace = false)
                ProtocolPicker(
                    protocol = protocol,
                    expanded = protocolMenuOpen,
                    onExpandedChange = { protocolMenuOpen = it },
                    onSelect = { protocol = it },
                )
                ProviderField(tr("端点 URL"), endpoint, KeyboardType.Uri, trailingSpace = false)
                ProviderField(tr("密钥"), apiKey, KeyboardType.Password, mask = true, trailingSpace = false)
                if (formError != null) {
                    Text(formError!!)
                }
                Button(
                    onClick = {
                        val current = provider ?: return@Button
                        val updated = current.copy(
                            name = name.text.toString().trim(),
                            endpointUrl = endpoint.text.toString().trim(),
                            apiKey = apiKey.text.toString(),
                            protocol = protocol,
                        )
                        if (updated.name.isBlank() || updated.endpointUrl.isBlank() || updated.apiKey.isBlank()) return@Button
                        saving = true
                        formError = null
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                ProviderApi.fetchModels(updated)
                            }
                            saving = false
                            if (result.error == null) {
                                refreshDiff = modelRefreshDiff(current.models, result.models)
                                ProviderStore.saveProvider(context, updated.copy(models = result.models))
                                provider = ProviderStore.loadProvider(context, current.id)
                            } else {
                                formError = result.error
                            }
                        }
                    },
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                    style = ButtonStyle.Primary,
                ) {
                    Text(if (saving) tr("保存中") else tr("保存"))
                }
                Button(
                    onClick = {
                        val current = provider ?: return@Button
                        if (refreshing) return@Button
                        refreshing = true
                        formError = null
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                ProviderApi.fetchModels(current)
                            }
                            refreshing = false
                            if (result.error == null) {
                                refreshDiff = modelRefreshDiff(current.models, result.models)
                                ProviderStore.saveProvider(context, current.copy(models = result.models))
                                provider = ProviderStore.loadProvider(context, current.id)
                            } else {
                                formError = result.error
                            }
                        }
                    },
                    enabled = !refreshing,
                    modifier = Modifier.fillMaxWidth(),
                    style = ButtonStyle.Secondary,
                ) {
                    Text(if (refreshing) tr("刷新中") else tr("刷新模型"))
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (provider!!.models.isEmpty() && refreshDiff.removed.isEmpty()) {
                        Text(tr("还没有模型"))
                    } else {
                        modelDiffRows(provider!!.models, refreshDiff).forEach { entry ->
                            val removed = entry.kind == ModelDiffKind.Removed
                            ModelEnabledRow(
                                model = entry.model,
                                enabled = !removed && entry.model !in provider!!.disabledModels,
                                onCheckedChange = { on ->
                                    if (removed) return@ModelEnabledRow
                                    val current = provider ?: return@ModelEnabledRow
                                    val disabled = if (on) {
                                        current.disabledModels - entry.model
                                    } else {
                                        current.disabledModels + entry.model
                                    }
                                    provider = current.copy(disabledModels = disabled)
                                    val ticket = disabledSaveTicket.incrementAndGet()
                                    val id = current.id
                                    scope.launch(Dispatchers.IO) {
                                        synchronized(disabledSaveLock) {
                                            if (ticket != disabledSaveTicket.get()) return@synchronized
                                            ProviderStore.saveDisabledModels(context, id, disabled)
                                        }
                                    }
                                },
                                diff = entry.kind,
                            )
                        }
                    }
                }
                Button(
                    onClick = { deleteDialogOpen = true },
                    modifier = Modifier.fillMaxWidth(),
                    style = ButtonStyle.Destructive,
                ) {
                    Text(tr("删除提供商"))
                }
            }
        }
    }

    IrioAlertDialog(
        visible = deleteDialogOpen,
        onDismissRequest = { deleteDialogOpen = false },
        backgroundColor = Theme[colors][panelColor],
        contentColor = Theme[colors][onPanelColor],
    ) {
        Column {
            DialogTitle(tr("删除提供商"))
            Spacer(Modifier.height(12.dp))
            Text(tr("确定要删除“%s”吗？相关模型和已选模型也会被清除。").format(provider?.name.orEmpty()))
            Spacer(Modifier.height(16.dp))
            DialogButtonRow(
                confirmLabel = tr("删除"),
                onConfirm = {
                    provider?.id?.let { ProviderStore.deleteProvider(context, it) }
                    deleteDialogOpen = false
                    onDeleted()
                },
                onCancel = { deleteDialogOpen = false },
                confirmStyle = ButtonStyle.Destructive,
            )
        }
    }
}

@Composable
private fun ModelEnabledRow(
    model: String,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    diff: ModelDiffKind = ModelDiffKind.Unchanged,
) {
    val card = Theme[colors][secondaryColor]
    val ink = Theme[colors][onBackgroundColor]
    val background = when (diff) {
        ModelDiffKind.Removed -> DiffRemovedColor.copy(alpha = DIFF_FILL_ALPHA)
        ModelDiffKind.Added -> DiffAddedColor.copy(alpha = DIFF_FILL_ALPHA)
        ModelDiffKind.Unchanged -> card
    }
    val interactive = diff != ModelDiffKind.Removed
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background, Theme[shapes][largeShape])
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            model,
            modifier = Modifier.weight(1f),
            color = ink,
        )
        Switch(
            checked = if (interactive) enabled else false,
            onCheckedChange = onCheckedChange,
            enabled = interactive,
        )
    }
}

private val DiffAddedColor = Color(0xFF22C55E)
private val DiffRemovedColor = Color(0xFFEF4444)
private const val DIFF_FILL_ALPHA = 0.28f
