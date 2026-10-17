package com.irio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
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
                    items = entries,
                    key = { it.name },
                    modifier = Modifier.fillMaxSize().padding(bottom = bottomInset),
                    contentPadding = PaddingValues(start = 16.dp, top = topInset + 16.dp, end = 16.dp, bottom = 16.dp),
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
                    bottomInset = bottomInset,
                    onDismiss = { noticeVisible = false },
                )
            }
        }
    }
}
