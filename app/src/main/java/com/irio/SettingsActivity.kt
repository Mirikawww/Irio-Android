package com.irio

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.ui.components.ButtonStyle
import com.composables.ui.components.Icon
import com.composables.ui.components.IconButton
import com.composables.ui.components.Text
import com.composables.ui.theme.backgroundColor
import com.composables.ui.theme.colors
import com.composables.ui.theme.mutedColor
import com.composeunstyled.theme.Theme

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProfileStore.ensureLoaded(this)
        setIrioContent {
            SettingsScreen(onBack = { finish() })
        }
    }
}

@Composable
internal fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val softwareInfo = remember { androidSoftwareInfo(context) }
    var editingName by remember { mutableStateOf(false) }
    var cropSource by remember { mutableStateOf<Bitmap?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }
    var searchAnchor by remember { mutableStateOf(Rect.Zero) }
    val searchFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val background = Theme[colors][backgroundColor]
    val searchProgress by animateFloatAsState(
        targetValue = if (searchExpanded) 1f else 0f,
        animationSpec = tween(280),
        label = "settingsSearchFade",
    )
    val pickAvatar = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val decoded = withContext(Dispatchers.IO) { decodeProfilePhoto(context, uri) }
            if (decoded != null) cropSource = decoded
        }
    }

    BackHandler(enabled = searchExpanded) {
        focusManager.clearFocus()
        searchQuery = ""
        searchExpanded = false
    }
    Box(Modifier.fillMaxSize().background(background)) {
    Box(Modifier.graphicsLayer { alpha = 1f - searchProgress }) {
    SettingsHazeScaffold(
        title = tr("设置"),
        onBack = onBack,
    ) { topInset, bottomInset ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = bottomInset),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(top = topInset)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            ) {
                SettingsProfileHeader(
                    onPickAvatar = {
                        pickAvatar.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    onEditName = { editingName = true },
                    searchField = {
                        SettingsSearchSlot(
                            showField = !searchExpanded && searchProgress <= 0f,
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            onExpanded = { searchExpanded = true },
                            focusRequester = searchFocus,
                            onBounds = { searchAnchor = it },
                        )
                    },
                )
                Spacer(Modifier.height(20.dp))
                ListRowButton(
                    label = tr("提供商"),
                    icon = Icons.Rounded.DataObject,
                    onClick = {
                        context.startActivity(Intent(context, ProviderSettingsActivity::class.java))
                    },
                )
                Spacer(Modifier.height(12.dp))
                ListRowButton(
                    label = tr("模型"),
                    icon = Icons.Rounded.SmartToy,
                    onClick = {
                        context.startActivity(Intent(context, ModelSettingsActivity::class.java))
                    },
                )
                Spacer(Modifier.height(12.dp))
                ListRowButton(
                    label = tr("搜索"),
                    icon = Icons.Rounded.Search,
                    onClick = {
                        context.startActivity(Intent(context, SearchSettingsActivity::class.java))
                    },
                )
                Spacer(Modifier.height(12.dp))
                ListRowButton(
                    label = "TTS/STT",
                    icon = Icons.Rounded.RecordVoiceOver,
                    onClick = {
                        context.startActivity(Intent(context, SpeechSettingsActivity::class.java))
                    },
                )
                Spacer(Modifier.height(12.dp))
                ListRowButton(
                    label = tr("记忆"),
                    icon = Icons.Rounded.Memory,
                    onClick = {
                        context.startActivity(Intent(context, MemorySettingsActivity::class.java))
                    },
                )
                Spacer(Modifier.height(12.dp))
                ListRowButton(
                    label = "MCP",
                    iconPainter = painterResource(R.drawable.ic_mcp),
                    onClick = { context.startActivity(Intent(context, McpSettingsActivity::class.java)) },
                )
                Spacer(Modifier.height(12.dp))
                ListRowButton(
                    label = tr("外观与偏好"),
                    icon = Icons.Rounded.Palette,
                    onClick = {
                        context.startActivity(Intent(context, AppearanceSettingsActivity::class.java))
                    },
                )
                Spacer(Modifier.height(12.dp))
                ListRowButton(
                    label = tr("互连"),
                    icon = Icons.Rounded.Devices,
                    onClick = {
                        context.startActivity(Intent(context, LinkSettingsActivity::class.java))
                    },
                )
                Spacer(Modifier.height(12.dp))
                ListRowButton(
                    label = tr("Linux 沙盒"),
                    icon = Icons.Rounded.Terminal,
                    onClick = {
                        context.startActivity(Intent(context, LinuxSandboxSettingsActivity::class.java))
                    },
                )
                Spacer(Modifier.height(12.dp))
                ListRowButton(
                    label = tr("其他"),
                    icon = Icons.Rounded.MoreHoriz,
                    onClick = {
                        context.startActivity(Intent(context, OtherSettingsActivity::class.java))
                    },
                )
            }
            Text(
                softwareInfo,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                color = Theme[colors][mutedColor],
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
    }
    SettingsSearchOverlay(
        query = searchQuery,
        onQueryChange = { searchQuery = it },
        expanded = searchExpanded,
        anchor = searchAnchor,
        onExpanded = { searchExpanded = true },
        onDismiss = {
            focusManager.clearFocus()
            searchQuery = ""
            searchExpanded = false
        },
        onResult = { hit ->
            focusManager.clearFocus()
            context.openSettingsSearch(hit)
        },
        focusRequester = searchFocus,
    )
    ProfileNameDialog(visible = editingName, onDismiss = { editingName = false })
    cropSource?.let { source ->
        AvatarCropScreen(source = source, onDismiss = { cropSource = null })
    }
    }
}
