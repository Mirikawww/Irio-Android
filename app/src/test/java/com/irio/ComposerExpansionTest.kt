package com.irio

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ComposerExpansionTest {
    @Test
    fun expandingPreservesSelectionAndGivesTextTheFullWidthAboveFloatingAttachmentsAndActions() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(controller.get())
        val expanded = mutableStateOf(false)
        val state = TextFieldState("one\ntwo\nthree\nfour", TextRange(2, 9))
        var lines = 0
        var textWidth = 0
        var footer = Rect.Zero
        var attachment = Rect.Zero
        var actions = Rect.Zero
        var frameTime = 0L
        try {
            view.setParentCompositionContext(recomposer)
            controller.get().setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme {
                        ComposerField(
                            state = state,
                            placeholder = "Message",
                            modifier = Modifier.fillMaxWidth().then(if (expanded.value) Modifier.height(400.dp) else Modifier),
                            expanded = expanded.value,
                            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = if (expanded.value) Int.MAX_VALUE else 4),
                            onTextLayout = { lines = it.lineCount; textWidth = it.layoutInput.constraints.maxWidth },
                            leading = { Box(Modifier.size(32.dp)) },
                            trailing = { Box(Modifier.size(32.dp)) },
                            expandedFooter = {
                                Column(Modifier.fillMaxWidth().onGloballyPositioned { footer = it.boundsInRoot() }) {
                                    Box(Modifier.fillMaxWidth().height(36.dp).onGloballyPositioned { attachment = it.boundsInRoot() })
                                    Box(Modifier.fillMaxWidth().height(56.dp).onGloballyPositioned { actions = it.boundsInRoot() })
                                }
                            },
                        )
                    }
                }
            }
            fun measure() {
                view.requestLayout()
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.AT_MOST),
                )
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            }
            fun settle() {
                repeat(4) {
                    Snapshot.sendApplyNotifications()
                    runCurrent()
                    frameTime += 16_000_000L
                    clock.sendFrame(frameTime)
                    runCurrent()
                    measure()
                }
            }
            settle()
            assertEquals(4, lines)
            val compactWidth = textWidth
            assertTrue(compactWidth > 0)
            expanded.value = true
            settle()
            assertEquals("one\ntwo\nthree\nfour", state.text.toString())
            assertEquals(TextRange(2, 9), state.selection)
            assertTrue("Floating actions must release the text width: compact=$compactWidth expanded=$textWidth", textWidth > compactWidth + 40)
            assertEquals(360f, footer.width, 0.5f)
            assertEquals(400f, actions.bottom, 0.5f)
            assertTrue("Attachments must sit above the action row", attachment.bottom <= actions.top)
            state.edit { replace(0, length, "wrapped text ".repeat(35)) }
            settle()
            assertTrue("Automatic wrapping must count as multiple lines", lines >= 4)
            val editedText = state.text.toString()
            val editedSelection = state.selection
            expanded.value = false
            settle()
            assertEquals(editedText, state.text.toString())
            assertEquals(editedSelection, state.selection)
            assertTrue(textWidth < compactWidth + 1)
        } finally {
            view.disposeComposition()
            recomposer.close()
            runner.cancel()
            controller.pause().stop().destroy()
        }
    }
}
