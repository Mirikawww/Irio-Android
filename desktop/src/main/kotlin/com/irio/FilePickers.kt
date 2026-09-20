package com.irio

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.FilenameFilter
import java.util.UUID

object FilePickers {
    fun pickPhotos(): List<File> = pick(imagesOnly = true)

    fun pickFiles(): List<File> = pick(imagesOnly = false)

    /** 保存文件对话框；用户取消返回 null。 */
    fun saveFile(defaultName: String): File? {
        val dialog = FileDialog(null as Frame?, "保存图片", FileDialog.SAVE)
        dialog.file = defaultName
        dialog.isVisible = true
        val dir = dialog.directory ?: return null
        val name = dialog.file ?: return null
        return File(dir, name)
    }

    private fun pick(imagesOnly: Boolean): List<File> {
        val dialog = FileDialog(null as Frame?, if (imagesOnly) "选择照片" else "选择文件", FileDialog.LOAD)
        dialog.isMultipleMode = true
        if (imagesOnly) {
            dialog.filenameFilter = FilenameFilter { _, name ->
                val ext = name.substringAfterLast('.', "").lowercase()
                ext in setOf("png", "jpg", "jpeg", "webp", "gif", "mp4", "mov", "webm")
            }
        }
        dialog.isVisible = true
        return dialog.files?.toList().orEmpty()
    }
}

fun copyAttachment(source: File): ChatAttachment? {
    if (!source.exists() || source.length() <= 0L) return null
    val name = source.name
    val mimeType = mimeFromName(name)
    val target = File(AppPaths.attachments, "${UUID.randomUUID()}${extensionFor(mimeType).ifBlank { "" }}")
    source.copyTo(target, overwrite = true)
    if (!target.exists() || target.length() <= 0L) {
        target.delete()
        return null
    }
    return ChatAttachment(
        name = name.ifBlank { "attachment${extensionFor(mimeType)}" },
        mimeType = mimeType,
        filePath = target.absolutePath,
    )
}

fun mimeFromName(name: String?): String {
    val extension = name?.substringAfterLast('.', "").orEmpty().lowercase()
    return when (extension) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "mp4" -> "video/mp4"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        "md" -> "text/markdown"
        "json" -> "application/json"
        else -> "application/octet-stream"
    }
}
