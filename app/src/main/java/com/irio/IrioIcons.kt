package com.irio

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Only the Lucide icons used by Irio, cached as native Compose vectors.
 * SVG source: https://github.com/lucide-icons/lucide/tree/20aef31db93fe4d9f398dbb15b120d99c48eef95/icons
 * Paths retain the upstream 24-unit viewport, 2-unit strokes, round caps and joins.
 * SVG shape elements are expressed as equivalent vector paths.
 * License (including Feather attribution): assets/licenses/lucide.txt.
 */
internal object IrioIcons {
    val ArrowLeft by lazy { icon("arrow-left", "m12 19-7-7 7-7", "M19 12H5", autoMirror = true) }
    val ChevronLeft by lazy { icon("chevron-left", "m15 18-6-6 6-6", autoMirror = true) }
    val ChevronRight by lazy { icon("chevron-right", "m9 18 6-6-6-6", autoMirror = true) }
    val ChevronDown by lazy { icon("chevron-down", "m6 9 6 6 6-6") }
    val Volume2 by lazy {
        icon("volume-2",
            "M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298z",
            "M16 9a5 5 0 0 1 0 6", "M19.364 18.364a9 9 0 0 0 0-12.728", autoMirror = true)
    }
    val Plus by lazy { icon("plus", "M5 12h14", "M12 5v14") }
    val FaceSlightlySmilingPlus by lazy {
        icon("face-slightly-smiling-plus", "M13.267 2.08a10 10 0 1 0 8.653 8.653",
            "M15 10V9", "M16 5h6", "M16.472 15a6 6 0 0 1-8.943 0", "M19 2v6", "M9 10V9")
    }
    val ArrowUp by lazy { icon("arrow-up", "m5 12 7-7 7 7", "M12 19V5") }
    val Paperclip by lazy {
        icon("paperclip", "m16 6-8.414 8.586a2 2 0 0 0 2.829 2.829l8.414-8.586a4 4 0 1 0-5.657-5.657l-8.379 8.551a6 6 0 1 0 8.485 8.485l8.379-8.551")
    }
    val Sparkles by lazy {
        icon("sparkles",
            "M11.017 2.814a1 1 0 0 1 1.966 0l1.051 5.558a2 2 0 0 0 1.594 1.594l5.558 1.051a1 1 0 0 1 0 1.966l-5.558 1.051a2 2 0 0 0-1.594 1.594l-1.051 5.558a1 1 0 0 1-1.966 0l-1.051-5.558a2 2 0 0 0-1.594-1.594l-5.558-1.051a1 1 0 0 1 0-1.966l5.558-1.051a2 2 0 0 0 1.594-1.594z",
            "M20 2v4", "M22 4h-4", circle(4f, 20f, 2f))
    }
    val Zap by lazy {
        icon("zap", "M15.914 4a1.5 1.5 0 0 0-2.474-1.561l-9 9A1.5 1.5 0 0 0 5.5 14h4.002a.5.5 0 0 1 .471.666L8.086 20a1.5 1.5 0 0 0 2.475 1.56l9-9A1.5 1.5 0 0 0 18.5 10h-3.997a.5.5 0 0 1-.472-.667z")
    }
    val ZapOff by lazy {
        icon("zap-off", "M10.768 5.111 13.44 2.44a1.5 1.5 0 0 1 2.474 1.561l-1.633 4.625",
            "m18.889 13.232.672-.672A1.5 1.5 0 0 0 18.5 10h-2.844", "m2 2 20 20",
            "m7.94 7.94-3.5 3.499A1.5 1.5 0 0 0 5.5 14h4.002a.5.5 0 0 1 .471.666L8.086 20a1.5 1.5 0 0 0 2.475 1.56l5.5-5.5")
    }
    val Check by lazy { icon("check", "M20 6 9 17l-5-5") }
    val X by lazy { icon("x", "M18 6 6 18", "m6 6 12 12") }
    val Minimize2 by lazy { icon("minimize-2", "m14 10 7-7", "M20 10h-6V4", "m3 21 7-7", "M4 14h6v6") }
    val Monitor by lazy { icon("monitor", rect(2, 3, 20, 14, 2), "M8 21H16", "M12 17V21") }
    val Copy by lazy { icon("copy", rect(8, 8, 14, 14, 2), "M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2") }
    val Braces by lazy {
        icon("braces", "M8 3H7a2 2 0 0 0-2 2v5a2 2 0 0 1-2 2 2 2 0 0 1 2 2v5c0 1.1.9 2 2 2h1",
            "M16 21h1a2 2 0 0 0 2-2v-5c0-1.1.9-2 2-2a2 2 0 0 1-2-2V5a2 2 0 0 0-2-2h-1")
    }
    val Trash by lazy {
        icon("trash", "M10 11v6", "M14 11v6", "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6", "M3 6h18",
            "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2")
    }
    val MonitorSmartphone by lazy {
        icon("monitor-smartphone", "M18 8V6a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2v7a2 2 0 0 0 2 2h8",
            "M10 19v-3.96 3.15", "M7 19h5", rect(16, 12, 6, 10, 2))
    }
    val Download by lazy { icon("download", "M12 15V3", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "m7 10 5 5 5-5") }
    val Pencil by lazy {
        icon("pencil", "M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z", "m15 5 4 4")
    }
    val Quote by lazy {
        icon("quote", "M16 3a2 2 0 0 0-2 2v6a2 2 0 0 0 2 2 1 1 0 0 1 1 1v1a2 2 0 0 1-2 2 1 1 0 0 0-1 1v2a1 1 0 0 0 1 1 6 6 0 0 0 6-6V5a2 2 0 0 0-2-2z",
            "M5 3a2 2 0 0 0-2 2v6a2 2 0 0 0 2 2 1 1 0 0 1 1 1v1a2 2 0 0 1-2 2 1 1 0 0 0-1 1v2a1 1 0 0 0 1 1 6 6 0 0 0 6-6V5a2 2 0 0 0-2-2z")
    }
    val FastForward by lazy {
        icon("fast-forward", "M12 6a2 2 0 0 1 3.414-1.414l6 6a2 2 0 0 1 0 2.828l-6 6A2 2 0 0 1 12 18z",
            "M2 6a2 2 0 0 1 3.414-1.414l6 6a2 2 0 0 1 0 2.828l-6 6A2 2 0 0 1 2 18z")
    }
    val Cpu by lazy {
        icon("cpu", "M12 20v2", "M12 2v2", "M17 20v2", "M17 2v2", "M2 12h2", "M2 17h2", "M2 7h2",
            "M20 12h2", "M20 17h2", "M20 7h2", "M7 20v2", "M7 2v2", rect(4, 4, 16, 16, 2), rect(8, 8, 8, 8, 1))
    }
    val Menu by lazy { icon("menu", "M4 5h16", "M4 12h16", "M4 19h16") }
    val Mic by lazy { icon("mic", "M12 19v3", "M19 10v2a7 7 0 0 1-14 0v-2", rect(9, 2, 6, 13, 3)) }
    val Ellipsis by lazy { icon("ellipsis", circle(12f, 12f, 1f), circle(19f, 12f, 1f), circle(5f, 12f, 1f)) }
    val Maximize2 by lazy { icon("maximize-2", "M15 3h6v6", "m21 3-7 7", "m3 21 7-7", "M9 21H3v-6") }
    val Palette by lazy {
        icon("palette", "M12 22a1 1 0 0 1 0-20 10 9 0 0 1 10 9 5 5 0 0 1-5 5h-2.25a1.75 1.75 0 0 0-1.4 2.8l.3.4a1.75 1.75 0 0 1-1.4 2.8z",
            filledPaths = listOf(circle(13.5f, 6.5f, .5f), circle(17.5f, 10.5f, .5f), circle(6.5f, 12.5f, .5f), circle(8.5f, 7.5f, .5f)))
    }
    val Pause by lazy { icon("pause", rect(14, 3, 5, 18, 1), rect(5, 3, 5, 18, 1)) }
    val Smartphone by lazy { icon("smartphone", rect(5, 2, 14, 20, 2), "M12 18h.01") }
    val Image by lazy { icon("image", rect(3, 3, 18, 18, 2), circle(9f, 9f, 2f), "m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21") }
    val Play by lazy { icon("play", "M5 5a2 2 0 0 1 3.008-1.728l11.997 6.998a2 2 0 0 1 .003 3.458l-12 7A2 2 0 0 1 5 19z") }
    val Pin by lazy {
        icon("pin", "M12 17v5", "M9 10.76a2 2 0 0 1-1.11 1.79l-1.78.9A2 2 0 0 0 5 15.24V16a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-.76a2 2 0 0 0-1.11-1.79l-1.78-.9A2 2 0 0 1 15 10.76V7a1 1 0 0 1 1-1 2 2 0 0 0 0-4H8a2 2 0 0 0 0 4 1 1 0 0 1 1 1z")
    }
    val Speech by lazy {
        icon("speech", "M8.8 20v-4.1l1.9.2a2.3 2.3 0 0 0 2.164-2.1V8.3A5.37 5.37 0 0 0 2 8.25c0 2.8.656 3.054 1 4.55a5.77 5.77 0 0 1 .029 2.758L2 20",
            "M19.8 17.8a7.5 7.5 0 0 0 .003-10.603", "M17 15a3.5 3.5 0 0 0-.025-4.975")
    }
    val RefreshCw by lazy {
        icon("refresh-cw", "M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8", "M21 3v5h-5",
            "M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16", "M8 16H3v5")
    }
    val Rewind by lazy {
        icon("rewind", "M12 6a2 2 0 0 0-3.414-1.414l-6 6a2 2 0 0 0 0 2.828l6 6A2 2 0 0 0 12 18z",
            "M22 6a2 2 0 0 0-3.414-1.414l-6 6a2 2 0 0 0 0 2.828l6 6A2 2 0 0 0 22 18z")
    }
    val Search by lazy { icon("search", "m21 21-4.34-4.34", circle(11f, 11f, 8f)) }
    val Settings by lazy {
        icon("settings", "M9.671 4.136a2.34 2.34 0 0 1 4.659 0 2.34 2.34 0 0 0 3.319 1.915 2.34 2.34 0 0 1 2.33 4.033 2.34 2.34 0 0 0 0 3.831 2.34 2.34 0 0 1-2.33 4.033 2.34 2.34 0 0 0-3.319 1.915 2.34 2.34 0 0 1-4.659 0 2.34 2.34 0 0 0-3.32-1.915 2.34 2.34 0 0 1-2.33-4.033 2.34 2.34 0 0 0 0-3.831A2.34 2.34 0 0 1 6.35 6.051a2.34 2.34 0 0 0 3.319-1.915", circle(12f, 12f, 3f))
    }
    val Bot by lazy { icon("bot", "M12 8V4H8", rect(4, 8, 16, 12, 2), "M2 14h2", "M20 14h2", "M15 13v2", "M9 13v2") }
    val Square by lazy { icon("square", rect(3, 3, 18, 18, 2)) }
    val SquareCheck by lazy { icon("square-check", rect(3, 3, 18, 18, 2), "m16 9-5.5 5.5L8 12") }
    val SquarePen by lazy {
        icon(
            "square-pen",
            "M12 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7",
            "m18.375 2.625a1 1 0 0 1 3 3l-9.013 9.014a2 2 0 0 1-.853.506l-2.873.84.84-2.872a2 2 0 0 1 .506-.854z",
        )
    }
    val Terminal by lazy { icon("terminal", "M12 19h8", "m4 17 6-6-6-6") }
    val Upload by lazy { icon("upload", "M12 3v12", "m17 8-5-5-5 5", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4") }
}

private fun icon(
    name: String,
    vararg paths: String,
    autoMirror: Boolean = false,
    filledPaths: List<String> = emptyList(),
): ImageVector {
    val ink = SolidColor(Color.Black)
    val builder = ImageVector.Builder(
        name = "lucide:$name",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = autoMirror,
    )
    for (path in paths.toList() + filledPaths) {
        builder.addPath(
            pathData = PathParser().parsePathString(path).toNodes(),
            fill = if (path in filledPaths) ink else null,
            stroke = ink,
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    return builder.build()
}

private fun circle(cx: Float, cy: Float, r: Float): String =
    "M${cx - r} $cy a$r $r 0 1 0 ${2 * r} 0 a$r $r 0 1 0 ${-2 * r} 0 Z"

private fun rect(x: Int, y: Int, width: Int, height: Int, radius: Int): String =
    "M${x + radius} $y H${x + width - radius} A$radius $radius 0 0 1 ${x + width} ${y + radius} " +
        "V${y + height - radius} A$radius $radius 0 0 1 ${x + width - radius} ${y + height} " +
        "H${x + radius} A$radius $radius 0 0 1 $x ${y + height - radius} " +
        "V${y + radius} A$radius $radius 0 0 1 ${x + radius} $y Z"
