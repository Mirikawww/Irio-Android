package com.irio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.composables.ui.components.ButtonSize
import com.composables.ui.components.ButtonStyle
import com.composables.ui.components.DropdownMenu
import com.composables.ui.components.DropdownMenuAlignment
import com.composables.ui.components.DropdownMenuItem
import com.composables.ui.components.DropdownMenuPanel
import com.composables.ui.components.Icon
import com.composables.ui.components.IconButton
import com.composables.ui.components.Text
import com.composables.ui.theme.colors
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.onPanelColor
import com.composeunstyled.theme.Theme

class SearchSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setIrioContent { SearchSettingsScreen(onBack = { finish() }) }
    }
}

@Composable
internal fun SearchSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var provider by remember { mutableStateOf(SearchStore.loadProvider(context)) }
    var providerMenuOpen by remember { mutableStateOf(false) }
    val apiKey = rememberTextFieldState(SearchStore.loadApiKey(context, provider))
    var maxResults by remember { mutableIntStateOf(SearchStore.loadMaxResults(context, provider)) }
    val imageApiKey = rememberTextFieldState(SearchStore.loadImageApiKey(context))
    var imageMaxResults by remember { mutableIntStateOf(SearchStore.loadImageMaxResults(context)) }
    val currentProvider by rememberUpdatedState(provider)
    val lifecycleOwner = LocalLifecycleOwner.current

    fun persistCurrent() {
        SearchStore.saveApiKey(context, apiKey.text.toString(), currentProvider)
        SearchStore.saveProvider(context, currentProvider)
        SearchStore.saveImageApiKey(context, imageApiKey.text.toString())
    }

    fun switchProvider(next: SearchProvider) {
        if (next == provider) {
            providerMenuOpen = false
            return
        }
        SearchStore.saveApiKey(context, apiKey.text.toString(), provider)
        SearchStore.saveMaxResults(context, maxResults, provider)
        SearchStore.saveProvider(context, next)
        provider = next
        providerMenuOpen = false
        apiKey.edit { replace(0, length, SearchStore.loadApiKey(context, next)) }
        maxResults = SearchStore.loadMaxResults(context, next)
    }

    DisposableEffect(lifecycleOwner, apiKey, imageApiKey) {
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
    SettingsHazeScaffold(title = tr("搜索"), onBack = onBack) { topInset, bottomInset ->
        Column(
            modifier = Modifier.fillMaxSize().padding(bottom = bottomInset).padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()).padding(top = topInset).padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsSectionCard {
                DropdownMenu(
                    expanded = providerMenuOpen,
                    onExpandedChange = { providerMenuOpen = it },
                    modifier = Modifier.fillMaxWidth().settingsSearchAnchor("search.provider", edge = SettingsSearchEdge.Top),
                    alignment = DropdownMenuAlignment.End,
                    panel = {
                        DropdownMenuPanel {
                            SearchProvider.entries.forEach { option ->
                                DropdownMenuItem(onClick = { switchProvider(option) }, modifier = Modifier.fillMaxWidth()) {
                                    Text(tr(option.label), color = Theme[colors][onPanelColor])
                                }
                            }
                        }
                    },
                ) {
                    val interactionSource = remember { MutableInteractionSource() }
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = { providerMenuOpen = !providerMenuOpen },
                        ).settingsDropdownInset(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(tr("搜索提供商"), color = Theme[colors][onPanelColor])
                            Text(tr(provider.label), color = Theme[colors][mutedColor], fontSize = 13.sp)
                        }
                        IconButton(
                            onClick = { providerMenuOpen = !providerMenuOpen },
                            style = ButtonStyle.Ghost,
                            buttonSize = ButtonSize.Small,
                            shape = ContinuousCapsule,
                            modifier = Modifier.size(32.dp),
                            interactionSource = interactionSource,
                        ) {
                            Icon(
                                imageVector = IrioIcons.ChevronDown,
                                contentDescription = tr("选择搜索提供方"),
                                tint = Theme[colors][onPanelColor],
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
                Column(Modifier.padding(vertical = 8.dp)) {
                    ProviderField(
                        label = tr("%s API 密钥").format(tr(provider.label)),
                        state = apiKey,
                        keyboardType = KeyboardType.Password,
                        mask = true,
                        trailingSpace = false,
                    )
                }
                SettingsSlider(
                    modifier = Modifier.settingsSearchAnchor("search.limit", edge = SettingsSearchEdge.Bottom),
                    label = tr("单次搜索上限"),
                    value = maxResults,
                    valueRange = provider.maxResultsMin..provider.maxResultsMax,
                    onValueChange = {
                        val next = provider.clampMaxResults(it)
                        maxResults = next
                        SearchStore.saveMaxResults(context, next, provider)
                    },
                )
            }
            SettingsSectionCard {
                Column(Modifier.settingsSearchAnchor("search.image", edge = SettingsSearchEdge.Top).fillMaxWidth().settingsDropdownInset()) {
                    Text(tr("图片搜索提供商"), color = Theme[colors][onPanelColor])
                    Text("SerpAPI", color = Theme[colors][mutedColor], fontSize = 13.sp)
                }
                Column(Modifier.padding(vertical = 8.dp)) {
                    ProviderField(
                        label = tr("SerpAPI 密钥"),
                        state = imageApiKey,
                        keyboardType = KeyboardType.Password,
                        mask = true,
                        trailingSpace = false,
                    )
                }
                SettingsSlider(
                    modifier = Modifier.settingsSearchAnchor("search.imageLimit", edge = SettingsSearchEdge.Bottom),
                    label = tr("单次搜索上限"),
                    value = imageMaxResults,
                    valueRange = SearchStore.IMAGE_RESULTS_MIN..SearchStore.IMAGE_RESULTS_MAX,
                    onValueChange = {
                        imageMaxResults = it
                        SearchStore.saveImageMaxResults(context, it)
                    },
                )
            }
        }
    }
}
