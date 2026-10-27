package com.irio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

internal enum class ImageUploadFormat(val label: String, val stored: String, val mimeType: String, val extension: String) {
    WEBP("WebP", "webp", "image/webp", "webp"),
    JPEG("JPEG", "jpeg", "image/jpeg", "jpg");

    companion object {
        fun fromStored(value: String?): ImageUploadFormat = entries.firstOrNull { it.stored.equals(value, ignoreCase = true) } ?: WEBP
    }
}

internal data class ImageUploadOptions(val enabled: Boolean = true, val quality: Int = 90, val format: ImageUploadFormat = ImageUploadFormat.WEBP) {
    val compressionQuality: Int get() = quality.coerceIn(40, 100)
}

internal data class EncodedUpload(val mimeType: String, val base64: String) {
    fun dataUrl(): String = "data:$mimeType;base64,$base64"
}
internal data class UploadBytes(val mimeType: String, val bytes: ByteArray)

/** Request-local options/cache: retries and tool rounds reuse the same immutable image encoding. */
internal class ImageUploadEncoding(private val options: ImageUploadOptions = ThemeStore.imageUploadOptions()) {
    private data class Key(val path: String, val size: Long, val modified: Long, val mime: String)
    private val cache = LinkedHashMap<Key, EncodedUpload>(8, 0.75f, true)
    private var cachedChars = 0L

    fun encode(attachment: ChatAttachment): EncodedUpload {
        val file = File(attachment.filePath)
        val key = Key(file.absolutePath, file.length(), file.lastModified(), attachment.mimeType)
        cache[key]?.let { return it }
        val prepared = prepare(attachment)
        val result = EncodedUpload(prepared.mimeType, Base64.getEncoder().encodeToString(prepared.bytes))
        // Bound retained memory; never retain arbitrarily large document/video encodings.
        if (attachment.isImage && result.base64.length <= MAX_CACHED_CHARS) {
            cache[key] = result
            cachedChars += result.base64.length
            while (cachedChars > MAX_CACHED_CHARS) {
                val oldest = cache.entries.iterator().next()
                cachedChars -= oldest.value.base64.length
                cache.remove(oldest.key)
            }
        }
        return result
    }

    fun prepare(attachment: ChatAttachment): UploadBytes {
        val file = File(attachment.filePath)
        val converted = if (options.enabled && attachment.isImage) convert(file) else null
        return UploadBytes(if (converted != null) options.format.mimeType else attachment.mimeType, converted ?: file.readBytes())
    }

    private fun convert(file: File): ByteArray? {
        return try {
            val header = ByteArray(32)
            val read = file.inputStream().use { it.read(header) }
            // Never collapse a GIF or animated WebP into a still image.
            if (read >= 6 && String(header, 0, 3, Charsets.US_ASCII) == "GIF") return null
            if (read >= 21 && String(header, 8, 4, Charsets.US_ASCII) == "WEBP" &&
                String(header, 12, 4, Charsets.US_ASCII) == "VP8X" && header[20].toInt() and 2 != 0) return null
            val decoded = BitmapFactory.decodeFile(file.absolutePath) ?: return null
            var oriented: Bitmap = decoded
            var opaque: Bitmap? = null
            try {
                val orientation = runCatching {
                    ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
                val matrix = Matrix().apply {
                    when (orientation) {
                        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                        ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                        ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                        ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                        ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                        ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                        ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
                    }
                }
                if (!matrix.isIdentity) oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                if (options.format == ImageUploadFormat.JPEG && oriented.hasAlpha()) {
                    opaque = Bitmap.createBitmap(oriented.width, oriented.height, Bitmap.Config.ARGB_8888)
                    Canvas(opaque).apply { drawColor(Color.WHITE); drawBitmap(oriented, 0f, 0f, null) }
                }
                ByteArrayOutputStream().use { output ->
                    val format = when (options.format) {
                        ImageUploadFormat.WEBP -> Bitmap.CompressFormat.WEBP_LOSSY
                        ImageUploadFormat.JPEG -> Bitmap.CompressFormat.JPEG
                    }
                    if ((opaque ?: oriented).compress(format, options.compressionQuality, output)) output.toByteArray() else null
                }
            } finally {
                opaque?.recycle()
                if (oriented !== decoded) oriented.recycle()
                decoded.recycle()
            }
        } catch (_: Exception) {
            // Unsupported/corrupt images retain their exact original bytes and MIME, never disappear.
            null
        }
    }

    private companion object { const val MAX_CACHED_CHARS = 16 * 1024 * 1024 }
}
