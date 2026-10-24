package com.irio

import android.graphics.Bitmap
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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import coil3.EventListener
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.annotation.ExperimentalCoilApi
import coil3.decode.BitmapFactoryDecoder
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class, DelicateCoilApi::class, ExperimentalCoilApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageGenerationHistoryTest {
    @Test fun finalImagesKeepTheirLargeCardAndOriginalRatioAfterReload() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(controller.get())
        val previousImageLoader = SingletonImageLoader.get(controller.get())
        val imageDispatcher = StandardTestDispatcher(testScheduler)
        var imageError: Throwable? = null
        val imageLoader = ImageLoader.Builder(controller.get())
            .mainCoroutineContext(imageDispatcher)
            .coroutineContext(imageDispatcher)
            .components { add(BitmapFactoryDecoder.Factory()) }
            .eventListener(object : EventListener() {
                override fun onError(request: ImageRequest, result: ErrorResult) { imageError = result.throwable }
            })
            .build()
        SingletonImageLoader.setUnsafe(imageLoader)
        val message = mutableStateOf(ChatMessage(1, "", fromUser = false))
        val reactions = ReactionBounceTracker()
        val files = mutableListOf<File>()
        var opened: ChatAttachment? = null
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
                view.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, 320, 1200)
            }
        }
        fun nodes(): List<SemanticsNode> {
            fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
            return descend((view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
        }
        fun tagged(tag: String) = nodes().filter { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == tag }
        fun cards() = tagged("image-generation-card")
        fun assertFinalCard(width: Int, height: Int) {
            frames(6)
            assertEquals("A restored assistant image must use the full image card, not an attachment thumbnail", 1, cards().size)
            for (attempt in 0 until 100) {
                frames(1)
                val bounds = cards().single().boundsInRoot
                if (tagged("image-generation-final-image").isNotEmpty() &&
                    abs(bounds.width / bounds.height - width.toFloat() / height) < 0.02f) break
                imageError?.let { throw AssertionError("The fixture image failed to load", it) }
            }
            frames(60)
            assertTrue("The original final file must load, not just a square placeholder",
                tagged("image-generation-final-image").isNotEmpty())
            val bounds = cards().single().boundsInRoot
            assertEquals("The longest edge must remain 275dp after reopening", 275f, maxOf(bounds.width, bounds.height), 1f)
            assertEquals("The saved image must retain its original aspect ratio", width.toFloat() / height,
                bounds.width / bounds.height, 0.02f)
        }
        try {
            view.setParentCompositionContext(recomposer)
            controller.get().setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme {
                        Column {
                            MessageRow(
                                chatMessage = message.value,
                                reactionAnimations = reactions,
                                markdownScope = this@runTest,
                                onOpenProcess = {},
                                onOpenAttachment = { opened = it },
                                onCopy = {}, onQuote = {}, onAddReaction = {}, onRemoveReaction = {},
                                onRetry = {}, canRetry = false,
                            )
                        }
                    }
                }
            }
            for ((index, dimensions) in listOf(160 to 160, 160 to 90, 90 to 160).withIndex()) {
                val (width, height) = dimensions
                val file = File.createTempFile("generated-$index-", ".png", controller.get().cacheDir).also(files::add)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try { file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
                finally { bitmap.recycle() }
                val attachment = ChatAttachment(file.name, "image/png", file.absolutePath)
                val original = ChatMessage(index + 1L, "", fromUser = false, attachments = listOf(attachment),
                    process = listOf(ProcessEvent(index + 1L, ProcessEventKind.TOOL,
                        toolName = ImageGenTool.NAME, content = ImageGenTool.STATUS_OK,
                        imageGeneration = ImageGenerationProgress("image-$index", width, height,
                            finalAttachment = attachment, active = false))))
                message.value = original
                frames(6)
                assertEquals("Live final output must not also render an attachment card", 1, cards().size)

                val saved = ConversationStore.conversationJson(Conversation("fixture", "Fixture", 1, listOf(original)))
                val restored = ConversationStore.parseConversation(JSONObject(saved.toString())).messages.single()
                assertNull("Preview state intentionally does not survive serialization", restored.process.single().imageGeneration)
                message.value = restored
                assertFinalCard(width, height)
                assertTrue(cards().single().config[SemanticsActions.OnClick].action!!.invoke())
                assertEquals("Tapping the restored card must still open the original attachment", attachment, opened)

                message.value = restored.copy(fromUser = true)
                frames(8)
                assertTrue("User uploads must keep their compact attachment thumbnails", cards().isEmpty())
                val thumbnail = nodes().single { it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
                    .contains(attachment.name) }.boundsInRoot
                assertTrue("User uploads must not become large generated-image cards", thumbnail.width < 100f)
            }
            val document = File.createTempFile("attachment-", ".txt", controller.get().cacheDir).also(files::add)
            document.writeText("fixture")
            message.value = ChatMessage(10, "", fromUser = false,
                attachments = listOf(ChatAttachment("fixture.txt", "text/plain", document.absolutePath)))
            frames(8)
            assertTrue("Non-image attachments must keep their ordinary file cards", cards().isEmpty())
            assertTrue(nodes().any { node -> node.config.getOrElse(SemanticsProperties.Text) { emptyList() }
                .any { it.text == "fixture" } })
        } finally {
            view.disposeComposition(); recomposer.close(); runner.cancel()
            SingletonImageLoader.setUnsafe(previousImageLoader)
            imageLoader.shutdown()
            controller.pause().stop().destroy()
            files.forEach { it.delete() }
        }
    }
}
