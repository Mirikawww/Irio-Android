package com.irio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.composables.ui.components.Text
import com.composables.ui.theme.colors
import com.composables.ui.theme.onBackgroundColor
import com.composables.ui.theme.onPrimaryColor
import com.composables.ui.theme.primaryColor
import com.composables.ui.theme.secondaryColor
import com.composeunstyled.theme.Theme
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ToolSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setIrioContent { ToolSettingsScreen(onBack = { finish() }) }
    }
}

@Composable
internal fun ToolSettingsScreen(
    onBack: () -> Unit,
    loadTools: suspend (android.content.Context) -> List<ToolSettingsEntry> = ToolCatalog::load,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var entries by remember { mutableStateOf<List<ToolSettingsEntry>>(emptyList()) }
    var preferences by remember { mutableStateOf(ToolPreferencesStore.load(context)) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    // Drafts and expansion belong to the tool, never to a recycled ComposeView.
    val drafts = remember { mutableMapOf<String, TextFieldState>() }
    var notice by remember { mutableStateOf("") }
    var noticeVisible by remember { mutableStateOf(false) }
    var selectedScope by rememberSaveable { mutableStateOf(ToolSettingsScope.Main) }
    var tabsHeightPx by remember { mutableIntStateOf(0) }
    val tabsHeight = with(LocalDensity.current) { tabsHeightPx.toDp() }
    val visibleEntries = remember(entries, selectedScope) {
        if (selectedScope == ToolSettingsScope.Main) entries else entries.filterNot { it.name in SubagentTools.NAMES }
    }
    fun showNotice(message: String) {
        notice = message
        noticeVisible = true
    }
    DisposableEffect(lifecycleOwner, context) {
        var refresh: Job? = null
        fun reload() {
            refresh?.cancel()
            refresh = scope.launch {
                val loaded = loadTools(context)
                preferences = ToolPreferencesStore.load(context)
                entries = loaded
                loaded.filter { it.blockedReason != null }.forEach { expanded[it.name] = false }
            }
        }
        reload()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reload()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            refresh?.cancel()
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    DismissComposerFocusWhenImeHides()
    Box(Modifier.fillMaxSize().imePadding()) {
        SettingsHazeScaffold(title = tr("工具"), onBack = onBack) { topInset, bottomInset ->
            Box(Modifier.fillMaxSize()) {
                AndroidComposeRecyclerList(
                    items = visibleEntries,
                    key = { it.name },
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = topInset + 16.dp,
                        end = 16.dp,
                        bottom = tabsHeight + 16.dp,
                    ),
                    itemSpacing = 12.dp,
                ) { entry ->
                    key(entry.name) {
                        val draft = drafts.getOrPut(entry.name) {
                            TextFieldState(preferences.descriptions[entry.name] ?: entry.defaultDescription)
                        }
                        ToolSettingsRow(
                            entry = entry,
                            checked = preferences.isEnabled(entry.name),
                            expanded = expanded[entry.name] == true,
                            description = draft,
                            onExpandedChange = { next ->
                                if (!next) focus.clearFocus()
                                expanded[entry.name] = next
                            },
                            onCheckedChange = { enabled ->
                                ToolPreferencesStore.saveEnabled(context, entry.name, enabled)
                                preferences = ToolPreferencesStore.load(context)
                            },
                            onSave = {
                                ToolPreferencesStore.saveDescription(context, entry.name, draft.text.toString())
                                preferences = ToolPreferencesStore.load(context)
                                focus.clearFocus()
                                showNotice(tr("工具描述已保存"))
                            },
                            onReset = {
                                ToolPreferencesStore.resetDescription(context, entry.name)
                                draft.edit { replace(0, length, entry.defaultDescription) }
                                preferences = ToolPreferencesStore.load(context)
                                focus.clearFocus()
                                showNotice(tr("已恢复默认描述"))
                            },
                            onBlocked = { entry.blockedReason?.let { showNotice(it.message()) } },
                        )
                    }
                }
                IrioSnackbar(
                    visible = noticeVisible,
                    message = notice,
                    bottomInset = tabsHeight,
                    onDismiss = { noticeVisible = false },
                )
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .onSizeChanged { tabsHeightPx = it.height },
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
                    ) {
                        ToolSettingsTabs(
                            selected = selectedScope,
                            onSelect = { selectedScope = it },
                        )
                    }
                }
            }
        }
    }
}

private enum class ToolSettingsScope(val label: String) {
    Main("主模型"),
    Subagent("子智能体"),
}

@Composable
private fun ToolSettingsTabs(
    selected: ToolSettingsScope,
    onSelect: (ToolSettingsScope) -> Unit,
) {
    val pages = ToolSettingsScope.entries
    val progress by animateFloatAsState(
        targetValue = pages.indexOf(selected).toFloat(),
        animationSpec = irioSpring<Float>(stiffness = 520f),
        label = "toolSettingsTab",
    )
    val selectedFill = Theme[colors][primaryColor]
    val selectedInk = Theme[colors][onPrimaryColor]
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
                        irioSpring(),
                        label = "toolSettingsTabInk",
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
            val labels = Array(count) { index -> measurables[index + 1].measure(labelConstraints) }
            val height = labels.maxOf { it.height }
            val indicator = measurables[0].measure(Constraints.fixed(segment, height))
            layout(width, height) {
                indicator.placeRelative((progress * segment).toInt(), 0)
                labels.forEachIndexed { index, label ->
                    label.placeRelative(index * segment, (height - label.height) / 2)
                }
            }
        }
    }
}
