package com.irio

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

internal fun attachmentPreviewSize(
    widthPx: Int,
    heightPx: Int,
    density: Float,
    compact: Boolean = false,
): DpSize {
    val width = widthPx / density
    val height = heightPx / density
    val maxEdge = if (compact) 64f else 160f
    val scale = minOf(1f, maxEdge / width, maxEdge / height)
    return DpSize((width * scale).dp, (height * scale).dp)
}
