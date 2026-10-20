package com.irio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserMessageAvatarTest {
    @Test
    fun avatarCentersOnTheFirstLineInsteadOfTheLast() {
        val padding = 10f
        val line = 22f
        val avatar = 28f
        val besideFirst = userMessageAvatarTopOffsetPx(padding, line, avatar)
        assertEquals(padding + line / 2f - avatar / 2f, besideFirst, 0.01f)

        val besideLastOfFour = padding + line * 3f + line / 2f - avatar / 2f
        assertTrue(besideFirst < besideLastOfFour)
        assertEquals(besideFirst, userMessageAvatarTopOffsetPx(padding, line, avatar), 0.01f)
    }

    @Test
    fun avatarDoesNotRiseAboveTheBubble() {
        assertEquals(0f, userMessageAvatarTopOffsetPx(0f, 10f, 40f), 0.01f)
        assertEquals(0f, userMessageAvatarTopOffsetPx(0f, 0f, 28f), 0.01f)
        assertEquals(0f, userMessageAvatarTopOffsetPx(10f, 22f, 0f), 0.01f)
    }
}
