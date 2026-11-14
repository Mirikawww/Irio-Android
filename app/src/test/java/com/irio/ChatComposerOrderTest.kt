package com.irio

import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, shadows = [ChatComposerOrderTest.Speech::class,
    ChatComposerOrderTest.Jobs::class, ChatComposerOrderTest.Scroll::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class ChatComposerOrderTest {
    companion object {
        var playback by mutableStateOf(SpeechPlayback())
        var commands by mutableStateOf(emptyList<LinuxBackgroundCommand>())
    }
    @Implements(SpeechPlayer::class, isInAndroidSdk = false)
    class Speech {
        @Implementation fun getPlayback(): SpeechPlayback = playback
    }
    @Implements(LinuxBackgroundJobs::class, isInAndroidSdk = false)
    class Jobs {
        @Implementation fun running(): List<LinuxBackgroundCommand> = commands
        @Implementation fun subscribe(listener: () -> Unit): () -> Unit = {}
    }
    @Implements(MessageRecyclerController::class, isInAndroidSdk = false)
    class Scroll {
        @Implementation fun getScrollButtonHidden(): Boolean = false
    }

    @Test fun composerAccessoriesKeepRequestedBottomToTopOrder() = verify(order = true)
    @Test fun returnButtonClearsEverySnackbarAndReclaimsItsSpaceAfterDismissal() = verify(order = false)

    private fun verify(order: Boolean) = runTest {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(activity.get())
        lateinit var state: ChatScreenState
        val startup = StartupUpdateCheck(this)
        var time = 0L
        fun descend(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::descend)
        fun nodes() = descend((view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
        fun text(value: String) = nodes().single {
            it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { t -> t.text == value }
        }.boundsInRoot
        fun tagged(tag: String) = nodes().single {
            it.config.getOrElse(SemanticsProperties.TestTag) { "" } == tag
        }.boundsInRoot
        fun frames(count: Int = 40) = repeat(count) {
            Snapshot.sendApplyNotifications(); advanceTimeBy(16); runCurrent()
            time += 16_000_000L; clock.sendFrame(time); runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
            view.requestLayout()
            view.measure(View.MeasureSpec.makeMeasureSpec(420, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 420, 1000)
            (view.getChildAt(0) as ViewRootForTest).measureAndLayoutForTest()
        }
        try {
            playback = SpeechPlayback(active = true, durationMs = 60_000)
            commands = listOf(LinuxBackgroundCommand(1, "fixture", "", false, true, null, false))
            view.setParentCompositionContext(recomposer)
            activity.get().setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme { state = rememberChatScreenState(); ChatScreenLayout(state, startup) }
                }
            }
            frames()
            state.message.edit { replace(0, length, "Draft") }
            state.pendingAttachments = listOf(ChatAttachment("fixture.txt", "text/plain", ""))
            state.pendingQuote = "Fixture quote"
            state.editingMessageId = 99L
            state.stickToBottom = false
            state.transcriptPresentation = buildTranscriptPresentation(state.currentConversation.id,
                listOf(ChatMessage(1, "Existing reply", false)))
            ViewCompat.dispatchApplyWindowInsets(view, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 24))
                .setVisible(WindowInsetsCompat.Type.navigationBars(), true).build())
            state.composerStatus = "Fixture snackbar"
            frames()
            if (order) {
                val field = nodes().single { it.config.contains(SemanticsProperties.EditableText) }.boundsInRoot
                val attachment = text("fixture")
                val quote = text("Fixture quote")
                val edit = text(tr("正在编辑消息"))
                val background = text(tr("%s 个后台命令").format(1))
                val speech = text(formatSpeechClock(60_000))
                val snackbar = tagged("irio-snackbar-surface")
                val scroll = tagged("scroll-to-bottom")
                val bottomToTop = listOf(field, attachment, quote, edit, background, speech, snackbar, scroll)
                bottomToTop.zipWithNext().forEachIndexed { i, (lower, upper) ->
                    assertTrue("Wrong bottom-to-top order at $i: lower=$lower upper=$upper", upper.bottom <= lower.top)
                }
            } else {
                val first = tagged("scroll-to-bottom")
                assertTrue("Return button must be above composer errors", first.bottom < tagged("irio-snackbar-surface").top)
                state.composerStatus = "Longer snackbar content with wrapping lines. ".repeat(4)
                frames()
                assertTrue("Return button must clear multiline notices", tagged("scroll-to-bottom").bottom < tagged("irio-snackbar-surface").top)
                state.composerStatus = null
                frames()
                val idle = tagged("scroll-to-bottom")
                assertTrue("Dismissal must release the old snackbar space", idle.bottom > first.bottom)
                state.titleGeneration.showNotice("Title fixture")
                frames()
                assertTrue("Return button must clear shared title notices", tagged("scroll-to-bottom").bottom < tagged("irio-snackbar-surface").top)
                state.titleGeneration.notice = null
                frames()
                startup.start { UpdateCheckResult.Available(GitHubUpdate("fixture-update", "https://example.test", false)) }
                frames()
                assertTrue("Return button must clear startup notices with actions", tagged("scroll-to-bottom").bottom < tagged("irio-snackbar-surface").top)
                ViewCompat.dispatchApplyWindowInsets(view, WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 300))
                    .setVisible(WindowInsetsCompat.Type.ime(), true).build())
                frames()
                assertTrue("IME must preserve ordering", tagged("scroll-to-bottom").bottom < tagged("irio-snackbar-surface").top)
            }
        } finally {
            view.disposeComposition(); recomposer.close(); runner.cancel()
            playback = SpeechPlayback(); commands = emptyList()
            activity.pause().stop().destroy()
        }
    }
}
