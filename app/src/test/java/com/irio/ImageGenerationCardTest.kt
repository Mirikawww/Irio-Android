package com.irio

import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.toArgb
import java.io.ByteArrayOutputStream
import java.io.File
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageGenerationCardTest {
    @Test fun downloadedRectanglesUseActualDimensionsWithoutSizeOrDespiteStaleSquareMetadata() {
        for ((width, height) in listOf(160 to 90, 90 to 160)) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val bytes = ByteArrayOutputStream()
            try { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)) } finally { bitmap.recycle() }
            for (size in listOf("", "auto", "1024x1024")) MockWebServer().use { server ->
                val url = server.url("/rectangle.png").toString()
                val completed = org.json.JSONObject().put("type", "image_generation.completed").put("url", url)
                if (size.isNotEmpty()) completed.put("size", size)
                server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBody("data: $completed\n\n"))
                server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(bytes.toByteArray())))
                val updates = mutableListOf<ImageGenerationProgress>()
                val result = ProviderApi.generateImage(ProviderConfig("fixture", "Fixture", server.url("/v1").toString(), "fixture"),
                    "gpt-image-2", "fixture", onProgress = { updates += it })
                try {
                    assertNull(result.error)
                    assertNotNull(result.attachment)
                    val final = updates.last()
                    assertEquals("Downloaded image dimensions must override absent/auto/stale size '$size'", width, final.width)
                    assertEquals(height, final.height)
                    assertEquals(width.toFloat() / height, final.aspectRatio, 0.001f)
                    assertFalse(final.active)
                } finally { result.attachment?.let { File(it.filePath).delete() } }
            }
        }
    }

    @Test fun squarePlaceholderResizesSmoothlyAndPreviewsOverlapDuringTheCrossfade() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(controller.get())
        val progress = mutableStateOf(ImageGenerationProgress("fixture"))
        var expectedBackground = 0
        val first = Bitmap.createBitmap(60, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val second = Bitmap.createBitmap(60, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        var frameTime = 0L
        fun frames(count: Int) {
            repeat(count) {
                shadowOf(android.os.Looper.getMainLooper()).idle()
                Snapshot.sendApplyNotifications()
                runCurrent()
                view.requestLayout()
                frameTime += 16_000_000L
                clock.sendFrame(frameTime)
                runCurrent()
                view.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, 300, 900)
            }
        }
        fun nodes(): List<SemanticsNode> {
            fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
            return descend((view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
        }
        fun tagged(tag: String) = nodes().filter { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == tag }
        fun card() = tagged("image-generation-card").single().boundsInRoot
        fun texts() = nodes().flatMap { it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { value -> value.text } }
        try {
            view.setParentCompositionContext(recomposer)
            controller.get().setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme {
                        expectedBackground = imageGenerationPlaceholderColor(LocalResolvedTheme.current.isDark).toArgb()
                        Column { ImageGenerationCard(progress.value) }
                    }
                }
            }
            frames(6)
            assertEquals("The initial square must be 25% larger than 220dp", 275f, card().width, 0.5f)
            assertEquals(275f, card().height, 0.5f)
            assertTrue(texts().contains(tr("图片生成中")))
            assertTrue(tagged("image-generation-blurred-preview").isEmpty())
            val capture = Bitmap.createBitmap(300, 900, Bitmap.Config.ARGB_8888)
            try {
                view.draw(android.graphics.Canvas(capture))
                assertEquals("The placeholder must draw the stronger gray, not the pale secondary surface",
                    expectedBackground, capture.getPixel(30, 30))
            } finally { capture.recycle() }

            progress.value = progress.value.copy(width = 1536, height = 1024, partialIndex = 0, preview = first, label = imageGenerationPhrases[0])
            frames(4)
            assertTrue("Resize must pass through intermediate heights, got ${card().height}", card().height in 184f..274f)
            frames(50)
            assertEquals(183f, card().height, 1f)
            assertEquals(1, tagged("image-generation-blurred-preview").size)

            progress.value = progress.value.copy(partialIndex = 1, preview = second, label = imageGenerationPhrases[1])
            frames(12)
            assertEquals("Outgoing and incoming blurred images must coexist", 2, tagged("image-generation-blurred-preview").size)
            frames(35)
            assertEquals(1, tagged("image-generation-blurred-preview").size)
            assertTrue(texts().contains(tr(imageGenerationPhrases[1])))
            assertFalse(texts().contains(tr(imageGenerationPhrases[0])))

            progress.value = progress.value.copy(width = 1024, height = 1536, modelText = "A quiet forest is appearing")
            frames(5)
            assertTrue("Portrait resize must also animate, not jump, got ${card().height}", card().height in 184f..274f)
            frames(50)
            assertEquals(275f, card().height, 1f)
            assertEquals("Portrait images must fit the same bounding box", 183f, card().width, 1f)
            assertTrue(texts().contains("A quiet forest is appearing"))
            assertFalse(texts().contains(tr(imageGenerationPhrases[1])))
            val caption = nodes().single { node -> node.config.getOrElse(SemanticsProperties.Text) { emptyList() }
                .any { it.text == "A quiet forest is appearing" } }.boundsInRoot
            assertTrue("Caption stays within the animated image bounds", caption.bottom < card().bottom && caption.right < card().right)
            assertTrue("Caption stays in the lower right corner", card().bottom - caption.bottom < 24f && card().right - caption.right < 30f)

            progress.value = progress.value.copy(width = 3072, height = 1024)
            frames(50)
            assertEquals(92f, card().height, 1f)
            val wideCaption = nodes().single { node -> node.config.getOrElse(SemanticsProperties.Text) { emptyList() }
                .any { it.text == "A quiet forest is appearing" } }.boundsInRoot
            assertTrue("The capsule must also fit a compact 3:1 preview", wideCaption.top > card().top && wideCaption.bottom < card().bottom)

            val stopped = ChatMessage(1, "", fromUser = false, process = listOf(
                ProcessEvent(1, ProcessEventKind.TOOL, live = true, toolName = ImageGenTool.NAME, imageGeneration = progress.value),
            )).stopImageGenerationPreviews().process.single().imageGeneration!!
            assertNull("Stop must also release the preview bitmap", stopped.preview)
            progress.value = stopped
            frames(4)
            assertTrue("Stopping must remove the entire placeholder immediately", tagged("image-generation-card").isEmpty())
            assertTrue(tagged("image-generation-blurred-preview").isEmpty())
            progress.value = progress.value.copy(failed = true)
            frames(4)
            assertTrue("Failed generations must not retain a placeholder", tagged("image-generation-card").isEmpty())
        } finally {
            view.disposeComposition(); recomposer.close(); runner.cancel()
            controller.pause().stop().destroy()
            first.recycle(); second.recycle()
        }
    }

    @Test fun previewDecoderCreatesABoundedBitmapAndRejectsMalformedData() {
        val source = Bitmap.createBitmap(2048, 1024, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream()
        try {
            assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, bytes))
            val decoded = decodeImageGenerationPreview(java.util.Base64.getEncoder().encodeToString(bytes.toByteArray()))!!
            try {
                assertTrue(decoded.width <= 1024 && decoded.height <= 1024)
                assertEquals(2f, decoded.width.toFloat() / decoded.height, 0.01f)
                val completed = ImageGenerationProgressState("final").complete(
                    ChatAttachment("fixture.png", "image/png", "/fixture.png"), decoded, "auto")
                assertEquals(2f, completed.aspectRatio, 0.01f)
                assertNull("Final chat state must release preview bitmaps", completed.preview)
            } finally { decoded.recycle() }
            assertNull(decodeImageGenerationPreview("not base64!"))
        } finally { source.recycle() }
    }
}
