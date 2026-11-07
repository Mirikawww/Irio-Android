package com.irio

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.composables.ui.components.Button
import com.composables.ui.components.ButtonStyle
import com.composables.ui.components.Text
import com.composables.ui.components.TextField
import com.composables.ui.theme.colors
import com.composables.ui.theme.fieldShape
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.onPanelColor
import com.composables.ui.theme.primaryColor
import com.composables.ui.theme.shapes
import com.composeunstyled.LocalContentColor
import com.composeunstyled.theme.Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MeSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setIrioContent { MeSettingsScreen(onBack = { finish() }) }
    }
}

@Composable
internal fun MeSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val name = rememberTextFieldState(ProfileStore.loadNameInput(context))
    val lifecycleOwner = LocalLifecycleOwner.current
    var avatarActionsOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var cropSource by remember { mutableStateOf<Bitmap?>(null) }
    DisposableEffect(lifecycleOwner, name) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) ProfileStore.saveName(context, name.text.toString())
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            ProfileStore.saveName(context, name.text.toString())
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    fun receiveAvatar(uri: Uri?) {
        if (uri == null) return
        scope.launch {
            val decoded = withContext(Dispatchers.IO) { decodeProfilePhoto(context, uri) }
            if (decoded != null) cropSource = decoded
        }
    }
    val pickAlbum = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia(), ::receiveAvatar)
    val pickDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), ::receiveAvatar)
    DismissComposerFocusWhenImeHides()
    Box(Modifier.fillMaxSize()) {
        SettingsHazeScaffold(title = tr("我"), onBack = onBack) { topInset, bottomInset ->
            Column(
                modifier = Modifier.fillMaxSize()
                    .padding(bottom = bottomInset)
                    .verticalScroll(rememberScrollState())
                    .padding(top = topInset)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier.align(Alignment.CenterHorizontally).size(96.dp)
                        .settingsSearchAnchor("me.avatar", horizontalOutset = 0.dp,
                            edge = SettingsSearchEdge.All, verticalOutset = 0.dp)
                        .clip(CircleShape).background(Theme[colors][primaryColor])
                        .clickable { avatarActionsOpen = true }
                        .semantics { contentDescription = tr("头像") },
                ) {
                    ProfileStore.avatar?.let { avatar ->
                        Image(avatar, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
                TextField(
                    state = name,
                    modifier = Modifier.fillMaxWidth()
                        .settingsSearchAnchor("me.name", horizontalOutset = 0.dp,
                            edge = SettingsSearchEdge.All, verticalOutset = 0.dp)
                        .then(rememberInputFieldBorder()),
                    accessibilityLabel = tr("用户名称"),
                    placeholder = {
                        Text(tr("用户名称"), color = Theme[colors][mutedColor],
                            style = FieldTextStyle.copy(baselineShift = BaselineShift(0.12f)))
                    },
                    textStyle = FieldTextStyle, lineHeight = 20.sp, fontSize = 16.sp,
                    shape = Theme[shapes][fieldShape], contentPadding = PaddingValues(12.dp),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    inputTransformation = InputTransformation.maxLength(PROFILE_NAME_LIMIT),
                )
            }
        }
        IrioAlertDialog(visible = avatarActionsOpen, onDismissRequest = { avatarActionsOpen = false }) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                DialogTitle(tr("头像"))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            avatarActionsOpen = false
                            pickAlbum.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        style = ButtonStyle.Secondary, modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
                    ) { Text(tr("相册选择"), color = LocalContentColor.current) }
                    Button(
                        onClick = { avatarActionsOpen = false; pickDocument.launch(arrayOf("image/*")) },
                        style = ButtonStyle.Secondary, modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
                    ) { Text(tr("文件选择"), color = LocalContentColor.current) }
                }
                if (ProfileStore.avatar != null) {
                    Button(
                        onClick = { avatarActionsOpen = false; confirmDelete = true },
                        style = ButtonStyle.Destructive, modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
                    ) { Text(tr("删除头像"), color = LocalContentColor.current) }
                }
            }
        }
        IrioAlertDialog(visible = confirmDelete, onDismissRequest = { confirmDelete = false }) {
            Column {
                DialogTitle(tr("删除头像"))
                Spacer(Modifier.height(12.dp))
                Text(tr("确定要删除头像吗？"), color = Theme[colors][onPanelColor])
                Spacer(Modifier.height(16.dp))
                DialogButtonRow(
                    confirmLabel = tr("删除"), confirmStyle = ButtonStyle.Destructive,
                    onConfirm = { ProfileStore.clearAvatar(context); confirmDelete = false },
                    onCancel = { confirmDelete = false },
                )
            }
        }
        cropSource?.let { source ->
            AvatarCropScreen(source = source, onDismiss = { cropSource = null })
        }
    }
}
