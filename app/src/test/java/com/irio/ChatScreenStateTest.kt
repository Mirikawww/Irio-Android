package com.irio

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ChatScreenStateTest {
    @Test
    fun recompositionRetainsTheStateAndComposerDraft() = runTest {
        withState { harness ->
            val original = harness.state
            original.message.edit { append("unsent") }
            original.pendingQuote = "quote"
            original.composerFullscreen = true
            harness.recompose()
            assertSame(original, harness.state)
            assertEquals("unsent", harness.state.message.text.toString())
            assertEquals("quote", harness.state.pendingQuote)
            assertTrue(harness.state.composerFullscreen)
        }
    }

    @Test
    fun switchingConversationRestoresTheEntireComposerThroughTheRealActions() = runTest {
        withState { harness ->
            val state = harness.state
            val attachment = ChatAttachment("a.txt", "text/plain", "fixture/a.txt")
            state.message.edit { append("first draft") }
            state.pendingAttachments = listOf(attachment)
            state.editingMessageId = 12L
            state.pendingQuote = "quoted"
            state.composerStatus = "old error"
            state.composerFullscreen = true

            state.swapComposer("first", "second")
            assertEquals("", state.message.text.toString())
            assertTrue(state.pendingAttachments.isEmpty())
            assertNull(state.editingMessageId)
            assertNull(state.composerStatus)
            assertFalse(state.composerFullscreen)
            state.message.edit { append("second draft") }
            state.swapComposer("second", "first")
            assertEquals("first draft", state.message.text.toString())
            assertEquals(listOf(attachment), state.pendingAttachments)
            assertEquals(12L, state.editingMessageId)
            assertEquals("quoted", state.pendingQuote)
            assertEquals("second draft", state.composerDrafts["second"]?.text)
        }
    }

    @Test
    fun busyGuardsObservePreflightAndGenerationBeforeAnotherComposeFrame() = runTest {
        withState { harness ->
            val state = harness.state
            val id = state.currentConversation.id
            assertFalse(state.busyThisChat)
            state.preparing = mapOf(1L to id)
            assertTrue(state.busyThisChat)
            assertFalse(state.generatingThisChat)
            state.preparing = emptyMap()
            state.generations = mapOf(1L to ActiveGeneration(id, Job(), ChatRequestCancellation()))
            assertTrue(state.busyThisChat)
            assertTrue(state.generatingThisChat)
            state.currentConversation = ConversationStore.newConversation()
            assertFalse(state.busyThisChat)
            assertFalse(state.generatingThisChat)
        }
    }

    @Test
    fun presentationDoesNotLeakMessagesOrVersionGroupsAcrossConversations() = runTest {
        withState { harness ->
            val state = harness.state
            val user = ChatMessage(1, "question", true)
            val reply = ChatMessage(2, "answer", false)
            state.transcriptPresentation =
                buildTranscriptPresentation(state.currentConversation.id, listOf(user, reply))
            assertEquals(listOf(user, reply), state.shownTranscript)
            assertTrue(state.retryGroups.isNotEmpty())
            state.currentConversation = ConversationStore.newConversation()
            assertTrue(state.shownTranscript.isEmpty())
            assertTrue(state.retryGroups.isEmpty())
            assertTrue(state.editGroups.isEmpty())
        }
    }

    private suspend fun TestScope.withState(block: suspend (Harness) -> Unit) {
        val harness = Harness(this)
        try {
            harness.start()
            block(harness)
        } finally {
            harness.composition.dispose()
            harness.recomposer.close()
            harness.runner.cancel()
            runCurrent()
        }
    }

    private class Harness(val scope: TestScope) {
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(scope.coroutineContext + clock)
        val runner = scope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodesApplier(), recomposer)
        val revision = mutableIntStateOf(0)
        lateinit var state: ChatScreenState

        fun start() {
            composition.setContent {
                revision.intValue
                CompositionLocalProvider(
                    LocalContext provides RuntimeEnvironment.getApplication(),
                    LocalDensity provides Density(1f),
                    LocalFocusManager provides TestFocusManager,
                    LocalClipboardManager provides TestClipboard,
                    LocalWindowInfo provides TestWindowInfo,
                ) {
                    state = rememberChatScreenState()
                }
            }
            scope.runCurrent()
        }

        fun recompose() {
            revision.intValue++
            Snapshot.sendApplyNotifications()
            scope.runCurrent()
            clock.sendFrame(16_000_000L)
            scope.runCurrent()
        }
    }

    private object TestFocusManager : FocusManager {
        override fun clearFocus(force: Boolean) = Unit

        override fun moveFocus(focusDirection: FocusDirection) = false
    }

    private object TestClipboard : ClipboardManager {
        override fun setText(annotatedString: AnnotatedString) = Unit

        override fun getText(): AnnotatedString? = null
    }

    private object TestWindowInfo : WindowInfo {
        override val isWindowFocused = true
        override val containerSize = IntSize(1080, 1920)
    }

    private class NoNodesApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = error("No UI nodes expected")

        override fun insertBottomUp(index: Int, instance: Unit) = error("No UI nodes expected")

        override fun remove(index: Int, count: Int) = error("No UI nodes expected")

        override fun move(from: Int, to: Int, count: Int) = error("No UI nodes expected")

        override fun onClear() = Unit
    }
}
