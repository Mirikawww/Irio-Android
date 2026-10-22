package com.irio

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSwitcherMotionTest {
    @Test
    fun compactControlsRemainInertWhileModelListIsOpenOrClosing() {
        assertTrue(modelSwitcherCompactInteractive(modelsExpanded = false, phase = 0f))
        // The list becomes interactive before its fade-in completes.
        assertFalse(modelSwitcherCompactInteractive(modelsExpanded = true, phase = 0f))
        assertFalse(modelSwitcherCompactInteractive(modelsExpanded = true, phase = 1f))
        // Closing the list must not activate the slider while the surfaces overlap.
        assertFalse(modelSwitcherCompactInteractive(modelsExpanded = false, phase = 0.5f))
        assertTrue(modelSwitcherCompactInteractive(modelsExpanded = false, phase = 0f))
    }

    @Test
    fun entirePanelExchangesDuringFastEntryAndFinishesWithSlowExit() {
        assertEquals(0f, modelSwitcherListOpacity(0.04f), 0.001f)
        assertEquals(0.5f, modelSwitcherListOpacity(0.27f), 0.001f)
        assertEquals(1f, modelSwitcherListOpacity(0.5f), 0.001f)
        assertEquals(0f, modelSwitcherExtension(0f), 0.001f)
        assertEquals(1f, modelSwitcherExtension(1f), 0.001f)
        assertTrue(modelSwitcherExtension(0.2f) > 0.5f)
        val entryVelocity = modelSwitcherExtension(0.011f) - modelSwitcherExtension(0.009f)
        val endingVelocity = modelSwitcherExtension(0.801f) - modelSwitcherExtension(0.799f)
        assertTrue(entryVelocity > endingVelocity * 20f)
        assertEquals(1f, modelSwitcherListOpacity(modelSwitcherExtension(0.2f)), 0.001f)
    }

    @Test
    fun shortModelListShrinksInsteadOfLeavingBlankSpace() {
        // 2 个模型：2*36+8=80.dp，远小于收起态 220.dp，展开后应缩到内容高度。
        assertEquals(80.dp, modelSwitcherListHeight(80.dp, 800))
        // 模型多时按屏幕 55% 封顶，封顶区间 264.dp..420.dp。
        assertEquals(420.dp, modelSwitcherListHeight(1000.dp, 800))
        assertEquals(330.dp, modelSwitcherListHeight(1000.dp, 600))
        assertEquals(264.dp, modelSwitcherListHeight(1000.dp, 400))
        // 内容恰好在封顶内时保持原高度。
        assertEquals(300.dp, modelSwitcherListHeight(300.dp, 800))
    }

    @Test
    fun maxFillStopsAtItsThumbRatherThanFillingTheWholeTrack() {
        val max = thinkingSliderGeometry(280f, 12f, 6f / 7f, false)
        assertEquals(max.thumbX, max.fillWidth, 0.001f)
        assertTrue(max.fillWidth < 280f - 12f)
        val ultra = thinkingSliderGeometry(280f, 12f, 1f, false)
        assertEquals(268f, ultra.thumbX, 0.001f)
        assertEquals(280f, ultra.fillWidth, 0.001f)
    }

    @Test
    fun fillAndThumbMirrorTogetherAndStayWithinTheTrack() {
        listOf(-1f, 0f, 0.1f, 0.5f, 0.9f, 1f, 2f).forEach { progress ->
            val left = thinkingSliderGeometry(280f, 12f, progress, false)
            val right = thinkingSliderGeometry(280f, 12f, progress, true)
            assertEquals(280f - left.thumbX, right.thumbX, 0.001f)
            assertEquals(left.fillWidth, right.fillWidth, 0.001f)
            assertEquals(280f - left.fillWidth, right.fillStart, 0.001f)
            assertTrue(left.thumbX in 12f..268f)
            assertTrue(left.fillWidth in 0f..280f)
        }
    }

    @Test
    fun originalPointerStaysSymmetricAndOverlapsCapsuleAtBothEnds() {
        listOf(35f, 69f, 120f).forEach { width ->
            listOf(12f, width / 2f, width - 12f).forEach { tip ->
                val shape = thinkingIndicatorGeometry(width, 34f, tip, 5f, 5f)
                assertEquals(tip, (shape.rootStart + shape.rootEnd) / 2f, 0.001f)
                assertEquals(tip, shape.tipX, 0.001f)
                assertTrue(shape.rootBottom > shape.bodyTop)
                if (tip == 12f || tip == width - 12f) assertTrue(shape.rootBottom > shape.bodyTop + 1f)
            }
        }
    }

    @Test
    fun rapidReversalsUseTheImmediatelyPrecedingSelection() {
        var state = ThinkingRollState(3)
        listOf(6 to 1, 7 to 1, 2 to -1, 5 to 1, 1 to -1, 0 to -1, 7 to 1).forEach { (next, direction) ->
            state = state.moveTo(next)
            assertEquals(direction, state.direction)
            assertEquals(next, state.index)
            assertEquals(state, state.moveTo(next))
        }
    }

    @Test
    fun randomParticlesMoveRightAndOnlyRespawnAtZeroOpacity() {
        val field = ThinkingParticleField(42, 36)
        val previous = Array(field.count) { field.sample(it, 0f) }
        repeat(960) { frame ->
            val time = (frame + 1) / 120f
            repeat(field.count) { index ->
                val next = field.sample(index, time)
                if (next.x < previous[index].x) {
                    assertTrue(previous[index].opacity < 0.03f)
                    assertTrue(next.opacity < 0.03f)
                    assertTrue(previous[index].x > 1f)
                    assertTrue(next.x < 0f)
                }
                previous[index] = next
            }
        }
    }

    @Test
    fun randomParticlesDoNotReplayOneSharedCycle() {
        val field = ThinkingParticleField(42, 36)
        val start = (0 until field.count).map { field.sample(it, 0.1f) }
        val later = (0 until field.count).map { field.sample(it, 1.8f) }
        assertTrue(start.zip(later).count { (a, b) -> a.y != b.y || a.scale != b.scale } > field.count / 2)
    }

    @Test
    fun allChangedLettersShareOneDirectionAndOneProgress() {
        val previous = listOf(ThinkingGlyphFrame(0, "L", 0f), ThinkingGlyphFrame(1, "o", 9f))
        val target = listOf(ThinkingGlyphFrame(0, "H", 0f), ThinkingGlyphFrame(1, "i", 11f))
        listOf(-1, 1).forEach { direction ->
            val runs = thinkingGlyphRuns(previous, target, direction)
            val incoming = runs.takeLast(target.size).map { it.frame(0.4f) }
            val outgoing = runs.take(previous.size).map { it.frame(0.4f) }
            incoming.forEach { assertEquals(direction * 0.6f, it.y, 0.001f); assertEquals(0.4f, it.alpha, 0.001f) }
            outgoing.forEach { assertEquals(-direction * 0.4f, it.y, 0.001f); assertEquals(0.6f, it.alpha, 0.001f) }
        }
    }

    @Test
    fun staggeredLettersStartOneAfterAnotherWithoutChangingDirection() {
        val previous = listOf(ThinkingGlyphFrame(0, "L", 0f), ThinkingGlyphFrame(1, "o", 9f), ThinkingGlyphFrame(2, "w", 18f))
        val target = listOf(ThinkingGlyphFrame(0, "H", 0f), ThinkingGlyphFrame(1, "i", 11f), ThinkingGlyphFrame(2, "g", 18f))
        val runs = thinkingGlyphRuns(previous, target, 1, stagger = 0.5f).takeLast(target.size)
        assertEquals(listOf(0f, 0.25f, 0.5f), runs.map { it.delay })
        assertEquals(1f, runs[0].frame(0f).y, 0.001f)
        assertEquals(0f, runs[0].frame(1f).y, 0.001f)
        assertEquals(1f, runs[2].frame(0.4f).y, 0.001f)
        assertEquals(0f, runs[2].frame(1f).y, 0.001f)
    }

    @Test
    fun lettersAlreadyHeadingAgainstTheNewDirectionKeepLeaving() {
        val settled = listOf(ThinkingGlyphFrame(0, "L", 0f), ThinkingGlyphFrame(1, "o", 9f))
        val other = listOf(ThinkingGlyphFrame(0, "H", 0f), ThinkingGlyphFrame(1, "i", 11f))
        listOf(-1, 1).forEach { direction ->
            val visible = thinkingGlyphRuns(settled, other, direction, stagger = 0.5f).map { it.frame(0.3f) }
            // Move the slider back immediately in the same direction: nothing may travel against it.
            val next = thinkingGlyphRuns(visible, settled, direction, stagger = 0.5f)
            next.forEach { run -> assertTrue((run.end.y - run.start.y) * direction <= 0.0001f) }
        }
    }

    @Test
    fun blurIsSharpAtRestAndPeaksMidRoll() {
        val label = listOf(ThinkingGlyphFrame(0, "L", 0f), ThinkingGlyphFrame(1, "o", 9f))
        val settled = label.map { ThinkingGlyphRun(it, it) }
        assertEquals(0f, thinkingBlurFraction(settled, 0.5f), 0.0001f)

        val other = listOf(ThinkingGlyphFrame(0, "H", 0f), ThinkingGlyphFrame(1, "i", 11f))
        listOf(0f, 0.5f).forEach { stagger ->
            val runs = thinkingGlyphRuns(label, other, 1, stagger)
            assertEquals(0f, thinkingBlurFraction(runs, 0f), 0.0001f)
            assertEquals(0f, thinkingBlurFraction(runs, 1f), 0.0001f)
            assertTrue(thinkingBlurFraction(runs, 0.5f) > thinkingBlurFraction(runs, 0.1f))
            assertTrue(thinkingBlurFraction(runs, 0.5f) in 0f..1f)
        }
    }

    @Test
    fun interruptedLetterRollRetainsItsVisiblePositionAndOpacity() {
        val initial = listOf(ThinkingGlyphFrame(0, "L", 0f), ThinkingGlyphFrame(1, "o", 9f))
        val high = listOf(ThinkingGlyphFrame(0, "H", 0f), ThinkingGlyphFrame(1, "i", 11f))
        val visible = thinkingGlyphRuns(initial, high, 1).map { it.frame(0.3f) }
        val reversed = thinkingGlyphRuns(visible, initial, -1)
        visible.forEach { frame ->
            val retained = reversed.single { it.start.index == frame.index && it.start.character == frame.character }.frame(0f)
            assertEquals(frame, retained)
        }
        assertEquals(initial, reversed.takeLast(initial.size).map { it.frame(1f) })
    }
}
