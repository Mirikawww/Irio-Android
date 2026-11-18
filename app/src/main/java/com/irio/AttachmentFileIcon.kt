package com.irio

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** File marks drawn for the compact attachment tile. Document-style marks share a fold and baseline. */
internal enum class AttachmentFileIcon(private val mark: String) {
    TEXT("M8 11.5h8 M8 14.5h8 M8 17.5h4.5"),
    MARKDOWN("M7.5 17v-5l2.5 2.5 2.5-2.5v5 M15.5 12v5m-1.35-1.35 1.35 1.35 1.35-1.35"),
    PDF(""),
    DOCUMENT("M7.75 11.5l1.6 6 2.65-3.8 2.65 3.8 1.6-6"),
    SPREADSHEET("M8 11.5h8v6H8z M8 14.5h8 M11.5 11.5v6"),
    PRESENTATION("M8 16.5v-5h8v5z M10 19h4"),
    VIDEO("M9 11.25v6.5l6-3.25z"),
    AUDIO("M8.5 13v3 M12 11v7 M15.5 12.5v4"),
    ARCHIVE("M10.65 8.6h1.25 M12.1 10.25h1.25 M10.65 11.9h1.25 M12.1 13.55h1.25 M12 15.8a1.45 1.45 0 0 1 1.45 1.45v.15a1.45 1.45 0 0 1-2.9 0v-.15A1.45 1.45 0 0 1 12 15.8z"),
    CODE("M10.35 12.35l-1.9 2.15 1.9 2.15 M13.65 12.35l1.9 2.15-1.9 2.15");

    val vector: ImageVector by lazy {
        val outline = "M6.5 2.75h6.75L19 8.5v11.25A1.5 1.5 0 0 1 17.5 21.25h-11A1.5 1.5 0 0 1 5 19.75V4.25A1.5 1.5 0 0 1 6.5 2.75z M13 2.75v4.25a1.5 1.5 0 0 0 1.5 1.5h4"
        attachmentVector("attachment:$name", "$outline $mark", 1.55f)
    }
}

// Keep the PDF mark thinner than the surrounding page outline.
internal val pdfAttachmentMark: ImageVector by lazy {
    val curve = "M7.99 17.51C8.67 17.51 10.08 16.1 11.08 14.21C12.12 12.25 12.52 10.05 11.55 10.05C10.57 10.05 10.82 11.9 11.85 13.46C12.95 15.11 14.85 16.07 15.92 15.96C16.8 15.88 16.8 14.88 15.5 14.8C13.72 14.69 11.32 15.23 9.55 15.92C7.75 16.61 7.14 17.51 7.99 17.51Z"
    attachmentVector("attachment:pdf-mark", curve, 1.1f)
}

private fun attachmentVector(name: String, path: String, strokeWidth: Float): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(
            pathData = PathParser().parsePathString(path).toNodes(),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = strokeWidth,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        .build()

internal fun attachmentFileIconTint(extension: String, fallback: Color): Color = when (extension) {
    "doc", "docx" -> Color(0xFF2B75D0)
    "pdf" -> Color(0xFFE53237)
    "xls", "xlsx" -> Color(0xFF26834F)
    "ppt", "pptx" -> Color(0xFFC65A2C)
    else -> fallback
}

internal fun attachmentFileIcon(extension: String, mimeType: String): AttachmentFileIcon? = when (extension) {
    "txt", "rtf", "log" -> AttachmentFileIcon.TEXT
    "md" -> AttachmentFileIcon.MARKDOWN
    "pdf" -> AttachmentFileIcon.PDF
    "doc", "docx", "odt" -> AttachmentFileIcon.DOCUMENT
    "xls", "xlsx", "ods", "csv", "tsv" -> AttachmentFileIcon.SPREADSHEET
    "ppt", "pptx", "odp", "key" -> AttachmentFileIcon.PRESENTATION
    "mp4", "m4v", "mkv", "mov", "avi", "webm" -> AttachmentFileIcon.VIDEO
    "aac", "flac", "m4a", "mp3", "ogg", "wav" -> AttachmentFileIcon.AUDIO
    "zip", "7z", "rar", "tar", "gz", "bz2", "xz" -> AttachmentFileIcon.ARCHIVE
    "css", "html", "htm", "js", "json", "kt", "py", "sql", "ts", "xml", "yml", "yaml" -> AttachmentFileIcon.CODE
    else -> when {
        mimeType.startsWith("video/") -> AttachmentFileIcon.VIDEO
        mimeType.startsWith("audio/") -> AttachmentFileIcon.AUDIO
        else -> null
    }
}
