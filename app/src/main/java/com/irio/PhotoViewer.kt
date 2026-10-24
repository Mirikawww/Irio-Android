package com.irio

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.composables.ui.components.ButtonStyle
import com.composables.ui.components.Icon
import com.composables.ui.components.IconButton
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.saket.telephoto.subsamplingimage.SubSamplingImage
import me.saket.telephoto.subsamplingimage.SubSamplingImageErrorReporter
import me.saket.telephoto.subsamplingimage.SubSamplingImageSource
import me.saket.telephoto.subsamplingimage.rememberSubSamplingImageState
import me.saket.telephoto.zoomable.ZoomableContentLocation
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable
import okio.Path.Companion.toPath

private object PhotoZoomHapticsDisabled : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) = Unit
}

@Composable
internal fun PhotoViewer(attachment: ChatAttachment, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val zoomState = rememberZoomableState()
    var saved by remember(attachment.filePath) { mutableStateOf(false) }
    var regionDecodeFailed by remember(attachment.filePath) { mutableStateOf(false) }
    val errorReporter =
        remember(attachment.filePath) {
            object : SubSamplingImageErrorReporter {
                override fun onImageLoadingFailed(
                    e: IOException,
                    imageSource: SubSamplingImageSource,
                ) {
                    regionDecodeFailed = true
                }
            }
        }
    Box(
        modifier = Modifier.fillMaxSize().zIndex(4f).background(Color.Black.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center,
    ) {
        // 只禁用图片缩放边界的震动，保留缩放回弹和其他控件的触觉反馈。
        CompositionLocalProvider(LocalHapticFeedback provides PhotoZoomHapticsDisabled) {
            if (!regionDecodeFailed) {
                // 按当前缩放从原文件切块，而不是先压成屏幕大小的位图再放大。
                val imageState =
                    rememberSubSamplingImageState(
                        imageSource = SubSamplingImageSource.file(attachment.filePath.toPath()),
                        zoomableState = zoomState,
                        errorReporter = errorReporter,
                    )
                SubSamplingImage(
                    state = imageState,
                    contentDescription = attachment.name,
                    modifier =
                        Modifier
                            // 整个屏幕都是图片手势区：双击缩放、双指缩放/平移。
                            // 不设 onClick：tap 判定要等 double-tap 超时窗口（~300ms），关闭有可感延迟；
                            // 关闭走返回键（NavigationBackHandler）。
                            .fillMaxSize()
                            .zoomable(zoomState),
                )
            } else {
                ZoomableDecodedImage(attachment, zoomState)
            }
        }
        // 右下角下载按钮：保存到系统下载（相册可见）
        IconButton(
            onClick = {
                if (saved) return@IconButton
                saved = true
                runCatching {
                    val values =
                        android.content.ContentValues().apply {
                            put(
                                android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                                attachment.name,
                            )
                            put(
                                android.provider.MediaStore.Images.Media.MIME_TYPE,
                                attachment.mimeType.ifBlank { "image/png" },
                            )
                        }
                    val uri =
                        context.contentResolver.insert(
                            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            values,
                        ) ?: return@runCatching
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(File(attachment.filePath).readBytes())
                    }
                }
            },
            style = ButtonStyle.Ghost,
            modifier =
                Modifier.align(Alignment.BottomEnd)
                    .padding(20.dp)
                    .background(Color.Black.copy(alpha = 0.55f), ContinuousCapsule),
        ) {
            if (saved) {
                Icon(
                    imageVector = IrioIcons.Check,
                    contentDescription = tr("已保存"),
                    tint = Color.White,
                )
            } else {
                Icon(
                    imageVector = IrioIcons.Download,
                    contentDescription = tr("下载图片"),
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
private fun ZoomableDecodedImage(attachment: ChatAttachment, zoomState: ZoomableState) {
    var bitmap by remember(attachment.filePath) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(attachment.filePath) {
        bitmap = withContext(Dispatchers.IO) { decodeOriginal(attachment.filePath) }
    }
    val image = bitmap
    DisposableEffect(zoomState) {
        val previous = zoomState.autoApplyTransformations
        zoomState.autoApplyTransformations = false
        onDispose { zoomState.autoApplyTransformations = previous }
    }
    if (image != null) {
        zoomState.setContentLocation(
            ZoomableContentLocation.unscaledAndTopLeftAligned(
                Size(image.width.toFloat(), image.height.toFloat())
            )
        )
    }
    Box(
        Modifier.fillMaxSize().zoomable(zoomState).drawBehind {
            val current = image ?: return@drawBehind
            val transformation = zoomState.contentTransformation
            if (!transformation.isSpecified) return@drawBehind
            val scale = transformation.scale
            drawImage(
                image = current,
                dstOffset =
                    IntOffset(
                        transformation.offset.x.roundToInt(),
                        transformation.offset.y.roundToInt(),
                    ),
                dstSize =
                    IntSize(
                        (current.width * scale.scaleX).roundToInt().coerceAtLeast(1),
                        (current.height * scale.scaleY).roundToInt().coerceAtLeast(1),
                    ),
                filterQuality = FilterQuality.High,
            )
        }
    )
}

private fun decodeOriginal(path: String): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val maxPixels = 24_000_000L
    var sample = 1
    while ((bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > maxPixels) {
        sample *= 2
    }
    val options =
        BitmapFactory.Options().apply {
            inSampleSize = sample
            inScaled = false
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
    return BitmapFactory.decodeFile(path, options)?.asImageBitmap()
}
