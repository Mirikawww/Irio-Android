package com.irio

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.composables.ui.components.Button
import com.composables.ui.components.ButtonSize
import com.composables.ui.components.ButtonStyle
import com.composables.ui.components.DropdownMenu
import com.composables.ui.components.DropdownMenuAlignment
import com.composables.ui.components.DropdownMenuItem
import com.irio.ScaledDropdownMenuPanel as DropdownMenuPanel
import com.composables.ui.components.Icon
import com.composables.ui.components.IconButton
import com.composables.ui.components.Text
import com.composables.ui.components.Switch
import com.composables.ui.components.TextField
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

class ModelSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setIrioContent {
            ModelSettingsScreen(onBack = { finish() })
        }
    }
}

@Composable
internal fun ModelSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val background = Theme[colors][backgroundColor]
    val content = Theme[colors][onBackgroundColor]
    var maxRounds by remember { mutableIntStateOf(ToolStore.loadMaxRounds(context)) }
    var modelsDevReasoning by remember { mutableStateOf(ProviderStore.loadModelsDevReasoning(context)) }
    var imageGenModel by remember { mutableStateOf(ToolStore.loadImageGenModel(context)) }
    var imageGenMenuOpen by remember { mutableStateOf(false) }
    var titleModel by remember { mutableStateOf(ToolStore.loadTitleModel(context)) }
    var messageTimestamps by remember { mutableStateOf(ToolStore.loadMessageTimestamps(context)) }
    var smartToolHints by remember { mutableStateOf(ToolStore.loadSmartToolHints(context)) }
    var subagentsEnabled by remember { mutableStateOf(ToolStore.loadSubagents(context)) }
    var subagentModel by remember { mutableStateOf(ToolStore.loadSubagentModel(context)) }
    var showSmartHintNotice by remember { mutableStateOf(false) }
    var smartHintNoticeRestartKey by remember { mutableIntStateOf(0) }
    var contextCompression by remember { mutableStateOf(ToolStore.loadContextCompression(context)) }
    var compressionTrigger by remember { mutableIntStateOf(ToolStore.loadCompressionTrigger(context)) }
    var compressionTarget by remember { mutableIntStateOf(ToolStore.loadCompressionTarget(context)) }
    var visualizeContext by remember { mutableStateOf(ToolStore.loadVisualizeContext(context)) }
    var compressionModel by remember { mutableStateOf(ToolStore.loadCompressionModel(context)) }

    val currentMessageTimestamps by rememberUpdatedState(messageTimestamps)
    val currentContextCompression by rememberUpdatedState(contextCompression)
    val currentVisualizeContext by rememberUpdatedState(visualizeContext)
    val currentCompressionModel by rememberUpdatedState(compressionModel)
    val lifecycleOwner = LocalLifecycleOwner.current

    fun persistCurrent() {
        ToolStore.saveMessageTimestamps(context, currentMessageTimestamps)
        ToolStore.saveContextCompression(context, currentContextCompression)
        ToolStore.saveVisualizeContext(context, currentVisualizeContext)
        ToolStore.saveCompressionModel(context, currentCompressionModel)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) persistCurrent()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            persistCurrent()
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DismissComposerFocusWhenImeHides()
    SettingsHazeScaffold(
        title = tr("模型"),
        onBack = onBack,
    ) { topInset, bottomInset ->
        Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = bottomInset)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .padding(top = topInset)
                .padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsSectionCard {
                ListRowButton(
                    searchId = "model.tools",
                    label = tr("工具"),
                    labelFontWeight = FontWeight.Normal,
                    onClick = { context.startActivity(Intent(context, ToolSettingsActivity::class.java)) },
                    style = ButtonStyle.Ghost,
                    contentPadding = PaddingValues(horizontal = 0.dp),
                    trailing = {
                        Icon(
                            imageVector = IrioIcons.ChevronRight,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = Theme[colors][onPanelColor],
                        )
                    },
                )
                SettingsSlider(
                    modifier = Modifier.settingsSearchAnchor("model.rounds"),
                    label = tr("单轮工具调用"),
                    value = maxRounds,
                    valueRange = ToolStore.MAX_ROUNDS_MIN..ToolStore.MAX_ROUNDS_MAX,
                    onValueChange = {
                        maxRounds = it
                        ToolStore.saveMaxRounds(context, it)
                    },
                    valueLabel = { if (it == ToolStore.MAX_ROUNDS_MIN) tr("无限") else it.toString() },
                )
                Row(
                    modifier = Modifier.settingsSearchAnchor("model.smart", edge = SettingsSearchEdge.Bottom).settingsSwitchRow(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tr("更智能的工具调用提示"),
                        color = Theme[colors][onBackgroundColor],
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = smartToolHints,
                        onCheckedChange = { enabled ->
                            smartToolHints = enabled
                            ToolStore.saveSmartToolHints(context, enabled)
                            if (!enabled) {
                                showSmartHintNotice = false
                            } else if (ThemeStore.mergeToolCalls) {
                                ThemeStore.saveMergeToolCalls(context, false)
                                smartHintNoticeRestartKey++
                                showSmartHintNotice = true
                            }
                        },
                    )
                }
            }
            SettingsSectionCard {
                SettingsSwitchRow(
                    modifier = Modifier.settingsSearchAnchor("model.subagents", edge = SettingsSearchEdge.Top),
                    label = tr("子智能体"),
                    checked = subagentsEnabled,
                    onCheckedChange = { enabled ->
                        subagentsEnabled = enabled
                        ToolStore.saveSubagents(context, enabled)
                    },
                )
                TitleModelPicker(
                    selected = subagentModel,
                    onSelect = { model ->
                        subagentModel = model
                        ToolStore.saveSubagentModel(context, model)
                    },
                    modifier = Modifier.settingsSearchAnchor("model.subagentModel", edge = SettingsSearchEdge.Bottom),
                    label = tr("子智能体默认模型"),
                    contentDescription = tr("选择子智能体默认模型"),
                )
            }
            SettingsSectionCard {
                ImageGenModelPicker(
                    selected = imageGenModel,
                    onSelect = { model ->
                        imageGenModel = model
                        ToolStore.saveImageGenModel(context, model)
                    },
                )
                TitleModelPicker(
                    selected = titleModel,
                    onSelect = { model ->
                        titleModel = model
                        ToolStore.saveTitleModel(context, model)
                    },
                    modifier = Modifier.settingsSearchAnchor("model.title", edge = SettingsSearchEdge.Bottom),
                )
            }
            SettingsSectionCard {
                SystemPromptRow(
                    onClick = {
                        context.startActivity(Intent(context, SystemPromptSettingsActivity::class.java))
                    },
                )
                SettingsSwitchRow(
                    modifier = Modifier.settingsSearchAnchor("model.timestamps", edge = SettingsSearchEdge.Bottom),
                    label = tr("消息时间戳"),
                    checked = messageTimestamps,
                    onCheckedChange = {
                        messageTimestamps = it
                        ToolStore.saveMessageTimestamps(context, it)
                    },
                )
            }
            SettingsSectionCard {
                Row(
                    modifier = Modifier.settingsSearchAnchor("model.compression", edge = SettingsSearchEdge.Top).settingsSwitchRow(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tr("上下文压缩"),
                        color = Theme[colors][onBackgroundColor],
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = contextCompression,
                        onCheckedChange = {
                            contextCompression = it
                            ToolStore.saveContextCompression(context, it)
                        },
                    )
                }
                TitleModelPicker(
                    selected = compressionModel,
                    onSelect = { model ->
                        compressionModel = model
                        ToolStore.saveCompressionModel(context, model)
                    },
                    modifier = Modifier.settingsSearchAnchor("model.compressionModel"),
                    label = tr("压缩模型"),
                    contentDescription = tr("选择压缩模型"),
                )
                SettingsSlider(
                    modifier = Modifier.settingsSearchAnchor("model.trigger"),
                    label = tr("触发时机"),
                    value = compressionTrigger,
                    valueRange = ToolStore.COMPRESSION_PERCENT_MIN..ToolStore.COMPRESSION_PERCENT_MAX,
                    valueLabel = { "$it%" },
                    onValueChange = {
                        val next = ToolStore.clampCompressionPercent(it)
                        compressionTrigger = next
                        ToolStore.saveCompressionTrigger(context, next)
                    },
                )
                SettingsSlider(
                    modifier = Modifier.settingsSearchAnchor("model.target"),
                    label = tr("压缩目标"),
                    value = compressionTarget,
                    valueRange = ToolStore.COMPRESSION_PERCENT_MIN..ToolStore.COMPRESSION_PERCENT_MAX,
                    valueLabel = { "$it%" },
                    onValueChange = {
                        val next = ToolStore.clampCompressionPercent(it)
                        compressionTarget = next
                        ToolStore.saveCompressionTarget(context, next)
                    },
                )
                SettingsSwitchRow(
                    modifier = Modifier.settingsSearchAnchor("model.visualize", edge = SettingsSearchEdge.Bottom),
                    label = tr("可视化上下文"),
                    checked = visualizeContext,
                    onCheckedChange = {
                        visualizeContext = it
                        ToolStore.saveVisualizeContext(context, it)
                    },
                )
            }
            SettingsSectionCard {
                Row(
                    modifier = Modifier.settingsSearchAnchor("model.reasoning", edge = SettingsSearchEdge.All).settingsSwitchRow(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tr("使用 OpenRouter 思考数据"),
                        color = Theme[colors][onBackgroundColor],
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = modelsDevReasoning,
                        onCheckedChange = {
                            modelsDevReasoning = it
                            ProviderStore.saveModelsDevReasoning(context, it)
                        },
                    )
                }
            }
        }
        IrioSnackbar(
            visible = showSmartHintNotice,
            message = tr("开启更智能的工具调用提示后，将自动关闭合并工具调用显示"),
            bottomInset = bottomInset,
            onDismiss = { showSmartHintNotice = false },
            restartKey = smartHintNoticeRestartKey,
        )
        }
    }

}

@Composable
private fun SystemPromptRow(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .settingsSearchAnchor("model.prompt", edge = SettingsSearchEdge.Top)
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .settingsPromptInset(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            tr("编辑系统提示词"),
            modifier = Modifier.weight(1f),
            color = Theme[colors][onPanelColor],
        )
        IconButton(
            onClick = onClick,
            style = ButtonStyle.Ghost,
            buttonSize = ButtonSize.Small,
            shape = ContinuousCapsule,
            modifier = Modifier.size(24.dp),
            interactionSource = interactionSource,
        ) {
            Icon(
                imageVector = IrioIcons.ChevronRight,
                contentDescription = tr("编辑系统提示词"),
                tint = Theme[colors][onPanelColor],
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** 图片生成工具的默认模型选择：聚合所有提供商的模型，按提供商分组展示。 */
@Composable
private fun ImageGenModelPicker(
    selected: String,
    onSelect: (String) -> Unit,
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    // 只列生图模型（models.dev modalities.output 含 image）；普通聊天模型不该被选为图片生成目标。
    val providers = remember { ProviderStore.loadProviders(context) }
        .filter { it.enabled }
        .map { provider ->
            provider.copy(models = provider.enabledModels().filter { model -> ModelCatalog.isImageModel(context, model) })
        }
        .filter { it.models.isNotEmpty() }
    val options = remember(providers) {
        buildList {
            providers.forEach { provider ->
                provider.models.forEach { model ->
                    add(provider to model)
                }
            }
        }
    }
    // 与主页模型选择器按钮一致：只显示模型名；提供商归属由菜单里的分组标签表达，
    // 不在按钮里拼「提供商 · 模型」，否则一行太长。
    val selectedLabel = options
        .firstOrNull { it.second == selected }
        ?.let { (_, model) -> ModelCatalog.displayName(context, model, true) }
        ?: tr("未配置")

    DropdownMenu(
        expanded = menuOpen,
        onExpandedChange = { menuOpen = it },
        modifier = Modifier.fillMaxWidth(),
        alignment = DropdownMenuAlignment.End,
        panel = {
            // 与主页模型选择器同款式：固定 200dp 宽面板 + 提供商分组小字标签 + 选中 Check。
            // 面板会对内容做 width(IntrinsicSize.Max) 固有测量，LazyColumn 不支持固有测量会直接崩溃，
            // 必须用支持固有测量的 Column + verticalScroll。
            DropdownMenuPanel(modifier = Modifier.width(200.dp)) {
                Column(
                    modifier = Modifier
                        .width(200.dp)
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    providers.forEachIndexed { providerIndex, provider ->
                        if (providerIndex > 0) Spacer(Modifier.height(10.dp))
                        // 提供商分组小字：与下方条目文本左对齐，与上一组之间留出空隙
                        Text(
                            provider.name,
                            modifier = Modifier
                                .padding(start = 36.dp)
                                .padding(top = 6.dp, bottom = 2.dp),
                            color = Theme[colors][mutedColor],
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        provider.models.forEach { model ->
                            DropdownMenuItem(
                                onClick = {
                                    onSelect(model)
                                    menuOpen = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                                leading = {
                                    if (model == selected) {
                                        Icon(
                                            imageVector = IrioIcons.Check,
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
                                    ModelCatalog.displayName(context, model, true),
                                    color = Theme[colors][onPanelColor],
                                    fontSize = 15.5.sp,
                                )
                            }
                        }
                    }
                }
            }
        },
    ) {
        val interactionSource = remember { MutableInteractionSource() }
        Row(
            modifier = Modifier
                .settingsSearchAnchor("model.image", edge = SettingsSearchEdge.Top)
                .fillMaxWidth()
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = { menuOpen = !menuOpen },
                )
                .settingsDropdownInset(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(tr("图片生成模型"), color = Theme[colors][onPanelColor])
                Text(
                    selectedLabel,
                    color = Theme[colors][mutedColor],
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = { menuOpen = !menuOpen },
                style = ButtonStyle.Ghost,
                buttonSize = ButtonSize.Small,
                shape = ContinuousCapsule,
                modifier = Modifier.size(32.dp),
                interactionSource = interactionSource,
            ) {
                Icon(
                    imageVector = IrioIcons.ChevronDown,
                    contentDescription = tr("选择图片生成模型"),
                    tint = Theme[colors][onPanelColor],
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** 标题生成模型选择：列非图片模型；空选 = 跟随当前对话模型（当前是图片模型时用上次对话模型）。 */
@Composable
private fun TitleModelPicker(
    selected: String,
    onSelect: (String) -> Unit,
    label: String = tr("标题生成模型"),
    contentDescription: String = tr("选择标题生成模型"),
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    val providers = remember { ProviderStore.loadProviders(context) }
        .filter { it.enabled }
        .map { provider ->
            provider.copy(models = provider.enabledModels().filterNot { model -> ModelCatalog.isImageModel(context, model) })
        }
        .filter { it.models.isNotEmpty() }
    val options = remember(providers) {
        buildList {
            providers.forEach { provider ->
                provider.models.forEach { model ->
                    add(provider to model)
                }
            }
        }
    }
    val selectedLabel = options
        .firstOrNull { it.second == selected }
        ?.let { (_, model) -> ModelCatalog.displayName(context, model, true) }
        ?: tr("跟随对话模型")

    DropdownMenu(
        expanded = menuOpen,
        onExpandedChange = { menuOpen = it },
        modifier = modifier.fillMaxWidth(),
        alignment = DropdownMenuAlignment.End,
        // 与主页模型选择器同款式：固定 200dp 宽面板 + 提供商分组小字标签 + 选中 Check。
        // 面板会对内容做 width(IntrinsicSize.Max) 固有测量，LazyColumn 不支持固有测量会直接崩溃，
        // 必须用支持固有测量的 Column + verticalScroll。
        panel = {
            DropdownMenuPanel(modifier = Modifier.width(200.dp)) {
                Column(
                    modifier = Modifier
                        .width(200.dp)
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    DropdownMenuItem(
                        onClick = {
                            onSelect("")
                            menuOpen = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        leading = {
                            if (selected.isBlank()) {
                                Icon(
                                    imageVector = IrioIcons.Check,
                                    contentDescription = null,
                                    tint = Theme[colors][onPanelColor],
                                    modifier = Modifier.size(16.dp),
                                )
                            } else {
                                Spacer(Modifier.size(16.dp))
                            }
                        },
                    ) {
                        Text(tr("跟随对话模型"), color = Theme[colors][onPanelColor], fontSize = 15.5.sp)
                    }
                    providers.forEachIndexed { providerIndex, provider ->
                        if (providerIndex > 0) Spacer(Modifier.height(10.dp))
                        Text(
                            provider.name,
                            modifier = Modifier
                                .padding(start = 36.dp)
                                .padding(top = 6.dp, bottom = 2.dp),
                            color = Theme[colors][mutedColor],
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        provider.models.forEach { model ->
                            DropdownMenuItem(
                                onClick = {
                                    onSelect(model)
                                    menuOpen = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                                leading = {
                                    if (model == selected) {
                                        Icon(
                                            imageVector = IrioIcons.Check,
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
                                    ModelCatalog.displayName(context, model, true),
                                    color = Theme[colors][onPanelColor],
                                    fontSize = 15.5.sp,
                                )
                            }
                        }
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
                    onClick = { menuOpen = !menuOpen },
                )
                .settingsDropdownInset(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, color = Theme[colors][onPanelColor])
                Text(
                    selectedLabel,
                    color = Theme[colors][mutedColor],
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = { menuOpen = !menuOpen },
                style = ButtonStyle.Ghost,
                buttonSize = ButtonSize.Small,
                shape = ContinuousCapsule,
                modifier = Modifier.size(32.dp),
                interactionSource = interactionSource,
            ) {
                Icon(
                    imageVector = IrioIcons.ChevronDown,
                    contentDescription = contentDescription,
                    tint = Theme[colors][onPanelColor],
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.settingsSwitchRow(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            color = if (enabled) Theme[colors][onBackgroundColor] else Theme[colors][mutedColor],
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

@Composable
internal fun SettingsSectionCard(
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Theme[colors][secondaryColor], Theme[shapes][largeShape])
            .padding(horizontal = 16.dp, vertical = SettingsCardInset),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        content()
    }
}
