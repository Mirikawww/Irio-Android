package com.irio

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentPreviewSizeTest {
    @Test
    fun largeLandscapePhotoIsAProportionalThumbnail() {
        assertEquals(DpSize(160.dp, 120.dp), attachmentPreviewSize(4000, 3000, 3f))
    }

    @Test
    fun tallPhotoFitsWithoutCropping() {
        assertEquals(DpSize(40.dp, 160.dp), attachmentPreviewSize(1000, 4000, 2f))
    }

    @Test
    fun panoramicPhotoFitsWithoutCropping() {
        assertEquals(DpSize(160.dp, 40.dp), attachmentPreviewSize(4000, 1000, 2f))
    }

    @Test
    fun squarePhotoStaysWithinThumbnailBounds() {
        assertEquals(DpSize(160.dp, 160.dp), attachmentPreviewSize(4096, 4096, 1f))
    }

    @Test
    fun smallImageIsNotUpscaled() {
        assertEquals(DpSize(20.dp, 10.dp), attachmentPreviewSize(40, 20, 2f))
    }

    @Test
    fun pendingPhotoKeepsItsCompactBoundsAndAspectRatio() {
        val size = attachmentPreviewSize(4000, 3000, 2f, compact = true)
        assertEquals(64f, size.width.value, 0.001f)
        assertEquals(48f, size.height.value, 0.001f)
    }

    @Test
    fun highDensityAndLowDensityScreensUseTheSameThumbnailLimit() {
        assertEquals(attachmentPreviewSize(4000, 3000, 1f), attachmentPreviewSize(4000, 3000, 4f))
    }
}
