package com.irio

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.ui.components.ButtonStyle
import com.composables.ui.components.Text
import com.composables.ui.components.TextField
import com.composables.ui.theme.backgroundColor
import com.composables.ui.theme.colors
import com.composables.ui.theme.fieldShape
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.onBackgroundColor
import com.composables.ui.theme.primaryColor
import com.composables.ui.theme.shapes
import com.composeunstyled.theme.Theme
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ProfileAvatarSize = 72.dp

@Composable
internal fun SettingsProfileHeader(onPickAvatar: () -> Unit, onEditName: () -> Unit) {
    val context = LocalContext.current
    val name = ProfileStore.name
    val shown = if (name == PROFILE_DEFAULT_NAME) tr(name) else name
    var confirmDelete by remember { mutableStateOf(false) }
    val hasAvatar = ProfileStore.avatar != null
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(ProfileAvatarSize)
                .clip(CircleShape)
                .background(Theme[colors][primaryColor])
                .combinedClickable(
                    onClick = onPickAvatar,
                    onLongClick = if (hasAvatar) {
                        { confirmDelete = true }
                    } else {
                        null
                    },
                )
                .semantics { contentDescription = tr("头像") },
        ) {
            ProfileStore.avatar?.let { avatar ->
                Image(
                    bitmap = avatar,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Text(
            shown,
            modifier = Modifier
                .weight(1f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onEditName,
                ),
            color = Theme[colors][onBackgroundColor],
            fontSize = 20.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    IrioAlertDialog(
        visible = confirmDelete,
        onDismissRequest = { confirmDelete = false },
    ) {
        Column {
            DialogTitle(tr("删除头像"))
            Spacer(Modifier.height(12.dp))
            Text(tr("确定要删除头像吗？"))
            Spacer(Modifier.height(16.dp))
            DialogButtonRow(
                confirmLabel = tr("删除"),
                onConfirm = {
                    ProfileStore.clearAvatar(context)
                    confirmDelete = false
                },
                onCancel = { confirmDelete = false },
                confirmStyle = ButtonStyle.Destructive,
            )
        }
    }
}

@Composable
internal fun ProfileNameDialog(visible: Boolean, onDismiss: () -> Unit) {
    if (!visible) return
    val context = LocalContext.current
    val state = rememberTextFieldState(ProfileStore.name)
    IrioAlertDialog(
        visible = true,
        onDismissRequest = onDismiss,
        modifier = Modifier.imePadding(),
    ) {
        Column {
            DialogTitle(tr("用户名称"))
            Spacer(Modifier.height(16.dp))
            TextField(
                state = state,
                modifier = Modifier.fillMaxWidth().then(rememberInputFieldBorder()),
                accessibilityLabel = tr("用户名称"),
                placeholder = {
                    Text(
                        tr("用户"),
                        style = FieldTextStyle.copy(baselineShift = BaselineShift(0.12f)),
                    )
                },
                textStyle = FieldTextStyle,
                lineHeight = 20.sp,
                fontSize = 16.sp,
                shape = Theme[shapes][fieldShape],
                contentPadding = PaddingValues(12.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                lineLimits = TextFieldLineLimits.SingleLine,
                inputTransformation = InputTransformation.maxLength(PROFILE_NAME_LIMIT),
            )
            Spacer(Modifier.height(16.dp))
            DialogButtonRow(
                confirmLabel = tr("保存"),
                onConfirm = {
                    ProfileStore.saveName(context, state.text.toString())
                    onDismiss()
                },
                onCancel = onDismiss,
            )
        }
    }
}

/**
 * 裁切圆大小固定。图片在圆后面放大、平移，圆本身不跟着变。
 */
@Composable
internal fun AvatarCropScreen(source: Bitmap, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    val image = remember(source) { source.asImageBitmap() }
    var boxWidth by remember { mutableFloatStateOf(0f) }
    var boxHeight by remember { mutableFloatStateOf(0f) }
    var viewportPx by remember { mutableFloatStateOf(0f) }
    var zoom by remember(source) { mutableFloatStateOf(1f) }
    var offsetX by remember(source) { mutableFloatStateOf(0f) }
    var offsetY by remember(source) { mutableFloatStateOf(0f) }
    BackHandler(enabled = !saving) { onDismiss() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Theme[colors][backgroundColor])
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds()
                    .onSizeChanged { size ->
                        boxWidth = size.width.toFloat()
                        boxHeight = size.height.toFloat()
                        if (size.width <= 0 || size.height <= 0) return@onSizeChanged
                        val pad = with(density) { 48.dp.toPx() }
                        val maxSide = with(density) { 300.dp.toPx() }
                        val available = min(size.width, size.height) - pad
                        if (available < 1f) return@onSizeChanged
                        val next = available.coerceAtMost(maxSide)
                        if (next == viewportPx) return@onSizeChanged
                        viewportPx = next
                        zoom = 1f
                        val cover = avatarCoverScale(source.width, source.height, next)
                        offsetX = avatarInitialOffset(source.width, cover, next)
                        offsetY = avatarInitialOffset(source.height, cover, next)
                    }
                    .pointerInput(source, viewportPx) {
                        if (viewportPx <= 0f) return@pointerInput
                        detectTransformGestures { _, pan, gestureZoom, _ ->
                            if (saving) return@detectTransformGestures
                            val next = avatarGesture(
                                imageWidth = source.width,
                                imageHeight = source.height,
                                viewport = viewportPx,
                                zoom = zoom,
                                offsetX = offsetX,
                                offsetY = offsetY,
                                panX = pan.x,
                                panY = pan.y,
                                gestureZoom = gestureZoom,
                            )
                            zoom = next.zoom
                            offsetX = next.offsetX
                            offsetY = next.offsetY
                        }
                    },
            ) {
                if (viewportPx > 0f) {
                    val cover = avatarCoverScale(source.width, source.height, viewportPx)
                    val scale = cover * zoom
                    val circleLeft = ((boxWidth - viewportPx) / 2f).coerceAtLeast(0f)
                    val circleTop = ((boxHeight - viewportPx) / 2f).coerceAtLeast(0f)
                    val frame = avatarImageFrame(
                        circleLeft,
                        circleTop,
                        offsetX,
                        offsetY,
                        source.width,
                        source.height,
                        scale,
                    )
                    Canvas(Modifier.fillMaxSize()) {
                        drawImage(
                            image = image,
                            dstOffset = IntOffset(frame.left.roundToInt(), frame.top.roundToInt()),
                            dstSize = IntSize(
                                frame.width.roundToInt().coerceAtLeast(1),
                                frame.height.roundToInt().coerceAtLeast(1),
                            ),
                            filterQuality = FilterQuality.Medium,
                        )
                        val hole = Path().apply {
                            addRect(Rect(0f, 0f, size.width, size.height))
                            addOval(Rect(circleLeft, circleTop, circleLeft + viewportPx, circleTop + viewportPx))
                            fillType = PathFillType.EvenOdd
                        }
                        drawPath(hole, Color.Black.copy(alpha = 0.55f))
                        drawOval(
                            color = Color.White,
                            topLeft = Offset(circleLeft, circleTop),
                            size = Size(viewportPx, viewportPx),
                            style = Stroke(width = 2.dp.toPx()),
                        )
                    }
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    tr("拖动并双指缩放"),
                    color = Theme[colors][mutedColor],
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
                Spacer(Modifier.height(12.dp))
                DialogButtonRow(
                    confirmLabel = tr("完成"),
                    confirmEnabled = !saving && viewportPx > 0f,
                    cancelEnabled = !saving,
                    onConfirm = {
                        if (saving || viewportPx <= 0f) return@DialogButtonRow
                        saving = true
                        val scale = avatarCoverScale(source.width, source.height, viewportPx) * zoom
                        val window = avatarCropWindow(
                            source.width,
                            source.height,
                            viewportPx,
                            scale,
                            offsetX,
                            offsetY,
                        )
                        scope.launch {
                            val cropped = withContext(Dispatchers.IO) { cropAvatarBitmap(source, window) }
                            if (cropped != null) ProfileStore.saveAvatar(context, cropped)
                            onDismiss()
                        }
                    },
                    onCancel = { if (!saving) onDismiss() },
                )
            }
        }
    }
}
