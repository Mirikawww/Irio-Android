package com.irio

import java.io.File
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.robolectric.Robolectric
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class VideoDeliveryTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun video(url: String = "https://cdn.example.test/video.mp4") = ChatAttachment("video.mp4", "video/mp4", "", remoteUrl = url)

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun videoStatusUsesTheNativeToolSweepUntilTheTaskCompletes() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(controller.get())
        val pending = VideoGenerationJob("highlight-task", "conversation", "call", "video", "MiniMax-H3", "chat", "chat-model")
        val event = mutableStateOf(ProcessEvent(1, ProcessEventKind.TOOL, live = true, toolName = VideoGenTool.NAME))
        try {
            view.setParentCompositionContext(recomposer)
            controller.get().setContentView(view)
            view.setContent { AppTheme { ProcessEventRow(event.value, onOpen = {}) } }
            var frameTime = 0L
            fun frames() {
                Snapshot.sendApplyNotifications()
                runCurrent()
                frameTime += 16_000_000L
                clock.sendFrame(frameTime)
                runCurrent()
                view.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.AT_MOST))
                view.layout(0, 0, 320, view.measuredHeight)
            }
            fun nodes(): List<SemanticsNode> {
                fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
                return descend((view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
            }
            fun sweeps() = nodes().filter { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "status-sweep" }
            frames()
            assertEquals("Direct video generation must use the native tool highlight", 1, sweeps().size)
            event.value = event.value.copy(live = false, content = pending.resultJson(), toolOutcome = ToolOutcome.SUCCESS)
            frames()
            assertTrue(videoGenerationPending(event.value))
            assertEquals("The pending task must use the same highlighted composable as native tool calls", 1, sweeps().size)
            assertTrue(sweeps().single().config[SemanticsProperties.Text].any { it.text == tr("视频生成中") })
            event.value = event.value.copy(content = pending.copy(finished = true, url = "https://cdn.example.test/video.mp4").resultJson())
            frames()
            assertFalse(videoGenerationPending(event.value))
            assertTrue("A finished task must stop sweeping", sweeps().isEmpty())
            assertTrue(nodes().any { node -> node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == tr("已生成视频") } })
            view.setContent { AppTheme {
                AssistantAttachmentList(listOf(video(), video("https://cdn.example.test/second.mp4")), onOpen = {})
            } }
            frames()
            assertEquals("Real assistant results must use the downloadable video card for every remote attachment", 2,
                nodes().count { tr("下载视频") in it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() } })
            view.setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) { AppTheme {
                androidx.compose.foundation.layout.Column {
                    GeneratedVideoAttachmentCard(video(), modifier = Modifier.testTag("generated-video-card"))
                    AttachmentCard(ChatAttachment("ordinary.mp4", "video/mp4", ""), modifier = Modifier.testTag("ordinary-attachment"))
                }
            } } }
            frames()
            fun tagged(tag: String) = nodes().single { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == tag }
            assertEquals("Generated video output alone must use the longer card", 280f, tagged("generated-video-card").boundsInRoot.width, 0.01f)
            assertEquals("Ordinary attachments must retain their existing width", 192f, tagged("ordinary-attachment").boundsInRoot.width, 0.01f)
        } finally {
            view.disposeComposition()
            recomposer.close()
            runner.cancel()
            controller.pause().stop().destroy()
        }
    }

    @Test fun remoteVideoSurvivesSaveReloadAndLinkWithoutAFileOrPrivatePath() {
        val attachment = video()
        val conversation = ConversationStore.newConversation().copy(messages = listOf(ChatMessage(1, "", false, attachments = listOf(attachment))))
        ConversationStore.save(app, conversation)
        try {
            assertEquals(attachment, ConversationStore.load(app, conversation.id)!!.messages.single().attachments.single())
            val encoded = LinkConversationJson.encode(conversation)
            assertFalse(encoded.toString().contains("filePath"))
            assertEquals(attachment, LinkConversationJson.decode(encoded).messages.single().attachments.single())
            val request = conversation.messages.single().requestMessages().single()
            assertTrue("Generated output is metadata, not an implicit file upload", request.attachments.isEmpty())
            assertTrue(request.text.contains(attachment.remoteUrl))
        } finally { ConversationStore.delete(app, conversation.id) }
    }

    @Test fun downloadedVideoIsReusedWithoutRedownloadingAndNeverSendsCredentials() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "video/mp4").setBody("local video transport fixture"))
            val attachment = video(server.url("/video.mp4").toString())
            GeneratedVideoDownloads.target(app, attachment).delete()
            try {
                assertNull(GeneratedVideoDownloads.local(app, attachment))
                val downloaded = GeneratedVideoDownloads.download(app, attachment, ChatRequestCancellation())
                assertTrue(downloaded.downloaded)
                assertEquals("local video transport fixture", File(downloaded.filePath).readText())
                val request = server.takeRequest()
                assertEquals("GET", request.method)
                assertNull(request.getHeader("Authorization"))
                assertEquals(downloaded, GeneratedVideoDownloads.local(app, attachment))
                assertEquals(downloaded, GeneratedVideoDownloads.download(app, attachment, ChatRequestCancellation()))
                assertEquals(1, server.requestCount)
            } finally { GeneratedVideoDownloads.target(app, attachment).delete() }
        }
    }

    @Test fun failedDownloadLeavesNoCompletedFileAndCanBeRetried() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("expired"))
            server.enqueue(MockResponse().setHeader("Content-Type", "video/mp4").setBody("retry fixture"))
            val attachment = video(server.url("/video.mp4").toString())
            GeneratedVideoDownloads.target(app, attachment).delete()
            try {
                assertThrows(IllegalArgumentException::class.java) { GeneratedVideoDownloads.download(app, attachment, ChatRequestCancellation()) }
                assertNull(GeneratedVideoDownloads.local(app, attachment))
                assertTrue(GeneratedVideoDownloads.download(app, attachment, ChatRequestCancellation()).downloaded)
            } finally { GeneratedVideoDownloads.target(app, attachment).delete() }
        }
    }

    @Test fun videoTaskUsesItsConfiguredProviderAndReturnsBeforeGenerationFinishes() = runBlocking {
        val keyField = ProviderStore::class.java.getDeclaredField("cachedSecretKey").apply { isAccessible = true }
        val previousKey = keyField.get(ProviderStore)
        keyField.set(ProviderStore, SecretKeySpec(ByteArray(32) { 7 }, "AES"))
        ProviderStore.preferences(app).edit().clear().commit()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"task_id":"video-task"}"""))
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            val selected = ProviderConfig("video", "Video", server.url("/").toString(), "fixture-video", ProviderProtocol.MINIMAX_VIDEO,
                models = listOf("MiniMax-H3"), selectedModel = "MiniMax-H3")
            ProviderStore.saveProvider(app, selected)
            val chat = ProviderConfig("chat", "Chat", "https://never-contact.example.test", "fixture-chat")
            val updates = mutableListOf<ChatStreamUpdate>()
            val registry = ToolRegistry(listOf(VideoGenToolProvider("MiniMax-H3")))
            try {
                val result = kotlinx.coroutines.withTimeout(5_000) {
                    registry.execute(ChatToolCall("call-video", VideoGenTool.NAME,
                        """{"prompt":"A real protocol fixture","resolution":"768P","duration":5,"ratio":"16:9","mode":"text"}"""),
                        ToolExecutionContext(chat, "chat-model", appContext = app, conversationId = "video-origin", onUpdate = { updates += it }))
                }
                assertEquals(ToolOutcome.SUCCESS, result.outcome)
                assertFalse("The chat model may continue normally", result.endTurn)
                assertEquals("video-task", JSONObject(result.text).getString("task_id"))
                val create = server.takeRequest()
                assertEquals("/v2/video_generation", create.path)
                assertEquals("Bearer fixture-video", create.getHeader("Authorization"))
                assertEquals(listOf(true, false), updates.mapNotNull { it.toolActive })
                assertTrue(updates.none { it.attachments.isNotEmpty() })
            } finally {
                VideoGenerationJobs.forgetConversation("video-origin")
                ProviderStore.preferences(app).edit().clear().commit()
                keyField.set(ProviderStore, previousKey)
            }
        }
    }

    @Test fun taskSnapshotNeverPersistsProviderCredentialsAndStaticPromptDoesNotMerge() {
        val job = VideoGenerationJob("task", "conversation", "call", "video-provider", "MiniMax-H3", "chat-provider", "chat-model")
        assertEquals(job, VideoGenerationJob.fromJson(job.toJson()))
        assertFalse(job.toJson().has("apiKey"))
        val event = ProcessEvent(1, ProcessEventKind.TOOL, job.resultJson(), toolName = VideoGenTool.NAME, toolOutcome = ToolOutcome.SUCCESS)
        assertEquals(tr("视频生成中"), event.completedLabel())
        assertEquals(listOf(event), renderedProcessEvents(listOf(event), true))
        assertEquals(tr("已生成视频"), event.copy(content = job.copy(finished = true).resultJson()).completedLabel())
    }

    @Test fun downloadedCardOpensAnExplicitVideoPlayerChooserWithReadPermission() {
        val uri = android.net.Uri.parse("content://com.irio.debug.fileprovider/files/attachments/video.mp4")
        val chooser = attachmentViewChooser(uri, video().copy(filePath = "local.mp4"))
        assertEquals(android.content.Intent.ACTION_CHOOSER, chooser.action)
        val view = chooser.getParcelableExtra(android.content.Intent.EXTRA_INTENT, android.content.Intent::class.java)!!
        assertEquals(android.content.Intent.ACTION_VIEW, view.action)
        assertEquals("video/mp4", view.type)
        assertEquals(uri, view.data)
        assertTrue(view.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test fun videoSchemasSerializeForEveryChatProtocolAndSettingsExposePrerequisite() {
        ProviderProtocol.entries.filter { it != ProviderProtocol.MINIMAX_VIDEO }.forEach { protocol ->
            val schema = VideoGenTool.schema(protocol)
            assertTrue(schema.toString().contains(VideoGenTool.NAME))
        }
        val entry = ToolCatalog.entries(ToolFeatureState()).single { it.name == VideoGenTool.NAME }
        assertEquals(ToolBlockedReason.VIDEO_MODEL, entry.blockedReason)
        assertNull(ToolCatalog.entries(ToolFeatureState(videoConfigured = true)).single { it.name == VideoGenTool.NAME }.blockedReason)
    }
}
