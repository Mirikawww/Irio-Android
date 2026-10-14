package com.irio

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring

/** Shared motion policy: state changes use one family of critically-damped springs. */
internal fun <T> irioSpring(
    dampingRatio: Float = 0.86f,
    stiffness: Float = 520f,
): SpringSpec<T> = spring(
    dampingRatio = dampingRatio,
    stiffness = stiffness,
)

/** Hand-written cubic-bezier evaluator for procedural motion and visual envelopes. */
private fun cubicBezier(t: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
    val x = t.coerceIn(0f, 1f)
    var low = 0f
    var high = 1f
    repeat(16) {
        val u = (low + high) / 2f
        val oneMinus = 1f - u
        val curveX = 3f * oneMinus * oneMinus * u * x1 +
            3f * oneMinus * u * u * x2 + u * u * u
        if (curveX < x) low = u else high = u
    }
    val u = (low + high) / 2f
    val oneMinus = 1f - u
    return 3f * oneMinus * oneMinus * u * y1 +
        3f * oneMinus * u * u * y2 + u * u * u
}

internal val IrioEaseOut = Easing { cubicBezier(it, 0.22f, 1.0f, 0.36f, 1.0f) }
internal val IrioEaseInOut = Easing { cubicBezier(it, 0.65f, 0.0f, 0.35f, 1.0f) }
