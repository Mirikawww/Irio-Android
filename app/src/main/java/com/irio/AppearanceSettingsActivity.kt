package com.irio

import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.ui.components.Button
import com.irio.IrioCheckbox as Checkbox
import com.composables.ui.components.ButtonSize
import com.composables.ui.components.ButtonStyle
import com.composables.ui.components.DropdownMenu
import com.composables.ui.components.DropdownMenuAlignment
import com.composables.ui.components.DropdownMenuItem
import com.irio.ScaledDropdownMenuPanel as DropdownMenuPanel
import com.composables.ui.components.Icon
import com.composables.ui.components.IconButton
import com.composables.ui.components.Text
import com.irio.IrioSwitch as Switch
import com.composables.ui.theme.backgroundColor
import com.composables.ui.theme.colors
import com.composables.ui.theme.fieldShape
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.onBackgroundColor
import com.composables.ui.theme.onPanelColor
import com.composeunstyled.theme.Theme

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
internal fun AppearanceSettingsScreen(onBack: () -> Unit) {
    val background = Theme[colors][backgroundColor]
    val content = Theme[colors][onBackgroundColor]
    val context = LocalContext.current
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
    var pendingInputCornerRadius by remember(ThemeStore.inputCornerRadius) {
        mutableIntStateOf(ThemeStore.inputCornerRadius)
    }

    SettingsHazeScaffold(
        title = tr("外观与偏好"),
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
            SettingsSectionCard(title = tr("字体与语言")) {
                AppearanceFontSetting()
                TextScaleSettings()
                DropdownMenu(
                    expanded = languageMenuOpen,
                    onExpandedChange = { languageMenuOpen = it },
                    modifier = Modifier.fillMaxWidth().settingsSearchAnchor("appearance.language", edge = SettingsSearchEdge.Bottom),
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
                                                imageVector = IrioIcons.Check,
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
                                imageVector = IrioIcons.ChevronDown,
                                contentDescription = tr("选择语言"),
                                tint = Theme[colors][onPanelColor],
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
            SettingsSectionCard(title = tr("主题与样式")) {
                DropdownMenu(
                    expanded = themeMenuOpen,
                    onExpandedChange = { themeMenuOpen = it },
                    modifier = Modifier.fillMaxWidth().settingsSearchAnchor("appearance.theme", edge = SettingsSearchEdge.Top),
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
                                                imageVector = IrioIcons.Check,
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
                                imageVector = IrioIcons.ChevronDown,
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
                    modifier = Modifier.fillMaxWidth().settingsSearchAnchor("appearance.accent"),
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
                                imageVector = IrioIcons.ChevronDown,
                                contentDescription = tr("选择强调色"),
                                tint = Theme[colors][onPanelColor],
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.settingsSearchAnchor("appearance.amoled").settingsSwitchRow(),
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
                BubbleOpacitySetting()
                SettingsSlider(
                    modifier = Modifier.settingsSearchAnchor("appearance.inputCorners"),
                    label = tr("输入框圆角"),
                    value = pendingInputCornerRadius,
                    valueRange = InputCornerRadiusRange,
                    valueLabel = { "$it dp" },
                    onValueChange = { pendingInputCornerRadius = it },
                    onValueChangeFinished = {
                        ThemeStore.saveInputCornerRadius(context, pendingInputCornerRadius)
                    },
                    preview = if (ThemeStore.betterSettingsDisplay) { radius, covered ->
                        InputCornerPreview(radius, covered)
                    } else null,
                )
                SettingsDependentContent(enabled = pendingInputCornerRadius != DefaultInputCornerRadiusDp) {
                    Checkbox(
                        checked = ThemeStore.inputCornersMessageOnly,
                        onCheckedChange = { ThemeStore.saveInputCornersMessageOnly(context, it) },
                        modifier = Modifier
                            .settingsSearchAnchor("appearance.inputCorners.messageOnly")
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        accessibilityLabel = tr("仅对消息框生效"),
                    ) {
                        Text(tr("仅对消息框生效"), color = Theme[colors][onPanelColor])
                    }
                }
                Row(
                    modifier = Modifier
                        .settingsSearchAnchor("appearance.glass")
                        .settingsSwitchRow(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tr("毛玻璃"),
                        modifier = Modifier.weight(1f),
                        color = Theme[colors][onPanelColor],
                    )
                    Switch(
                        checked = ThemeStore.glassEnabled,
                        onCheckedChange = { ThemeStore.saveGlassEnabled(context, it) },
                        modifier = Modifier
                            .semantics { contentDescription = tr("毛玻璃") },
                    )
                }
                SettingsDependentContent(enabled = ThemeStore.glassEnabled) {
                    GlassSurface.entries.forEach { surface ->
                        val label = when (surface) {
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
                            modifier = Modifier
                                .settingsSearchAnchor(
                                    when (surface) {
                                        GlassSurface.SIDEBAR_BUTTON -> "appearance.glass.sidebar"
                                        GlassSurface.CONTEXT_RING -> "appearance.glass.ring"
                                        GlassSurface.MODEL_SWITCHER -> "appearance.glass.switcher"
                                        GlassSurface.COMPOSER -> "appearance.glass.composer"
                                    },
                                )
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            accessibilityLabel = label,
                        ) {
                            Text(label, color = Theme[colors][onPanelColor])
                        }
                    }
                }
                Row(
                    modifier = Modifier.settingsSearchAnchor("appearance.userAvatar", edge = SettingsSearchEdge.Bottom).settingsSwitchRow(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tr("显示用户头像"),
                        modifier = Modifier.weight(1f),
                        color = Theme[colors][onPanelColor],
                    )
                    Switch(
                        checked = ThemeStore.showUserAvatar,
                        onCheckedChange = { enabled ->
                            ThemeStore.saveShowUserAvatar(context, enabled)
                        },
                    )
                }
            }
            SettingsSectionCard(title = tr("图片处理")) {
                ImageUploadSettings()
            }
            SettingsSectionCard(title = tr("输入与发送")) {
                Row(
                    modifier = Modifier
                        .settingsSearchAnchor("appearance.paste", edge = SettingsSearchEdge.Top)
                        .settingsSwitchRow(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tr("粘贴长文本为文件"),
                        modifier = Modifier.weight(1f),
                        color = Theme[colors][onPanelColor],
                    )
                    Switch(
                        checked = ThemeStore.longTextPaste,
                        onCheckedChange = { ThemeStore.saveLongTextPaste(context, it) },
                        modifier = Modifier
                            .semantics { contentDescription = tr("粘贴长文本为文件") },
                    )
                }
                SettingsDependentContent(enabled = ThemeStore.longTextPaste) {
                    SettingsSlider(
                        modifier = Modifier.settingsSearchAnchor("appearance.paste.threshold"),
                        label = tr("字符阈值"),
                        value = ThemeStore.longTextPasteThreshold,
                        valueRange = LongTextPaste.MIN_THRESHOLD..LongTextPaste.MAX_THRESHOLD,
                        step = LongTextPaste.STEP,
                        onValueChange = { ThemeStore.saveLongTextPasteThreshold(context, it) },
                        valueLabel = { tr("%d 个字符").format(it) },
                    )
                }
                Row(
                    modifier = Modifier.settingsSearchAnchor("appearance.enter", edge = SettingsSearchEdge.Bottom).settingsSwitchRow(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tr("按 Enter 发送"),
                        color = Theme[colors][onPanelColor],
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = ThemeStore.enterToSend,
                        onCheckedChange = { enabled ->
                            ThemeStore.saveEnterToSend(context, enabled)
                        },
                    )
                }
            }
            SettingsSectionCard(title = tr("错误恢复")) {
                Row(
                    modifier = Modifier.settingsSearchAnchor("appearance.retry", edge = SettingsSearchEdge.Top).settingsSwitchRow(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(tr("自动重试"), modifier = Modifier.weight(1f), color = Theme[colors][onPanelColor])
                    Switch(checked = ThemeStore.autoRetry, onCheckedChange = { ThemeStore.saveAutoRetry(context, it) })
                }
                SettingsDependentContent(enabled = ThemeStore.autoRetry) {
                    SettingsSlider(
                        modifier = Modifier.settingsSearchAnchor("appearance.retry.limit"),
                        label = tr("重试次数上限"),
                        value = ThemeStore.retryLimit,
                        valueRange = 1..20,
                        onValueChange = { ThemeStore.saveRetryLimit(context, it) },
                    )
                    SettingsSlider(
                        modifier = Modifier.settingsSearchAnchor("appearance.retry.cooldown", edge = SettingsSearchEdge.Bottom),
                        label = tr("重试冷却时长"),
                        value = ThemeStore.retryCooldownSeconds,
                        valueRange = 0..300,
                        valueLabel = { "${it}s" },
                        onValueChange = { ThemeStore.saveRetryCooldown(context, it) },
                    )
                }
            }
            SettingsSectionCard(title = tr("界面交互")) {
                Row(
                    modifier = Modifier.settingsSearchAnchor("appearance.glyph", edge = SettingsSearchEdge.Top).settingsSwitchRow(),
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
                    modifier = Modifier.settingsSearchAnchor("appearance.imageStreaming").settingsSwitchRow(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tr("图片生成流式传输"),
                        modifier = Modifier.weight(1f),
                        color = Theme[colors][onPanelColor],
                    )
                    Switch(
                        checked = ThemeStore.imageGenerationStreaming,
                        onCheckedChange = { ThemeStore.saveImageGenerationStreaming(context, it) },
                    )
                }
                Row(
                    modifier = Modifier.settingsSearchAnchor("appearance.diff").settingsSwitchRow(),
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
                    modifier = Modifier.settingsSearchAnchor("appearance.pickerLeft").settingsSwitchRow(),
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
                    modifier = Modifier.settingsSearchAnchor("appearance.scroll").settingsSwitchRow(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tr("发送新消息时滚动到新消息"),
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
                    modifier = Modifier.settingsSearchAnchor("appearance.composerAnim").settingsSwitchRow(),
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
                    modifier = Modifier.settingsSearchAnchor("appearance.ring", edge = SettingsSearchEdge.Bottom).settingsSwitchRow(),
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
            }
            SettingsSectionCard(title = tr("内容显示")) {
                Row(
                    modifier = Modifier.settingsSearchAnchor("appearance.wrap", edge = SettingsSearchEdge.Top).settingsSwitchRow(),
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
                    modifier = Modifier.settingsSearchAnchor("appearance.lineNumbers").settingsSwitchRow(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tr("代码块行号"),
                        color = Theme[colors][onPanelColor],
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = ThemeStore.codeBlockLineNumbers,
                        onCheckedChange = { enabled ->
                            ThemeStore.saveCodeBlockLineNumbers(context, enabled)
                        },
                    )
                }
                Row(
                    modifier = Modifier.settingsSearchAnchor("appearance.thinking").settingsSwitchRow(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tr("默认折叠思考块"),
                        modifier = Modifier.weight(1f),
                        color = Theme[colors][onPanelColor],
                    )
                    Switch(
                        checked = !ThemeStore.thinkingBlock,
                        onCheckedChange = { enabled ->
                            ThemeStore.saveThinkingBlock(context, !enabled)
                        },
                    )
                }
                Row(
                    modifier = Modifier.settingsSearchAnchor("appearance.mergeTools").settingsSwitchRow(),
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
                    modifier = Modifier.settingsSearchAnchor("appearance.math"),
                    label = tr("公式大小"),
                    value = texScalePercent,
                    valueRange = 60..160,
                    onValueChange = { percent ->
                        texScalePercent = percent
                        ProviderStore.saveTexScale(context, percent / 100f)
                    },
                    valueLabel = { "$it%" },
                )
            }
            SettingsSectionCard(title = tr("模型列表")) {
                Row(
                    modifier = Modifier.settingsSearchAnchor("appearance.mergeModels", edge = SettingsSearchEdge.Top).settingsSwitchRow(),
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
                    modifier = Modifier.settingsSearchAnchor("appearance.names", edge = SettingsSearchEdge.Bottom).fillMaxWidth().padding(vertical = 5.dp),
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
