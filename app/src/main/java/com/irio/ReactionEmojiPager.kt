package com.irio

import androidx.compose.animation.core.animate
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs


/** At most two real categories, separated by one viewport regardless of their indices. */
internal class ReactionEmojiPagerState(private val scope: CoroutineScope) {
    var selected by mutableIntStateOf(0)
        private set
    private var anchor by mutableIntStateOf(0)
    private var destination by mutableStateOf<Int?>(null)
    private var direction by mutableFloatStateOf(1f)
    private var position by mutableFloatStateOf(0f)
    var dragging by mutableStateOf(false)
        private set

    private var motion: Job? = null
    var count: Int = 0

    val pages: List<Int> get() = listOfNotNull(anchor, destination)

    fun offsetFor(page: Int): Float = (if (page == anchor) 0f else direction) - position

    fun select(index: Int) {
        if (count == 0) return
        val target = index.coerceIn(0, count - 1)
        if (target != anchor && target != destination) {
            rebaseToDominantPage()
            destination = target
            direction = if (target > anchor) 1f else -1f
        }
        selected = target
        dragging = false
        settle(if (target == anchor) 0f else direction)
    }

    private fun rebaseToDominantPage() {
        val other = destination ?: return
        if (abs(direction - position) < abs(position)) {
            destination = anchor
            anchor = other
            position -= direction
            direction = -direction
        }
    }

    private fun finish(target: Float) {
        if (target != 0f) anchor = destination ?: anchor
        selected = anchor
        destination = null
        position = 0f
    }

    private fun settle(target: Float) {
        motion?.cancel()
        if (position == target) { finish(target); return }
        motion = scope.launch {
            animate(position, target, animationSpec = irioSpring(stiffness = 420f)) { value, _ -> position = value }
            finish(target)
        }
    }
}
