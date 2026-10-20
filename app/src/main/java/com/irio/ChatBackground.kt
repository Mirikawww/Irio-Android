package com.irio

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import java.io.File
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Windows 壁纸「填充」：等比放大到盖住整个区域，居中裁掉超出的部分。
 * 不拉伸，也不在上下左右留边。
 */
internal data class ChatBackgroundFill(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
    val drawnWidth: Float,
    val drawnHeight: Float,
)

internal fun chatBackgroundFill(
    srcWidth: Float,
    srcHeight: Float,
    dstWidth: Float,
    dstHeight: Float,
): ChatBackgroundFill? {
    if (srcWidth <= 0f || srcHeight <= 0f || dstWidth <= 0f || dstHeight <= 0f) return null
    val scale = max(dstWidth / srcWidth, dstHeight / srcHeight)
    val drawnWidth = srcWidth * scale
    val drawnHeight = srcHeight * scale
    return ChatBackgroundFill(
        scale = scale,
        offsetX = (dstWidth - drawnWidth) / 2f,
        offsetY = (dstHeight - drawnHeight) / 2f,
        drawnWidth = drawnWidth,
        drawnHeight = drawnHeight,
    )
}

/** [chatBackgroundFill]. [ContentScale.Fit] would letterbox. */
internal val ChatBackgroundContentScale: ContentScale = ContentScale.Crop

/** 效果还不行。入口留在外观设置里，但不能选、不能删、也不会铺到聊天上。改回 true 即恢复。 */
internal const val ChatBackgroundEnabled = false

internal fun acceptsChatBackgroundMime(mime: String?): Boolean {
    // 没有类型或通用二进制流时，后面再用解码结果判断是不是图片。
    if (mime.isNullOrBlank() || mime.equals("application/octet-stream", ignoreCase = true)) return true
    return mime.lowercase().startsWith("image/")
}

internal fun isDecodableImage(file: File): Boolean {
    if (!file.isFile || file.length() <= 0L || !hasImageHeader(file)) return false
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, options)
    return options.outWidth > 0 && options.outHeight > 0
}

private fun hasImageHeader(file: File): Boolean {
    val header = ByteArray(12)
    val count = runCatching { file.inputStream().use { it.read(header) } }.getOrDefault(-1)
    if (count < 12) return false
    if (header[0] == 0x89.toByte() && header[1] == 0x50.toByte() && header[2] == 0x4E.toByte() && header[3] == 0x47.toByte()) {
        return true
    }
    if (header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()) return true
    if (header[0] == 'G'.code.toByte() && header[1] == 'I'.code.toByte() && header[2] == 'F'.code.toByte()) return true
    if (header[0] == 'B'.code.toByte() && header[1] == 'M'.code.toByte()) return true
    val riffWebp = header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() &&
        header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte() &&
        header[8] == 'W'.code.toByte() && header[9] == 'E'.code.toByte() &&
        header[10] == 'B'.code.toByte() && header[11] == 'P'.code.toByte()
    if (riffWebp) return true
    val ftyp = header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() &&
        header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte()
    if (!ftyp) return false
    val brand = String(header, 8, 4, Charsets.US_ASCII).lowercase()
    return brand in setOf("heic", "heix", "heif", "mif1", "msf1", "avif", "avis")
}

object ChatBackgroundStore {
    private const val PREFS = "appearance_settings"
    private const val KEY = "chat_background_name"
    private const val DIRECTORY = "chat-background"

    var fileName by mutableStateOf<String?>(null)
        private set

    @Volatile
    private var loaded = false

    fun directory(context: Context): File = File(context.filesDir, DIRECTORY)

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val name = safeName(
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null),
            )
            val file = name?.let { File(directory(context), it) }
            fileName = if (file != null && file.isFile) name else null
            if (name != null && fileName == null) {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
            }
            directory(context).listFiles()?.forEach { child ->
                if (child.name != fileName) child.delete()
            }
            loaded = true
        }
    }

    suspend fun save(context: Context, uri: Uri): Boolean {
        ensureLoaded(context)
        val app = context.applicationContext
        val mime = app.contentResolver.getType(uri)
        if (!acceptsChatBackgroundMime(mime)) return false
        return withContext(Dispatchers.IO) {
            val dir = directory(app)
            if (!dir.isDirectory && !dir.mkdirs()) return@withContext false
            val temp = File(dir, ".incoming-${System.nanoTime()}")
            val copied = runCatching {
                app.contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                } != null
            }.getOrDefault(false)
            if (!copied || !isDecodableImage(temp)) {
                temp.delete()
                return@withContext false
            }
            val name = "background-${System.nanoTime()}"
            val target = File(dir, name)
            val moved = temp.renameTo(target) || runCatching {
                temp.copyTo(target, overwrite = true)
                temp.delete()
                true
            }.getOrDefault(false)
            if (!moved || !target.isFile) {
                temp.delete()
                target.delete()
                return@withContext false
            }
            withContext(Dispatchers.Main.immediate) {
                fileName = name
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, name).apply()
            }
            dir.listFiles()?.forEach { child ->
                if (child.name != name) child.delete()
            }
            true
        }
    }

    fun clear(context: Context) {
        ensureLoaded(context)
        fileName = null
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY)
            .apply()
        directory(context).deleteRecursively()
    }

    internal fun resetForTest(context: Context) {
        loaded = false
        fileName = null
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
        directory(context).deleteRecursively()
    }

    private fun safeName(name: String?): String? {
        if (name.isNullOrBlank()) return null
        if (name.contains('/') || name.contains('\\') || name.contains("..")) return null
        return name
    }
}

@Composable
internal fun ChatBackgroundImage(modifier: Modifier = Modifier) {
    if (!ChatBackgroundEnabled) return
    val name = ChatBackgroundStore.fileName ?: return
    val context = LocalContext.current
    val file = File(ChatBackgroundStore.directory(context), name).takeIf { it.isFile } ?: return
    AsyncImage(
        model = file,
        contentDescription = null,
        modifier = modifier.fillMaxSize(),
        alignment = Alignment.Center,
        contentScale = ChatBackgroundContentScale,
    )
}
