package com.irio

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, shadows = [SettingsImeFocusTest.NoCredentialWrites::class])
class SettingsImeFocusTest {
    @Test fun speechApiKeyClearsFocusWhenImeHides() = verifyDismissal(tools = false)

    @Test fun recycledToolDescriptionClearsItsOwnFocusWhenImeHides() = verifyDismissal(tools = true)

    private fun verifyDismissal(tools: Boolean) = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val host = ComposeView(controller.get())
        var time = 0L
        var observedIme = false
        try {
            host.setParentCompositionContext(recomposer)
            controller.get().setContentView(host, ViewGroup.LayoutParams(360, 640))
            controller.windowFocusChanged(true)
            host.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    observedIme = WindowInsets.ime.getBottom(LocalDensity.current) > 0
                    AppTheme {
                        if (tools) ToolSettingsScreen(onBack = {}, loadTools = {
                            listOf(ToolSettingsEntry("fixture", "Fixture", "Tool description"))
                        }) else SpeechSettingsScreen(onBack = {})
                    }
                }
            }
            fun frame() {
                Snapshot.sendApplyNotifications()
                runCurrent()
                time += 16_000_000L
                clock.sendFrame(time)
                runCurrent()
                shadowOf(Looper.getMainLooper()).idle()
                // Drive native measurement explicitly: this test checks focus, not
                // RecyclerView's deferred layout traversal in a headless window.
                fun forceLayout(view: View) {
                    view.forceLayout()
                    if (view is ViewGroup) (0 until view.childCount).forEach { forceLayout(view.getChildAt(it)) }
                }
                forceLayout(host)
                host.requestLayout()
                host.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
                host.layout(0, 0, 360, 640)
                host.dispatchWindowFocusChanged(true)
            }
            fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
            fun nodes(view: View): List<SemanticsNode> {
                val own = if (view is ViewRootForTest) descendants(view.semanticsOwner.unmergedRootSemanticsNode) else emptyList()
                return own + if (view is ViewGroup) (0 until view.childCount).flatMap { nodes(view.getChildAt(it)) } else emptyList()
            }
            fun field() = nodes(host).single { it.config.contains(SemanticsProperties.EditableText) }
            fun focused() = field().config[SemanticsProperties.Focused]
            fun ime(visible: Boolean) {
                val insets = WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, if (visible) 300 else 0))
                    .setVisible(WindowInsetsCompat.Type.ime(), visible)
                    .build()
                ViewCompat.dispatchApplyWindowInsets(host, insets)
                repeat(3) { frame() }
                assertEquals("Test must deliver the actual IME inset to the screen", visible, observedIme)
            }
            repeat(8) { frame() }
            if (tools) {
                val header = nodes(host).singleOrNull { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "tool-expand:fixture" }
                    ?: error("Missing tool description header")
                assertTrue(header.config[SemanticsActions.OnClick].action!!.invoke())
                repeat(60) { frame() }
            }
            fun focus() {
                assertTrue(field().config[SemanticsActions.RequestFocus].action!!.invoke())
                frame()
                assertTrue("Fixture field must actually gain focus", focused())
            }
            ime(false)
            focus()
            advanceTimeBy(100)
            frame()
            assertTrue("An initially hidden IME must not clear a newly focused field", focused())
            ime(true)
            assertTrue("Showing the IME must preserve field focus", focused())
            ime(false)
            advanceTimeBy(100)
            repeat(3) { frame() }
            assertFalse("Closing the IME must clear the input's own focus owner (tools=$tools)", focused())
        } finally {
            host.disposeComposition()
            recomposer.close()
            runner.cancel()
            controller.pause().stop().destroy()
        }
    }

    // Persistence is outside this focus test; Robolectric has no AndroidKeyStore.
    @Implements(SttStore::class, isInAndroidSdk = false)
    class NoCredentialWrites {
        @Implementation fun saveProvider(context: android.content.Context, config: SttProviderConfig) = Unit
        @Implementation fun saveActiveProvider(context: android.content.Context, id: String) = Unit
    }
}
