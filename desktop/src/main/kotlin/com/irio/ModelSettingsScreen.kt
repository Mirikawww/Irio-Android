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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
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
import com.composables.ui.components.TextField
import com.composables.ui.theme.backgroundColor
import com.composables.ui.theme.colors
import com.composables.ui.theme.fieldShape
import com.composables.ui.theme.largeShape
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.onBackgroundColor
import com.composables.ui.theme.onPanelColor
import com.composables.ui.theme.panelColor
import com.composables.ui.theme.secondaryColor
import com.composables.ui.theme.shapes
import com.composeunstyled.theme.Theme

@Composable
fun ModelSettingsScreen(onBack: () -> Unit) {
    val background = Theme[colors][backgroundColor]
    val content = Theme[colors][onBackgroundColor]
    var provider by remember { mutableStateOf(SearchStore.loadProvider()) }
    var providerMenuOpen by remember { mutableStateOf(false) }
    val apiKey = rememberTextFieldState(SearchStore.loadApiKey(provider))
    var maxResults by remember { mutableIntStateOf(SearchStore.loadMaxResults(provider)) }
    var maxRounds by remember { mutableIntStateOf(ToolStore.loadMaxRounds()) }
    var mergeModels by remember { mutableStateOf(ProviderStore.loadMergeModels()) }
    var imageGenModel by remember { mutableStateOf(ToolStore.loadImageGenModel()) }
    var titleModel by remember { mutableStateOf(ToolStore.loadTitleModel()) }
    var systemPromptDialogOpen by remember { mutableStateOf(false) }
    val systemPrompt = rememberTextFieldState(SystemPromptStore.load())
    val currentProvider by rememberUpdatedState(provider)
    val currentMaxResults by rememberUpdatedState(maxResults)
    val currentMaxRounds by rememberUpdatedState(maxRounds)
    val currentMergeModels by rememberUpdatedState(mergeModels)

    fun persistCurrent() {
        SearchStore.saveApiKey(apiKey.text.toString(), currentProvider)
        SearchStore.saveMaxResults(currentMaxResults, currentProvider)
        ProviderStore.saveMergeModels(currentMergeModels)
        SearchStore.saveProvider(currentProvider)
        ToolStore.saveMaxRounds(currentMaxRounds)
    }

    fun switchProvider(next: SearchProvider) {
        if (next == provider) {
            providerMenuOpen = false
            return
        }
        SearchStore.saveApiKey(apiKey.text.toString(), provider)
        SearchStore.saveMaxResults(maxResults, provider)
        SearchStore.saveProvider(next)
        provider = next
        providerMenuOpen = false
        apiKey.edit {
            replace(0, length, SearchStore.loadApiKey(next))
        }
        maxResults = SearchStore.loadMaxResults(next)
    }

    DisposableEffect(apiKey) {
        onDispose { persistCurrent() }
    }

    Scaffold(
        containerColor = background,
        topBar = {
            IrioToolbar(
                title = { Text("模型") },
                backgroundColor = background,
                contentColor = content,
                leading = {
                    IconButton(
                        onClick = onBack,
                        style = ButtonStyle.Ghost,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回",
                            tint = content,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsSectionCard(title = "搜索") {
                DropdownMenu(
                    expanded = providerMenuOpen,
                    onExpandedChange = { providerMenuOpen = it },
                    modifier = Modifier.fillMaxWidth(),
                    alignment = DropdownMenuAlignment.End,
                    panel = {
                        DropdownMenuPanel {
                            SearchProvider.entries.forEach { option ->
                                DropdownMenuItem(
                                    onClick = { switchProvider(option) },
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
                                onClick = { providerMenuOpen = !providerMenuOpen },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("搜索提供商", color = Theme[colors][onPanelColor])
                            Text(provider.label, color = Theme[colors][mutedColor], fontSize = 13.sp)
                        }
                        IconButton(
                            onClick = { providerMenuOpen = !providerMenuOpen },
                            style = ButtonStyle.Ghost,
                            buttonSize = ButtonSize.Small,
                            shape = CircleShape,
                            modifier = Modifier.size(32.dp),
                            interactionSource = interactionSource,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.KeyboardArrowDown,
                                contentDescription = "选择搜索提供方",
                                tint = Theme[colors][onPanelColor],
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
                ProviderField(
                    label = "${provider.label} API 密钥",
                    state = apiKey,
                    keyboardType = KeyboardType.Password,
                    mask = true,
                    trailingSpace = false,
                )
                SettingsSlider(
                    label = "单次搜索上限",
                    value = maxResults,
                    valueRange = provider.maxResultsMin..provider.maxResultsMax,
                    onValueChange = { maxResults = provider.clampMaxResults(it) },
                )
            }
            SettingsSectionCard(title = "工具") {
                SettingsSlider(
                    label = "单轮工具调用",
                    value = maxRounds,
                    valueRange = ToolStore.MAX_ROUNDS_MIN..ToolStore.MAX_ROUNDS_MAX,
                    onValueChange = { maxRounds = it },
                )
                ImageGenModelPicker(
                    selected = imageGenModel,
                    onSelect = { model ->
                        imageGenModel = model
                        ToolStore.saveImageGenModel(model)
                    },
                )
                TitleModelPicker(
                    selected = titleModel,
                    onSelect = { model ->
                        titleModel = model
                        ToolStore.saveTitleModel(model)
                    },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text(
                        "合并模型显示",
                        modifier = Modifier.weight(1f),
                        color = Theme[colors][onBackgroundColor],
                    )
                    Switch(
                        checked = mergeModels,
                        onCheckedChange = { mergeModels = it },
                    )
                }
            }
            SettingsSectionCard(title = "系统提示词") {
                ListRowButton(
                    label = "编辑系统提示词",
                    onClick = { systemPromptDialogOpen = true },
                )
            }
        }
    }

    IrioAlertDialog(
        visible = systemPromptDialogOpen,
        onDismissRequest = { systemPromptDialogOpen = false },
        backgroundColor = Theme[colors][panelColor],
        contentColor = Theme[colors][onPanelColor],
    ) {
        Column {
            DialogTitle("系统提示词")
            Spacer(Modifier.height(16.dp))
            TextField(
                state = systemPrompt,
                modifier = Modifier.fillMaxWidth(),
                accessibilityLabel = "系统提示词",
                placeholder = { Text("以系统身份注入每次对话的指令") },
                lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 5, maxHeightInLines = 12),
                inputTransformation = InputTransformation.maxLength(SystemPromptStore.MAX_CHARS),
            )
            Spacer(Modifier.height(16.dp))
            DialogButtonRow(
                confirmLabel = "保存",
                onConfirm = {
                    SystemPromptStore.save(systemPrompt.text.toString())
                    systemPromptDialogOpen = false
                },
                onCancel = {
                    val saved = SystemPromptStore.load()
                    systemPrompt.edit { replace(0, length, saved) }
                    systemPromptDialogOpen = false
                },
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
    var menuOpen by remember { mutableStateOf(false) }
    // 只列生图模型（models.dev modalities.output 含 image）；普通聊天模型不该被选为图片生成目标。
    val providers = remember { ProviderStore.loadProviders() }
        .filter { it.enabled }
        .map { provider ->
            provider.copy(models = provider.enabledModels().filter { model -> ModelCatalog.isImageModel(model) })
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
        ?.let { (_, model) -> model }
        ?: "未配置"

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
                                    model,
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
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("图片生成模型", color = Theme[colors][onPanelColor])
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
                shape = CircleShape,
                modifier = Modifier.size(32.dp),
                interactionSource = interactionSource,
            ) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = "选择图片生成模型",
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
) {
    var menuOpen by remember { mutableStateOf(false) }
    val providers = remember { ProviderStore.loadProviders() }
        .filter { it.enabled }
        .map { provider ->
            provider.copy(models = provider.enabledModels().filterNot { model -> ModelCatalog.isImageModel(model) })
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
        ?.let { (_, model) -> model }
        ?: "跟随对话模型"

    DropdownMenu(
        expanded = menuOpen,
        onExpandedChange = { menuOpen = it },
        modifier = Modifier.fillMaxWidth(),
        alignment = DropdownMenuAlignment.End,
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
                        Text("跟随对话模型", fontSize = 15.5.sp)
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
                                Text(model, fontSize = 15.5.sp)
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
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("标题生成模型", color = Theme[colors][onPanelColor])
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
                shape = CircleShape,
                modifier = Modifier.size(32.dp),
                interactionSource = interactionSource,
            ) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = "选择标题生成模型",
                    tint = Theme[colors][onPanelColor],
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Theme[colors][secondaryColor], Theme[shapes][largeShape])
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, color = Theme[colors][mutedColor], fontSize = 13.sp)
        content()
    }
}
