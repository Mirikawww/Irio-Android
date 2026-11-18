package com.irio

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
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

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ComposerPasteTest {
    @Test fun realComposerInterceptsPasteWithoutTouchingExistingDraftOrProgrammaticEdits() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val context = controller.get()
        ThemeStore.ensureLoaded(context)
        val previousEnabled = ThemeStore.longTextPaste
        val previousThreshold = ThemeStore.longTextPasteThreshold
        val previousEnter = ThemeStore.enterToSend
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(context)
        val state = TextFieldState("existing draft")
        val focus = FocusRequester()
        val attached = mutableListOf<String>()
        val files = mutableListOf<List<Uri>>()
        var sends = 0
        var frame = 0L
        try {
            ThemeStore.saveLongTextPaste(context, true)
            ThemeStore.saveLongTextPasteThreshold(context, 1500)
            view.setParentCompositionContext(recomposer)
            context.setContentView(view)
            view.setContent {
                AppTheme {
                    ComposerField(
                        state = state,
                        placeholder = "Message",
                        modifier = Modifier.fillMaxWidth(),
                        focusRequester = focus,
                        onEnterSend = { sends++ },
                        onPasteAsAttachment = { attached += it; true },
                        onPasteAttachments = { files += it; true },
                    )
                }
            }
            fun settle() {
                repeat(4) {
                    Snapshot.sendApplyNotifications()
                    runCurrent()
                    clock.sendFrame(++frame * 16_000_000L)
                    runCurrent()
                    view.measure(
                        View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.AT_MOST),
                    )
                    view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                }
            }
            fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
            fun paste(clip: ClipData) {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(clip)
                val root = (view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode
                val editor = descend(root).single { it.config.contains(SemanticsActions.PasteText) }
                assertTrue(editor.config[SemanticsActions.PasteText].action!!.invoke())
                settle()
            }
            fun pasteText(text: String) = paste(ClipData.newPlainText("test", text))
            settle()
            focus.requestFocus()
            settle()
            val pasted = "line\n".repeat(301)
            pasteText(pasted)
            assertEquals(listOf(pasted), attached)
            assertEquals("existing draft", state.text.toString())
            assertEquals(0, sends)
            ThemeStore.saveLongTextPaste(context, false)
            settle()
            val image = Uri.parse("content://paste.fixture/image.png")
            val pdf = Uri.parse("content://paste.fixture/document.pdf")
            paste(ClipData("attachments", arrayOf("image/png", "application/pdf"), ClipData.Item(image)).apply {
                addItem(ClipData.Item(pdf))
            })
            assertEquals("Real paste must forward every attachment even with long-text paste disabled", listOf(listOf(image, pdf)), files)
            assertEquals("existing draft", state.text.toString())
            paste(ClipData.newPlainText("mixed", "caption").apply { addItem(ClipData.Item(image)) })
            assertEquals(listOf(image), files.last())
            assertTrue(state.text.toString().contains("caption"))
            pasteText("ordinary paste")
            assertTrue(state.text.toString().contains("ordinary paste"))
            assertEquals(1, attached.size)
            assertEquals(0, sends)
            ThemeStore.saveLongTextPaste(context, true)
            state.edit { replace(0, length, "p".repeat(3000)) }
            settle()
            assertEquals("p".repeat(3000), state.text.toString())
            assertEquals(1, attached.size)
        } finally {
            view.disposeComposition()
            recomposer.close()
            runner.cancel()
            ThemeStore.saveLongTextPaste(context, previousEnabled)
            ThemeStore.saveLongTextPasteThreshold(context, previousThreshold)
            ThemeStore.saveEnterToSend(context, previousEnter)
            controller.pause().stop().destroy()
        }
    }
}
