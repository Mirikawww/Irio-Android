package com.irio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID

internal fun attachmentViewChooser(uri: Uri, attachment: ChatAttachment): Intent {
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, attachment.mimeType.ifBlank { "*/*" })
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return Intent.createChooser(view, attachment.name)
}

internal fun Context.copyAttachment(uri: Uri): ChatAttachment? {
    val name =
        contentResolver
            .query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) cursor.getString(nameIndex) else null
            }
    val mimeType = contentResolver.getType(uri)?.lowercase()?.ifBlank { null } ?: mimeFromName(name)
    val dir = File(filesDir, "attachments").apply { mkdirs() }
    val target = File(dir, "${UUID.randomUUID()}${extensionFor(mimeType).ifBlank { "" }}")
    contentResolver.openInputStream(uri)?.use { input ->
        target.outputStream().use { output -> input.copyTo(output) }
    } ?: return null
    if (!target.exists() || target.length() <= 0L) {
        target.delete()
        return null
    }
    return ChatAttachment(
        name = name?.ifBlank { null } ?: "attachment${extensionFor(mimeType)}",
        mimeType = mimeType,
        filePath = target.absolutePath,
    )
}

private fun mimeFromName(name: String?): String {
    val extension = name?.substringAfterLast('.', "").orEmpty().lowercase()
    return when (extension) {
        "png" -> "image/png"
        "jpg",
        "jpeg" -> "image/jpeg"
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
