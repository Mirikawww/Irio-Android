package com.irio

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
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

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatSnackbarPlacementTest {
    @Test fun sharedChatNoticeClearsTheComposerWithoutAvoidingItsHaze() = verify(composerError = false)
    @Test fun composerErrorUsesOnlyOneGapAboveTheComposer() = verify(composerError = true)
    @Test fun startupUpdateClearsTheComposerAndKeyboard() = verify(composerError = false, startup = true)
    @Test fun startupUpdateWaitsForComposerErrorsInsteadOfOverlappingThem() = verify(composerError = true, startup = true)
    @Test fun startupUpdateWaitsUntilFullscreenInputCloses() = verify(composerError = false, startup = true, fullscreen = true)
    @Test fun startupUpdateStaysBehindTheDrawerWhenItOpens() = verify(composerError = false, startup = true, drawer = true)

    private fun verify(composerError: Boolean, startup: Boolean = false, fullscreen: Boolean = false,
        drawer: Boolean = false) = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val context = controller.get()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(context)
        lateinit var state: ChatScreenState
        val startupCheck = StartupUpdateCheck(this)
        var time = 0L
        fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
        fun nodes() = descendants((view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
        fun frame() {
            Snapshot.sendApplyNotifications()
            advanceTimeBy(16); runCurrent()
            clock.sendFrame(time.also { time += 16_000_000L }); runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
            view.requestLayout()
            view.measure(View.MeasureSpec.makeMeasureSpec(420, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 420, 1000)
            (view.getChildAt(0) as ViewRootForTest).measureAndLayoutForTest()
        }
        fun frames(count: Int = 12) = repeat(count) { frame() }
        fun insets(ime: Int, navigation: Int) {
            ViewCompat.dispatchApplyWindowInsets(view, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navigation))
                .setVisible(WindowInsetsCompat.Type.navigationBars(), navigation > 0)
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime))
                .setVisible(WindowInsetsCompat.Type.ime(), ime > 0).build())
            frames()
        }
        try {
            view.setParentCompositionContext(recomposer)
            context.setContentView(view, ViewGroup.LayoutParams(420, 1000))
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme {
                        state = rememberChatScreenState()
                        ChatScreenLayout(state, startupCheck = startupCheck)
                    }
                }
            }
            frames()
            if (fullscreen) {
                state.composerFullscreen = true
                frames(60)
            }
            if (startup) startupCheck.start {
                UpdateCheckResult.Available(GitHubUpdate("fixture-update", "https://example.test/release", false))
            }
            if (fullscreen) {
                frames(60)
                assertTrue("Startup notices must not overlay the fullscreen editor", nodes().none {
                    it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "irio-snackbar-surface"
                })
                assertNotNull("Fullscreen editing must retain rather than consume the update", startupCheck.notice.value)
                state.composerFullscreen = false
                frames(60)
            }
            val configurations = if (startup) listOf(0 to 24, 300 to 24) else listOf(0 to 24, 300 to 24, 0 to 0)
            for ((ime, navigation) in configurations) {
                insets(ime, navigation)
                for (quote in if (startup) listOf("") else listOf("", "A quoted passage above the composer")) {
                    state.pendingQuote = quote
                    state.message.edit { replace(0, length, if (quote.isEmpty()) "Draft" else "Line 1\nLine 2\nLine 3") }
                    if (composerError) {
                        state.composerStatus = "Composer error placement"
                        state.composerStatusNonce++
                    } else if (!startup) {
                        state.titleGeneration.showNotice("Shared chat notice placement")
                    }
                    frames(60)
                    val surface = nodes().single {
                        it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "irio-snackbar-surface"
                    }.boundsInRoot
                    val composerTop = 1000f - ime - state.compactComposerHeightPx + ChatComposerHazeBleed.value
                    val field = nodes().single { it.config.contains(SemanticsProperties.EditableText) }.boundsInRoot
                    assertTrue("The measured obstruction must contain the real editor: field=$field, top=$composerTop, bar=${state.composerBarHeightPx}, ime=$ime, nav=$navigation", field.top >= composerTop - 1f)
                    assertTrue("The editor must be visible above the IME/navigation bar", field.bottom <= 1000f - maxOf(ime, navigation) + 1f)
                    assertEquals("Notice must clear actual composer controls by 16dp, without adding Haze or a second gap (ime=$ime, nav=$navigation, quote=$quote)",
                        16f, composerTop - surface.bottom, 1f)
                    if (startup && !composerError) assertTrue("The real update notice must retain its View action", nodes().any {
                        it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { text -> text.text == tr("查看") }
                    })
                    if (drawer) {
                        state.drawerProgress = 1f
                        frames(45)
                        val surface = nodes().single { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "irio-snackbar-surface" }
                        val settings = nodes().first { tr("设置") in it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() } }
                        val noticePath = generateSequence(surface) { it.parent }.toList().reversed()
                        val drawerPath = generateSequence(settings) { it.parent }.toList().reversed()
                        val shared = noticePath.zip(drawerPath).takeWhile { it.first.id == it.second.id }.size
                        val siblings = noticePath[shared - 1].children.map { it.id }
                        assertTrue("The notice must paint below the drawer, not on an app-wide foreground layer",
                            siblings.indexOf(noticePath[shared].id) < siblings.indexOf(drawerPath[shared].id))
                        assertNotNull("Opening the drawer must not consume the pending update", startupCheck.notice.value)
                        state.drawerProgress = 0f
                        frames(45)
                    }
                }
            }
            if (startup && composerError) {
                assertNotNull("The update must stay pending while the error occupies the lane", startupCheck.notice.value)
                state.composerStatus = null
                frames(60)
                assertTrue("The queued update must appear when the composer error leaves", nodes().any {
                    it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { text -> text.text == tr("查看") }
                })
            }
        } finally {
            view.disposeComposition(); recomposer.close(); runner.cancel()
            controller.pause().stop().destroy()
        }
    }
}
