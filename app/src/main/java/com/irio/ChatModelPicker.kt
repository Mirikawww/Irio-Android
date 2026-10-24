package com.irio

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.ui.components.Button
import com.composables.ui.components.ButtonSize
import com.composables.ui.components.ButtonStyle
import com.composables.ui.components.DropdownMenu
import com.composables.ui.components.DropdownMenuAlignment
import com.composables.ui.components.Icon
import com.composables.ui.components.Text
import com.composables.ui.theme.colors
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.onBackgroundColor
import com.composeunstyled.theme.Theme

/** 模型列表的预计算行：文本与目标模型在 remember 里一次算好，滚动路径零文本处理。 */
internal sealed interface ModelPickerRow {
    val key: String

    data class Gap(override val key: String) : ModelPickerRow

    data class GroupLabel(override val key: String, val text: String) : ModelPickerRow

    data class Model(
        override val key: String,
        val providerId: String,
        val display: String,
        val target: String,
        val variants: List<String>,
    ) : ModelPickerRow
}

@Composable
internal fun ModelPicker(
    alignLeft: Boolean,
    selectedModel: String,
    providers: List<ProviderConfig>,
    activeProviderId: String,
    mergeModels: Boolean,
    useModelsDev: Boolean,
    useModelsDevReasoning: Boolean,
    reasoningEffort: ReasoningEffort,
    fastMode: Boolean,
    webSearchAvailable: Boolean,
    webSearchEnabled: Boolean,
    autoRead: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelectedModelChange: (model: String, providerId: String) -> Unit,
    onReasoningChange: (ReasoningEffort) -> Unit,
    onFastModeChange: (Boolean) -> Unit,
    onWebSearchChange: (Boolean) -> Unit,
    onAutoReadChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val content = Theme[colors][onBackgroundColor]
    val muted = Theme[colors][mutedColor]
    val providersWithModels = providers.filter { it.enabled && it.enabledModels().isNotEmpty() }
    if (providersWithModels.isEmpty()) {
        Button(
            onClick = {},
            enabled = false,
            style = ButtonStyle.Ghost,
            buttonSize = ButtonSize.Small,
            shape = ToolbarCapsuleShape,
            modifier = Modifier.semantics { contentDescription = tr("选择模型") },
        ) {
            Text(tr("未配置模型"), fontSize = 15.sp, color = muted, maxLines = 1)
            Icon(
                imageVector = IrioIcons.ChevronDown,
                contentDescription = tr("选择模型"),
                modifier = Modifier.size(18.dp),
                tint = muted,
            )
        }
        return
    }
    // 每个提供商内部合并 -thinking / -fast 变体；不同提供商之间不合并。
    // 行的显示名 / 目标模型在 remember 里预计算，滚动组合时只剩纯 Text。
    val rows =
        remember(providersWithModels, mergeModels, useModelsDev) {
            buildList {
                providersWithModels.forEachIndexed { providerIndex, provider ->
                    if (providerIndex > 0) add(ModelPickerRow.Gap("gap-$providerIndex"))
                    add(ModelPickerRow.GroupLabel("label-${provider.id}", provider.name))
                    val groups =
                        if (mergeModels) {
                            provider.enabledModels().groupBy {
                                ModelCatalog.mergeKey(it, mergeEnabled = true)
                            }
                        } else {
                            LinkedHashMap<String, List<String>>().apply {
                                provider.enabledModels().forEach { put(it, listOf(it)) }
                            }
                        }
                    groups.entries.forEach { (base, variants) ->
                        add(
                            ModelPickerRow.Model(
                                key = "${provider.id}/$base",
                                providerId = provider.id,
                                display = ModelCatalog.displayName(context, base, useModelsDev),
                                target =
                                    if (mergeModels) ModelCatalog.canonicalModel(variants)
                                    else base,
                                variants = variants,
                            )
                        )
                    }
                }
            }
        }
    val selectedRow =
        rows.asSequence().filterIsInstance<ModelPickerRow.Model>().firstOrNull {
            it.providerId == activeProviderId && selectedModel in it.variants
        }
    val selectedDisplay =
        selectedRow?.display ?: ModelCatalog.displayName(context, selectedModel, useModelsDev)
    val reasoningInfo =
        remember(selectedModel, useModelsDevReasoning) {
            ModelCatalog.reasoningInfo(context, selectedModel, useCatalog = useModelsDevReasoning)
        }
    val reasoningLevels = remember(reasoningInfo) { ModelCatalog.reasoningLevels(reasoningInfo) }
    val effectiveEffort = ModelCatalog.resolveReasoningEffort(reasoningEffort, reasoningLevels)
    DropdownMenu(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = Modifier.wrapContentWidth(if (alignLeft) Alignment.Start else Alignment.End),
        alignment = if (alignLeft) DropdownMenuAlignment.Start else DropdownMenuAlignment.End,
        panel = {
            ModelSwitcherPanel(
                selectedDisplay = selectedDisplay,
                selectedModel = selectedModel,
                activeProviderId = activeProviderId,
                rows = rows,
                reasoningEffort = effectiveEffort,
                supportsThinking = reasoningInfo?.supports ?: true,
                reasoningLevels = reasoningLevels,
                fastMode = fastMode,
                webSearchAvailable = webSearchAvailable,
                webSearchEnabled = webSearchEnabled,
                autoRead = autoRead,
                providerProtocol =
                    providersWithModels.firstOrNull { it.id == activeProviderId }?.protocol
                        ?: ProviderProtocol.CHAT_COMPLETIONS,
                onSelectedModelChange = onSelectedModelChange,
                onReasoningChange = onReasoningChange,
                onFastModeChange = onFastModeChange,
                onWebSearchChange = onWebSearchChange,
                onAutoReadChange = onAutoReadChange,
            )
        },
    ) {
        Button(
            onClick = { onExpandedChange(!expanded) },
            style = ButtonStyle.Ghost,
            buttonSize = ButtonSize.Small,
            shape = ToolbarCapsuleShape,
            modifier = Modifier.semantics { contentDescription = tr("选择模型") },
        ) {
            Text(
                selectedDisplay.ifBlank { tr("选择模型") },
                modifier = Modifier.widthIn(max = 180.dp),
                fontSize = 15.sp,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = IrioIcons.ChevronDown,
                contentDescription = tr("选择模型"),
                modifier = Modifier.size(18.dp),
                tint = content,
            )
        }
    }
}
