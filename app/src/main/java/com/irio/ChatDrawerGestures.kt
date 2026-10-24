package com.irio

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange

internal fun Modifier.drawerSwipe(
    drawerWidthPx: Float,
    openDirection: Boolean,
    allowTapToClose: Boolean,
    progress: () -> Float,
    onStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onSettle: () -> Unit,
    onTapClose: () -> Unit,
): Modifier =
    pointerInput(drawerWidthPx, openDirection, allowTapToClose) {
        val touchSlop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var totalX = 0f
            var totalY = 0f
            var dragging = false
            var passedSlop = false
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    if (dragging) onSettle() else if (allowTapToClose && !passedSlop) onTapClose()
                    break
                }
                val delta = change.positionChange()
                totalX += delta.x
                totalY += delta.y
                if (
                    !passedSlop &&
                        (kotlin.math.abs(totalX) > touchSlop || kotlin.math.abs(totalY) > touchSlop)
                ) {
                    passedSlop = true
                    val horizontal = kotlin.math.abs(totalX) > kotlin.math.abs(totalY)
                    val matching = if (openDirection) totalX > 0f else totalX < 0f
                    if (horizontal && matching) {
                        dragging = true
                        onStart()
                        change.consume()
                        onDrag((progress() + totalX / drawerWidthPx).coerceIn(0f, 1f))
                    }
                } else if (dragging) {
                    change.consume()
                    onDrag((progress() + delta.x / drawerWidthPx).coerceIn(0f, 1f))
                }
            }
        }
    }

/**
 * Painted whitespace must consume taps, while child buttons and drawer drags keep their gestures.
 */
internal fun Modifier.absorbDrawerBackgroundTaps(): Modifier =
    pointerInput(Unit) { detectTapGestures(onTap = {}) }
