package com.irio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.ui.components.Button
import com.composables.ui.components.Checkbox
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
import com.composables.ui.theme.alphas
import com.composables.ui.theme.backgroundColor
import com.composables.ui.theme.colors
import com.composables.ui.theme.disabledAlpha
import com.composables.ui.theme.fieldShape
import com.composables.ui.theme.largeShape
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.onBackgroundColor
import com.composables.ui.theme.onPanelColor
import com.composables.ui.theme.secondaryColor
import com.composables.ui.theme.shapes
import com.composeunstyled.theme.Theme
import kotlinx.coroutines.launch

@Composable
private fun AccentSwatch(color: Color) {
    Box(
        modifier = Modifier
            .size(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(color, ContinuousCapsule),
        )
    }
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
    val scope = rememberCoroutineScope()
    val chatBackgroundName = ChatBackgroundStore.fileName
    val chatBackgroundInteraction = remember { MutableInteractionSource() }
    val pickChatBackground = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (!ChatBackgroundEnabled || uri == null) return@rememberLauncherForActivityResult
        scope.launch { ChatBackgroundStore.save(context, uri) }
    }
    var texScalePercent by remember { mutableIntStateOf((ProviderStore.loadTexScale(context) * 100).toInt()) }
    var mergeModels by remember { mutableStateOf(ProviderStore.loadMergeModels(context)) }
    var mergeToolCalls by remember { mutableStateOf(ThemeStore.mergeToolCalls) }
    var showMergeWarning by remember { mutableStateOf(false) }
    var mergeWarningRestartKey by remember { mutableIntStateOf(0) }
    var openRouterNames by remember { mutableStateOf(ProviderStore.loadModelsDevCatalog(context)) }
    val themePreference = ThemeStore.preference
    val accent = ThemeStore.accent
    val language = ThemeStore.language
    val autoAmoled = ThemeStore.autoAmoled
    var themeMenuOpen by remember { mutableStateOf(false) }
    var accentMenuOpen by remember { mutableStateOf(false) }
    var languageMenuOpen by remember { mutableStateOf(false) }
    var glassExpanded by remember { mutableStateOf(false) }

    SettingsHazeScaffold(
        title = tr("外观"),
        onBack = onBack,
    ) { topInset, bottomInset ->
        Box(Modifier.fillMaxSize()) {
            Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = bottomInset)
                .verticalScroll(rememberScrollState())
                .padding(top = topInset)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Theme[colors][secondaryColor], Theme[shapes][largeShape])
                    .padding(horizontal = 16.dp, vertical = SettingsCardInset),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                DropdownMenu(
                    expanded = languageMenuOpen,
                    onExpandedChange = { languageMenuOpen = it },
                    modifier = Modifier.fillMaxWidth(),
                    alignment = DropdownMenuAlignment.End,
                    panel = {
                        DropdownMenuPanel {
                            AppLanguage.entries.forEach { option ->
                                DropdownMenuItem(
                                    onClick = {
                                        ThemeStore.saveLanguage(context, option)
                                        languageMenuOpen = false
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    leading = {
                                        if (language == option) {
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
                                ) { Text(tr(option.label), color = Theme[colors][onPanelColor]) }
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
                                onClick = { languageMenuOpen = !languageMenuOpen },
                            )
                            .padding(top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(tr("语言"), color = Theme[colors][onPanelColor])
                            }
                            Text(tr(language.label), color = Theme[colors][mutedColor], fontSize = 13.sp)
                        }
                        IconButton(
                            onClick = { languageMenuOpen = !languageMenuOpen },
                            style = ButtonStyle.Ghost,
                            buttonSize = ButtonSize.Small,
                            shape = ContinuousCapsule,
                            modifier = Modifier.size(32.dp),
                            interactionSource = interactionSource,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.KeyboardArrowDown,
                                contentDescription = tr("选择语言"),
                                tint = Theme[colors][onPanelColor],
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
                DropdownMenu(
                    expanded = themeMenuOpen,
                    onExpandedChange = { themeMenuOpen = it },
                    modifier = Modifier.fillMaxWidth(),
                    alignment = DropdownMenuAlignment.End,
                    panel = {
                        DropdownMenuPanel {
                            ThemePreference.entries.forEach { option ->
                                DropdownMenuItem(
                                    onClick = {
                                        ThemeStore.save(context, option)
                                        themeMenuOpen = false
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    leading = {
                                        if (themePreference == option) {
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
                                ) { Text(tr(option.label), color = Theme[colors][onPanelColor]) }
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
                                onClick = { themeMenuOpen = !themeMenuOpen },
                            )
                            .settingsDropdownInset(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(tr("主题"), color = Theme[colors][onPanelColor])
                            Text(tr(themePreference.label), color = Theme[colors][mutedColor], fontSize = 13.sp)
                        }
                        IconButton(
                            onClick = { themeMenuOpen = !themeMenuOpen },
                            style = ButtonStyle.Ghost,
                            buttonSize = ButtonSize.Small,
                            shape = ContinuousCapsule,
                            modifier = Modifier.size(32.dp),
                            interactionSource = interactionSource,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.KeyboardArrowDown,
                                contentDescription = tr("选择主题"),
                                tint = Theme[colors][onPanelColor],
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
                DropdownMenu(
                    expanded = accentMenuOpen,
                    onExpandedChange = { accentMenuOpen = it },
                    modifier = Modifier.fillMaxWidth(),
                    alignment = DropdownMenuAlignment.End,
                    panel = {
                        DropdownMenuPanel {
                            AccentColor.entries.forEach { option ->
                                DropdownMenuItem(
                                    onClick = {
                                        ThemeStore.saveAccent(context, option)
                                        accentMenuOpen = false
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    leading = {
                                        if (accent == option) {
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
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        AccentSwatch(accentDisplayColor(option))
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            tr(option.label),
                                            color = Theme[colors][onPanelColor],
                                            lineHeight = 16.sp,
                                        )
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
                                onClick = { accentMenuOpen = !accentMenuOpen },
                            )
                            .settingsDropdownInset(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(tr("强调色"), color = Theme[colors][onPanelColor])
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AccentSwatch(accentDisplayColor(accent))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    tr(accent.label),
                                    color = Theme[colors][mutedColor],
                                    fontSize = 13.sp,
                                    lineHeight = 16.sp,
                                )
                            }
                        }
                        IconButton(
                            onClick = { accentMenuOpen = !accentMenuOpen },
                            style = ButtonStyle.Ghost,
                            buttonSize = ButtonSize.Small,
                            shape = ContinuousCapsule,
                            modifier = Modifier.size(32.dp),
                            interactionSource = interactionSource,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.KeyboardArrowDown,
                                contentDescription = tr("选择强调色"),
                                tint = Theme[colors][onPanelColor],
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.settingsSwitchRow(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(tr("自动 AMOLED"), color = Theme[colors][onPanelColor])
                    }
                    Switch(
                        checked = autoAmoled,
                        onCheckedChange = { enabled ->
                            ThemeStore.saveAutoAmoled(context, enabled)
                        },
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(if (ChatBackgroundEnabled) 1f else Theme[alphas][disabledAlpha]),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tr("聊天背景"),
                        modifier = Modifier
                            .weight(1f)
                            .clickable(
                                interactionSource = chatBackgroundInteraction,
                                indication = null,
                                enabled = ChatBackgroundEnabled,
                                onClick = {
                                    pickChatBackground.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                    )
                                },
                            )
                            .padding(vertical = 7.dp),
                        color = Theme[colors][onPanelColor],
                    )
                    if (ChatBackgroundEnabled && chatBackgroundName != null) {
                        Text(
                            tr("删除"),
                            modifier = Modifier
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { ChatBackgroundStore.clear(context) },
                                )
                                .padding(start = 12.dp, top = 7.dp, bottom = 7.dp),
                            color = Theme[colors][onPanelColor],
                        )
                    }
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Theme[colors][secondaryColor], Theme[shapes][largeShape])
                    .padding(horizontal = 16.dp, vertical = SettingsCardInset),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                val glassArrow by animateFloatAsState(
                    targetValue = if (glassExpanded) 180f else 0f,
                    animationSpec = irioSpring(),
                    label = "glass expansion arrow",
                )
                val glassInteraction = remember { MutableInteractionSource() }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = glassInteraction,
                            indication = null,
                            onClick = { glassExpanded = !glassExpanded },
                        )
                        .padding(top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tr("毛玻璃"),
                        modifier = Modifier.weight(1f),
                        color = Theme[colors][onPanelColor],
                    )
                    IconButton(
                        onClick = { glassExpanded = !glassExpanded },
                        style = ButtonStyle.Ghost,
                        buttonSize = ButtonSize.Small,
                        shape = ContinuousCapsule,
                        modifier = Modifier.size(32.dp),
                        interactionSource = glassInteraction,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowDown,
                            contentDescription = if (glassExpanded) tr("收起") else tr("展开"),
                            tint = Theme[colors][onPanelColor],
                            modifier = Modifier.size(20.dp).rotate(glassArrow),
                        )
                    }
                }
                AnimatedVisibility(
                    visible = glassExpanded,
                    enter = expandVertically(animationSpec = irioSpring(), expandFrom = Alignment.Top) + fadeIn(animationSpec = irioSpring()),
                    exit = shrinkVertically(animationSpec = irioSpring(), shrinkTowards = Alignment.Top) + fadeOut(animationSpec = irioSpring()),
                ) {
                    Column {
                    GlassSurface.entries.forEach { surface ->
                        val label = when (surface) {
                            GlassSurface.USER_BUBBLE -> tr("用户气泡")
                            GlassSurface.SIDEBAR_BUTTON -> tr("侧边栏按钮")
                            GlassSurface.CONTEXT_RING -> tr("上下文圆环")
                            GlassSurface.MODEL_SWITCHER -> tr("模型切换器")
                            GlassSurface.COMPOSER -> tr("消息框、语音框")
                        }
                        Checkbox(
                            checked = surface in ThemeStore.glassSurfaces,
                            onCheckedChange = { enabled ->
                                ThemeStore.saveGlassSurface(context, surface, enabled)
                            },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            accessibilityLabel = label,
                        ) {
                            Text(label, color = Theme[colors][onPanelColor])
                        }
                    }
                    }
                }
                Row(
                    modifier = Modifier.settingsSwitchRow(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ExperimentalLabel(
                        tr("文本淡入"),
                        color = Theme[colors][onPanelColor],
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = ThemeStore.glyphFade,
                        onCheckedChange = { enabled ->
                            ThemeStore.saveGlyphFade(context, enabled)
                        },
                    )
                }
                Row(
                    modifier = Modifier.settingsSwitchRow(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(tr("模型差异"), color = Theme[colors][onPanelColor])
                    }
                    Switch(
                        checked = ThemeStore.modelDiff,
                        onCheckedChange = { enabled ->
                            ThemeStore.saveModelDiff(context, enabled)
                        },
                    )
                }
                Row(
                    modifier = Modifier.settingsSwitchRow(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tr("模型切换器居左"),
                        modifier = Modifier.weight(1f),
                        color = Theme[colors][onPanelColor],
                    )
                    Switch(
                        checked = ThemeStore.modelPickerOnLeft,
                        onCheckedChange = { enabled ->
                            ThemeStore.saveModelPickerOnLeft(context, enabled)
                        },
                    )
                }
                Row(
                    modifier = Modifier.settingsSwitchRow(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ExperimentalLabel(
                        tr("新消息滚动"),
                        color = Theme[colors][onPanelColor],
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = ThemeStore.newMessageScroll,
                        onCheckedChange = { enabled ->
                            ThemeStore.saveNewMessageScroll(context, enabled)
                        },
                    )
                }
                Row(
                    modifier = Modifier.settingsSwitchRow(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tr("消息框延展动画"),
                        color = Theme[colors][onPanelColor],
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = ThemeStore.composerWrapAnimation,
                        onCheckedChange = { enabled ->
                            ThemeStore.saveComposerWrapAnimation(context, enabled)
                        },
                    )
                }
                Row(
                    modifier = Modifier.settingsSwitchRow(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tr("代码块自动换行"),
                        color = Theme[colors][onPanelColor],
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = ThemeStore.codeBlockWrap,
                        onCheckedChange = { enabled ->
                            ThemeStore.saveCodeBlockWrap(context, enabled)
                        },
                    )
                }
                Row(
                    modifier = Modifier.settingsSwitchRow(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(tr("上下文圆环"), color = Theme[colors][onPanelColor])
                    }
                    Switch(
                        checked = ThemeStore.contextRing,
                        onCheckedChange = { enabled ->
                            ThemeStore.saveContextRing(context, enabled)
                        },
                    )
                }
                Row(
                    modifier = Modifier.settingsSwitchRow(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tr("思考块"),
                        modifier = Modifier.weight(1f),
                        color = Theme[colors][onPanelColor],
                    )
                    Switch(
                        checked = ThemeStore.thinkingBlock,
                        onCheckedChange = { enabled ->
                            ThemeStore.saveThinkingBlock(context, enabled)
                        },
                    )
                }
                Row(
                    modifier = Modifier.settingsSwitchRow(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tr("合并工具调用显示"),
                        modifier = Modifier.weight(1f),
                        color = Theme[colors][onPanelColor],
                    )
                    Switch(
                        checked = mergeToolCalls,
                        onCheckedChange = { enabled ->
                            mergeToolCalls = enabled
                            ThemeStore.saveMergeToolCalls(context, enabled)
                            if (!enabled) {
                                showMergeWarning = false
                            } else if (ToolStore.loadSmartToolHints(context)) {
                                ToolStore.saveSmartToolHints(context, false)
                                mergeWarningRestartKey++
                                showMergeWarning = true
                            }
                        },
                    )
                }
                SettingsSlider(
                    label = tr("公式大小"),
                    value = texScalePercent,
                    valueRange = 60..160,
                    onValueChange = { percent ->
                        texScalePercent = percent
                        ProviderStore.saveTexScale(context, percent / 100f)
                    },
                    valueLabel = { "$it%" },
                )
                Row(
                    modifier = Modifier.settingsSwitchRow(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tr("合并模型显示"),
                        modifier = Modifier.weight(1f),
                        color = Theme[colors][onPanelColor],
                    )
                    Switch(
                        checked = mergeModels,
                        onCheckedChange = {
                            mergeModels = it
                            ProviderStore.saveMergeModels(context, it)
                        },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            tr("使用 OpenRouter 格式化模型名称"),
                            modifier = Modifier.weight(1f),
                            color = Theme[colors][onPanelColor],
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = openRouterNames,
                        onCheckedChange = {
                            openRouterNames = it
                            ProviderStore.saveModelsDevCatalog(context, it)
                        },
                    )
                }
            }
        }
        IrioSnackbar(
            visible = showMergeWarning,
            message = tr("开启合并工具调用后，将自动关闭更智能的工具调用提示"),
            bottomInset = bottomInset,
            onDismiss = { showMergeWarning = false },
            restartKey = mergeWarningRestartKey,
        )
    }
}
}
