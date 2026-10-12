package com.irio

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.kyant.shapes.Capsule

/** G2 pill. A square whose radius reaches half the side stays a circle; that is the library fallback. */
internal val ContinuousCapsule: Shape = Capsule()

internal fun Shape.continuousPath(
    size: Size,
    layoutDirection: LayoutDirection,
    density: Density,
): Path = when (val outline = createOutline(size, layoutDirection, density)) {
    is Outline.Generic -> Path().apply { addPath(outline.path) }
    is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
    is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
}

internal fun DrawScope.drawContinuous(
    color: Color,
    shape: Shape,
    topLeft: Offset = Offset.Zero,
    size: Size = this.size,
    style: DrawStyle = Fill,
) {
    val outline = shape.createOutline(size, layoutDirection, this)
    drawContext.canvas.save()
    try {
        drawContext.transform.translate(topLeft.x, topLeft.y)
        drawOutline(outline, color, style = style)
    } finally {
        drawContext.canvas.restore()
    }
}
