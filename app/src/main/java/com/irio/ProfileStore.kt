package com.irio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val PROFILE_DEFAULT_NAME = "用户"
internal const val PROFILE_NAME_LIMIT = 32
internal const val PROFILE_AVATAR_OUTPUT = 512

internal data class AvatarCropWindow(
    val left: Float,
    val top: Float,
    val size: Float,
)

internal data class AvatarGesture(
    val zoom: Float,
    val offsetX: Float,
    val offsetY: Float,
)

/** 短边铺满裁切圆，圆内不留空。 */
internal fun avatarCoverScale(imageWidth: Int, imageHeight: Int, viewport: Float): Float {
    if (imageWidth <= 0 || imageHeight <= 0 || viewport <= 0f) return 1f
    return max(viewport / imageWidth, viewport / imageHeight)
}

internal fun avatarInitialOffset(imageExtent: Int, scale: Float, viewport: Float): Float =
    (viewport - imageExtent * scale) / 2f

internal fun clampAvatarOffset(offset: Float, imageExtent: Int, scale: Float, viewport: Float): Float {
    val drawn = imageExtent * scale
    if (drawn <= viewport) return (viewport - drawn) / 2f
    return offset.coerceIn(viewport - drawn, 0f)
}

/**
 * 缩放钉在裁切圆中心，不跟手指重心走。双指缩放时丢掉同时发生的平移；只有不缩放的拖动才移动图片。
 * 圆的大小不变，图片也不能被拖出圆外。
 */
internal fun avatarGesture(
    imageWidth: Int,
    imageHeight: Int,
    viewport: Float,
    zoom: Float,
    offsetX: Float,
    offsetY: Float,
    panX: Float,
    panY: Float,
    gestureZoom: Float,
    maxZoom: Float = 5f,
): AvatarGesture {
    val cover = avatarCoverScale(imageWidth, imageHeight, viewport)
    val currentZoom = if (zoom.isFinite() && zoom > 0f) zoom else 1f
    val factor = if (gestureZoom.isFinite() && gestureZoom > 0f) gestureZoom else 1f
    val nextZoom = (currentZoom * factor).coerceIn(1f, maxZoom)
    val oldScale = cover * currentZoom
    val newScale = cover * nextZoom
    val center = viewport / 2f
    val anchorX = if (oldScale > 0f) (center - offsetX) / oldScale else 0f
    val anchorY = if (oldScale > 0f) (center - offsetY) / oldScale else 0f
    val panning = factor == 1f
    val panDeltaX = if (panning) panX else 0f
    val panDeltaY = if (panning) panY else 0f
    return AvatarGesture(
        zoom = nextZoom,
        offsetX = clampAvatarOffset(center - anchorX * newScale + panDeltaX, imageWidth, newScale, viewport),
        offsetY = clampAvatarOffset(center - anchorY * newScale + panDeltaY, imageHeight, newScale, viewport),
    )
}

internal data class AvatarImageFrame(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

/** 图片在裁切区域里的位置。offset 相对圆的左上角，再加上圆自己的位置。 */
internal fun avatarImageFrame(
    circleLeft: Float,
    circleTop: Float,
    offsetX: Float,
    offsetY: Float,
    imageWidth: Int,
    imageHeight: Int,
    scale: Float,
): AvatarImageFrame = AvatarImageFrame(
    left = circleLeft + offsetX,
    top = circleTop + offsetY,
    width = imageWidth * scale,
    height = imageHeight * scale,
)

/** 屏幕上那颗固定圆对应的原图正方形。 */
internal fun avatarCropWindow(
    imageWidth: Int,
    imageHeight: Int,
    viewport: Float,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
): AvatarCropWindow {
    if (imageWidth <= 0 || imageHeight <= 0 || viewport <= 0f) return AvatarCropWindow(0f, 0f, 0f)
    val safeScale = if (scale.isFinite() && scale > 0f) scale else avatarCoverScale(imageWidth, imageHeight, viewport)
    val left = (-offsetX / safeScale).coerceIn(0f, imageWidth.toFloat())
    val top = (-offsetY / safeScale).coerceIn(0f, imageHeight.toFloat())
    val size = min(viewport / safeScale, min(imageWidth - left, imageHeight - top))
    return AvatarCropWindow(left, top, size.coerceAtLeast(0f))
}

internal fun profileSampleSize(width: Int, height: Int, maxSide: Int = 2048): Int {
    if (width <= 0 || height <= 0 || maxSide <= 0) return 1
    var sample = 1
    while (width / sample > maxSide || height / sample > maxSide) sample *= 2
    return sample
}

internal fun normalizeProfileName(value: String?): String {
    val trimmed = value.orEmpty().trim().replace(Regex("\\s+"), " ")
    return trimmed.ifBlank { PROFILE_DEFAULT_NAME }.take(PROFILE_NAME_LIMIT)
}

internal fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.postRotate(90f)
            matrix.preScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.postRotate(270f)
            matrix.preScale(-1f, 1f)
        }
        else -> return bitmap
    }
    return runCatching {
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }.getOrElse { bitmap }
}

internal fun cropAvatarBitmap(
    source: Bitmap,
    window: AvatarCropWindow,
    output: Int = PROFILE_AVATAR_OUTPUT,
): Bitmap? {
    if (output <= 0 || window.size < 1f || source.width <= 0 || source.height <= 0) return null
    val left = window.left.roundToInt().coerceIn(0, source.width - 1)
    val top = window.top.roundToInt().coerceIn(0, source.height - 1)
    val right = (window.left + window.size).roundToInt().coerceIn(left + 1, source.width)
    val bottom = (window.top + window.size).roundToInt().coerceIn(top + 1, source.height)
    val result = Bitmap.createBitmap(output, output, Bitmap.Config.ARGB_8888)
    Canvas(result).drawBitmap(
        source,
        Rect(left, top, right, bottom),
        Rect(0, 0, output, output),
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
    )
    return result
}

internal fun decodeProfilePhoto(context: Context, uri: Uri, maxSide: Int = 2048): Bitmap? {
    val temp = File(context.cacheDir, "profile-incoming-${System.nanoTime()}")
    return try {
        val copied = context.contentResolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        } != null
        if (!copied || !temp.isFile || temp.length() == 0L) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(temp.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val decoded = BitmapFactory.decodeFile(
            temp.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = profileSampleSize(bounds.outWidth, bounds.outHeight, maxSide) },
        ) ?: return null
        val orientation = runCatching {
            ExifInterface(temp.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_UNDEFINED,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_UNDEFINED)
        applyExifOrientation(decoded, orientation)
    } catch (_: Exception) {
        null
    } finally {
        temp.delete()
    }
}

object ProfileStore {
    private const val PREFS = "profile_settings"
    private const val NAME = "display_name"

    var name by mutableStateOf(PROFILE_DEFAULT_NAME)
        private set

    var avatar by mutableStateOf<ImageBitmap?>(null)
        private set

    @Volatile
    private var loaded = false

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val app = context.applicationContext
            name = normalizeProfileName(preferences(app).getString(NAME, null))
            avatar = avatarFile(app).takeIf { it.isFile }?.let { file ->
                BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
            }
            loaded = true
        }
    }

    fun saveName(context: Context, value: String) {
        val next = normalizeProfileName(value)
        name = next
        preferences(context.applicationContext).edit().putString(NAME, next).apply()
    }

    suspend fun saveAvatar(context: Context, bitmap: Bitmap) {
        val app = context.applicationContext
        withContext(Dispatchers.IO) { writeAvatar(app, bitmap) }
        avatar = bitmap.asImageBitmap()
    }

    fun clearAvatar(context: Context) {
        avatarFile(context.applicationContext).delete()
        avatar = null
    }

    internal fun resetForTest(context: Context) {
        forgetForTest()
        preferences(context.applicationContext).edit().clear().apply()
        avatarFile(context.applicationContext).delete()
    }

    internal fun forgetForTest() {
        loaded = false
        name = PROFILE_DEFAULT_NAME
        avatar = null
    }

    private fun writeAvatar(context: Context, bitmap: Bitmap) {
        val dir = File(context.filesDir, "profile").apply { mkdirs() }
        val target = File(dir, "avatar.png")
        val temp = File(dir, ".avatar-${System.nanoTime()}.png")
        temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    private fun avatarFile(context: Context) = File(context.filesDir, "profile/avatar.png")

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
