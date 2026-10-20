package com.irio

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileCropTest {
    @Test fun blankNameFallsBackAndWhitespaceCollapses() {
        assertEquals("用户", normalizeProfileName(null))
        assertEquals("用户", normalizeProfileName("   "))
        assertEquals("Mira Lin", normalizeProfileName("  Mira   Lin  "))
        assertEquals("a".repeat(PROFILE_NAME_LIMIT), normalizeProfileName("a".repeat(40)))
    }

    @Test fun profileStringsHaveEnglish() {
        assertEquals("User", translate(AppLanguage.EN, "用户"))
        assertEquals("Name", translate(AppLanguage.EN, "用户名称"))
        assertEquals("Profile photo", translate(AppLanguage.EN, "头像"))
        assertEquals("Delete profile photo", translate(AppLanguage.EN, "删除头像"))
        assertEquals("Delete this profile photo?", translate(AppLanguage.EN, "确定要删除头像吗？"))
        assertEquals("Drag and pinch to zoom", translate(AppLanguage.EN, "拖动并双指缩放"))
    }

    @Test fun sampleSizeShrinksOnlyOversizedPhotos() {
        assertEquals(1, profileSampleSize(100, 80, 2048))
        assertEquals(2, profileSampleSize(4000, 3000, 2048))
        assertEquals(8, profileSampleSize(9000, 100, 2048))
    }

    @Test fun coverCropStartsCenteredAndZoomKeepsTheCircleFixed() {
        val viewport = 100f
        val cover = avatarCoverScale(200, 100, viewport)
        assertEquals(1f, cover, 0.001f)
        val offsetX = avatarInitialOffset(200, cover, viewport)
        val offsetY = avatarInitialOffset(100, cover, viewport)
        val initial = avatarCropWindow(200, 100, viewport, cover, offsetX, offsetY)
        assertEquals(50f, initial.left, 0.01f)
        assertEquals(0f, initial.top, 0.01f)
        assertEquals(100f, initial.size, 0.01f)

        val zoomed = avatarGesture(
            imageWidth = 200,
            imageHeight = 100,
            viewport = viewport,
            zoom = 1f,
            offsetX = offsetX,
            offsetY = offsetY,
            panX = 0f,
            panY = 0f,
            gestureZoom = 2f,
        )
        assertEquals(2f, zoomed.zoom, 0.001f)
        val closer = avatarCropWindow(200, 100, viewport, cover * zoomed.zoom, zoomed.offsetX, zoomed.offsetY)
        assertEquals(50f, closer.size, 0.01f)
        assertEquals(75f, closer.left, 0.01f)
        assertEquals(25f, closer.top, 0.01f)
    }

    @Test fun drawnImageKeepsTheCircleCenterOnTheSamePhotoPoint() {
        val viewport = 100f
        val circleLeft = 40f
        val circleTop = 80f
        val cover = avatarCoverScale(200, 100, viewport)
        val offsetX = avatarInitialOffset(200, cover, viewport)
        val offsetY = avatarInitialOffset(100, cover, viewport)
        val frame = avatarImageFrame(circleLeft, circleTop, offsetX, offsetY, 200, 100, cover)
        val centerX = circleLeft + viewport / 2f
        val centerY = circleTop + viewport / 2f
        val window = avatarCropWindow(200, 100, viewport, cover, offsetX, offsetY)
        assertEquals(window.left + window.size / 2f, (centerX - frame.left) / cover, 0.01f)
        assertEquals(window.top + window.size / 2f, (centerY - frame.top) / cover, 0.01f)
        val zoomed = avatarGesture(
            imageWidth = 200,
            imageHeight = 100,
            viewport = viewport,
            zoom = 1f,
            offsetX = offsetX,
            offsetY = offsetY,
            panX = 36f,
            panY = 28f,
            gestureZoom = 2f,
        )
        val zoomedFrame = avatarImageFrame(
            circleLeft,
            circleTop,
            zoomed.offsetX,
            zoomed.offsetY,
            200,
            100,
            cover * zoomed.zoom,
        )
        assertEquals(centerX, zoomedFrame.left + (window.left + window.size / 2f) * cover * zoomed.zoom, 0.01f)
        assertEquals(centerY, zoomedFrame.top + (window.top + window.size / 2f) * cover * zoomed.zoom, 0.01f)
    }

    @Test fun pinchZoomStaysOnTheCircleCenterWhenFingersAlsoMove() {
        val viewport = 100f
        val still = avatarGesture(
            imageWidth = 200,
            imageHeight = 100,
            viewport = viewport,
            zoom = 1f,
            offsetX = -50f,
            offsetY = 0f,
            panX = 0f,
            panY = 0f,
            gestureZoom = 2f,
        )
        val movingFingers = avatarGesture(
            imageWidth = 200,
            imageHeight = 100,
            viewport = viewport,
            zoom = 1f,
            offsetX = -50f,
            offsetY = 0f,
            panX = 80f,
            panY = -40f,
            gestureZoom = 2f,
        )
        assertEquals(still.zoom, movingFingers.zoom, 0.001f)
        assertEquals(still.offsetX, movingFingers.offsetX, 0.01f)
        assertEquals(still.offsetY, movingFingers.offsetY, 0.01f)
    }

    @Test fun panCannotDragTheCircleOutsideThePhoto() {
        val next = avatarGesture(
            imageWidth = 100,
            imageHeight = 100,
            viewport = 100f,
            zoom = 2f,
            offsetX = -50f,
            offsetY = -50f,
            panX = 10_000f,
            panY = -10_000f,
            gestureZoom = 1f,
        )
        assertEquals(0f, next.offsetX, 0.01f)
        assertEquals(-100f, next.offsetY, 0.01f)
        val window = avatarCropWindow(100, 100, 100f, 2f, next.offsetX, next.offsetY)
        assertEquals(0f, window.left, 0.01f)
        assertEquals(50f, window.top, 0.01f)
        assertEquals(50f, window.size, 0.01f)
    }
}
