package com.irio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
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
import com.composables.ui.theme.onPrimaryColor
import com.composables.ui.theme.panelColor
import com.composables.ui.theme.primaryColor
import com.composables.ui.theme.secondaryColor
import com.composables.ui.theme.shapes
import com.composeunstyled.theme.Theme
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlin.math.roundToInt
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
    val headers = rememberProviderHeadersState(provider?.customHeaders.orEmpty())
    var protocol by remember { mutableStateOf(provider?.protocol ?: ProviderProtocol.CHAT_COMPLETIONS) }
    var page by rememberSaveable { mutableStateOf(ProviderDetailPage.Config) }
    val configScroll = rememberScrollState()
    val modelScroll = rememberScrollState()
    val hazeState = rememberHazeState()
    var tabOverlayHeightPx by remember { mutableIntStateOf(0) }
    val tabContentPadding = with(LocalDensity.current) { tabOverlayHeightPx.toDp() }

    val capsulePx = remember { mutableIntStateOf(0) }
    val capsuleHeight = with(LocalDensity.current) { capsulePx.intValue.toDp() }
    Box(Modifier.fillMaxSize().background(background)) {
        if (provider == null) {
            Text(
                tr("找不到这个提供商"),
                modifier = Modifier.padding(top = capsuleHeight).padding(16.dp),
            )
        } else {
            Box(Modifier.fillMaxSize()) {
                AnimatedContent(
                    targetState = page,
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds(),
                        contentAlignment = Alignment.TopStart,
                        transitionSpec = {
                            val forward = targetState.ordinal > initialState.ordinal
                            val slide = tween<IntOffset>(ProviderPageAnimMillis, easing = FastOutSlowInEasing)
                            val fade = tween<Float>(ProviderPageAnimMillis, easing = FastOutSlowInEasing)
                            (
                                slideInHorizontally(slide) { full -> if (forward) full else -full } +
                                    fadeIn(fade)
                                ) togetherWith (
                                slideOutHorizontally(slide) { full -> if (forward) -full else full } +
                                    fadeOut(fade)
                                ) using SizeTransform(clip = true)
                        },
                        label = "providerDetailPage",
                    ) { current ->
                        when (current) {
                            ProviderDetailPage.Config -> ProviderConfigPage(
                                name = name,
                                endpoint = endpoint,
                                apiKey = apiKey,
                                headers = headers,
                                protocol = protocol,
                                protocolMenuOpen = protocolMenuOpen,
                                onProtocolMenuExpanded = { protocolMenuOpen = it },
                                onProtocol = { protocol = it },
                                formError = formError,
                                saving = saving || refreshing,
                                onSave = save@{
                                    if (saving || refreshing) return@save
                                    val currentProvider = provider ?: return@save
                                    val customHeaders = headers.validatedHeaders() ?: return@save
                                    val updated = currentProvider.copy(
                                        name = name.text.toString().trim(),
                                        endpointUrl = endpoint.text.toString().trim(),
                                        apiKey = apiKey.text.toString(),
                                        protocol = protocol,
                                        customHeaders = customHeaders,
                                    )
                                    if (updated.name.isBlank() || updated.endpointUrl.isBlank()) {
                                        return@save
                                    }
                                    saving = true
                                    formError = null
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            ProviderApi.fetchModels(updated)
                                        }
                                        saving = false
                                        if (result.error == null) {
                                            refreshDiff = modelRefreshDiff(currentProvider.models, result.models)
                                            ProviderStore.saveProvider(context, updated.copy(models = result.models))
                                            provider = ProviderStore.loadProvider(context, currentProvider.id)
                                        } else {
                                            formError = result.error
                                        }
                                    }
                                },
                                onDelete = { deleteDialogOpen = true },
                                scroll = configScroll,
                                hazeState = hazeState,
                                capsuleHeight = capsuleHeight,
                                contentPaddingBottom = tabContentPadding,
                            )
                            ProviderDetailPage.Models -> ProviderModelsPage(
                                provider = provider!!,
                                refreshing = refreshing || saving,
                                formError = formError,
                                refreshDiff = refreshDiff,
                                onRefresh = refresh@{
                                    val currentProvider = provider ?: return@refresh
                                    if (refreshing || saving) return@refresh
                                    refreshing = true
                                    formError = null
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            ProviderApi.fetchModels(currentProvider)
                                        }
                                        refreshing = false
                                        if (result.error == null) {
                                            refreshDiff = modelRefreshDiff(currentProvider.models, result.models)
                                            ProviderStore.saveProvider(context, currentProvider.copy(models = result.models))
                                            provider = ProviderStore.loadProvider(context, currentProvider.id)
                                        } else {
                                            formError = result.error
                                        }
                                    }
                                },
                                onModelCheckedChange = toggle@{ model, on ->
                                    val currentProvider = provider ?: return@toggle
                                    val disabled = if (on) {
                                        currentProvider.disabledModels - model
                                    } else {
                                        currentProvider.disabledModels + model
                                    }
                                    provider = currentProvider.copy(disabledModels = disabled)
                                    val ticket = disabledSaveTicket.incrementAndGet()
                                    val id = currentProvider.id
                                    scope.launch(Dispatchers.IO) {
                                        synchronized(disabledSaveLock) {
                                            if (ticket != disabledSaveTicket.get()) return@synchronized
                                            ProviderStore.saveDisabledModels(context, id, disabled)
                                        }
                                    }
                                },
                                scroll = modelScroll,
                                hazeState = hazeState,
                                capsuleHeight = capsuleHeight,
                                contentPaddingBottom = tabContentPadding,
                            )
                        }
                    }
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .zIndex(1f)
                        .onSizeChanged { tabOverlayHeightPx = it.height }
                        .hazeBlur(
                            input = HazeInput.Sources(hazeState),
                            style = chatComposerHazeStyle(),
                        ),
                ) {
                    Spacer(Modifier.height(ChatComposerHazeBleed))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(
                                WindowInsets.navigationBars.only(WindowInsetsSides.Bottom),
                            ),
                    ) {
                        ProviderDetailTabs(
                            selected = page,
                            onSelect = { next ->
                                if (next != ProviderDetailPage.Config) protocolMenuOpen = false
                                page = next
                            },
                        )
                    }
                }
            }
        }
        SettingsTitleCapsule(
            title = provider?.name ?: tr("提供商"),
            onBack = onBack,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .zIndex(2f),
            onHeightChanged = { capsulePx.intValue = it },
        )
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

private enum class ProviderDetailPage(val label: String) {
    Config("配置"),
    Models("模型"),
}

private const val ProviderPageAnimMillis = 320
private val RefreshModelGap = 8.dp

@Composable
private fun ProviderConfigPage(
    name: TextFieldState,
    endpoint: TextFieldState,
    apiKey: TextFieldState,
    headers: ProviderHeadersState,
    protocol: ProviderProtocol,
    protocolMenuOpen: Boolean,
    onProtocolMenuExpanded: (Boolean) -> Unit,
    onProtocol: (ProviderProtocol) -> Unit,
    formError: String?,
    saving: Boolean,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    scroll: ScrollState,
    hazeState: HazeState,
    capsuleHeight: Dp,
    contentPaddingBottom: Dp,
) {
    var hazePx by remember { mutableIntStateOf(0) }
    val topInset = with(LocalDensity.current) { hazePx.toDp() }
    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(hazeState)
                .verticalScroll(scroll)
                .padding(top = topInset, bottom = contentPaddingBottom)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ProviderField(tr("名称"), name, KeyboardType.Text, readOnly = saving, trailingSpace = false)
            ProtocolPicker(
                protocol = protocol,
                expanded = protocolMenuOpen,
                onExpandedChange = onProtocolMenuExpanded,
                onSelect = onProtocol,
                enabled = !saving,
            )
            ProviderField(tr("端点 URL"), endpoint, KeyboardType.Uri, readOnly = saving, trailingSpace = false)
            ProviderField(tr("密钥"), apiKey, KeyboardType.Password, mask = true, readOnly = saving, trailingSpace = false)
            ProviderHeadersEditor(headers, enabled = !saving)
            if (formError != null) {
                Text(formError)
            }
            Button(
                onClick = onSave,
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
                style = ButtonStyle.Primary,
            ) {
                Text(if (saving) tr("保存中") else tr("保存"))
            }
            Button(
                onClick = onDelete,
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
                style = ButtonStyle.Destructive,
            ) {
                Text(tr("删除提供商"))
            }
        }
        ProviderTopHaze(hazeState, onHeightChanged = { hazePx = it }) {
            Spacer(Modifier.height(capsuleHeight))
        }
    }
}

@Composable
private fun ProviderModelsPage(
    provider: ProviderConfig,
    refreshing: Boolean,
    formError: String?,
    refreshDiff: ModelRefreshDiff,
    onRefresh: () -> Unit,
    onModelCheckedChange: (String, Boolean) -> Unit,
    scroll: ScrollState,
    hazeState: HazeState,
    capsuleHeight: Dp,
    contentPaddingBottom: Dp,
) {
    val showRemoved = ThemeStore.modelDiff
    var hazePx by remember { mutableIntStateOf(0) }
    val hazeHeight = with(LocalDensity.current) { hazePx.toDp() }
    val listTop = (hazeHeight - ChatToolbarHazeBleed + RefreshModelGap).coerceAtLeast(0.dp)
    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(hazeState)
                .verticalScroll(scroll)
                .padding(top = listTop, bottom = contentPaddingBottom)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (formError != null) {
                Text(formError)
            }
            if (provider.models.isEmpty() && (!showRemoved || refreshDiff.removed.isEmpty())) {
                Text(tr("还没有模型"))
            } else {
                modelDiffRows(provider.models, refreshDiff, showRemoved).forEach { entry ->
                    val removed = entry.kind == ModelDiffKind.Removed
                    ModelEnabledRow(
                        model = entry.model,
                        enabled = !removed && entry.model !in provider.disabledModels,
                        onCheckedChange = { on ->
                            if (removed) return@ModelEnabledRow
                            onModelCheckedChange(entry.model, on)
                        },
                        diff = entry.kind,
                    )
                }
            }
        }
        ProviderTopHaze(hazeState, onHeightChanged = { hazePx = it }) {
            Spacer(Modifier.height(capsuleHeight))
            Button(
                onClick = onRefresh,
                enabled = !refreshing,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = RefreshModelGap),
                style = ButtonStyle.Secondary,
            ) {
                Text(if (refreshing) tr("刷新中") else tr("刷新模型"))
            }
        }
    }
}

@Composable
private fun ProviderTopHaze(
    hazeState: HazeState,
    onHeightChanged: (Int) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(1f)
            .onSizeChanged { onHeightChanged(it.height) }
            .hazeBlur(
                input = HazeInput.Sources(hazeState),
                style = chatToolbarHazeStyle(),
            ),
    ) {
        content()
        Spacer(Modifier.height(ChatToolbarHazeBleed))
    }
}

@Composable
private fun ProviderDetailTabs(
    selected: ProviderDetailPage,
    onSelect: (ProviderDetailPage) -> Unit,
) {
    val pages = ProviderDetailPage.entries
    val progress by animateFloatAsState(
        targetValue = pages.indexOf(selected).toFloat(),
        animationSpec = tween(ProviderPageAnimMillis, easing = FastOutSlowInEasing),
        label = "providerTab",
    )
    val background = Theme[colors][backgroundColor]
    // 黑色强调色在深底上用前景色当选中胶囊，避免黑块贴在深色轨道上消失。
    val blackOnDark = ThemeStore.accent == AccentColor.BLACK && background.luminance() < 0.5f
    val selectedFill = if (blackOnDark) Theme[colors][onBackgroundColor] else Theme[colors][primaryColor]
    val selectedInk = if (blackOnDark) background else Theme[colors][onPrimaryColor]
    val idleInk = Theme[colors][onBackgroundColor]
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 4.dp, bottom = 12.dp)
            .clip(ToolbarCapsuleShape)
            .background(Theme[colors][secondaryColor])
            .padding(4.dp),
    ) {
        Layout(
            modifier = Modifier.fillMaxWidth(),
            content = {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(ToolbarCapsuleShape)
                        .background(selectedFill),
                )
                pages.forEach { page ->
                    val ink by animateColorAsState(
                        if (page == selected) selectedInk else idleInk,
                        tween(ProviderPageAnimMillis, easing = FastOutSlowInEasing),
                        label = "providerTabInk",
                    )
                    Box(
                        modifier = Modifier
                            .heightIn(min = 44.dp)
                            .clip(ToolbarCapsuleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onSelect(page) },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(tr(page.label), color = ink)
                    }
                }
            },
        ) { measurables, constraints ->
            val count = measurables.size - 1
            val width = constraints.maxWidth
            val segment = width / count
            val labelConstraints = constraints.copy(
                minWidth = segment,
                maxWidth = segment,
                minHeight = 0,
            )
            val labels = Array(count) { index ->
                measurables[index + 1].measure(labelConstraints)
            }
            val height = labels.maxOf { it.height }
            val indicator = measurables[0].measure(Constraints.fixed(segment, height))
            layout(width, height) {
                indicator.placeRelative((progress * segment).roundToInt(), 0)
                labels.forEachIndexed { index, label ->
                    label.placeRelative(index * segment, (height - label.height) / 2)
                }
            }
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
    val highlight = ThemeStore.modelDiff
    val background = when (diff) {
        ModelDiffKind.Removed -> if (highlight) DiffRemovedColor.copy(alpha = DIFF_FILL_ALPHA) else card
        ModelDiffKind.Added -> if (highlight) DiffAddedColor.copy(alpha = DIFF_FILL_ALPHA) else card
        ModelDiffKind.Unchanged -> card
    }
    val interactive = diff != ModelDiffKind.Removed
    Row(
        modifier = Modifier
            .settingsRow()
            .background(background, Theme[shapes][largeShape])
            .padding(start = 16.dp, end = 8.dp, top = SettingsRowInset, bottom = SettingsRowInset),
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
