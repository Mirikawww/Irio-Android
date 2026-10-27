package com.irio

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.composables.ui.components.Text
import com.composables.ui.components.rememberBottomSheetState
import com.composables.ui.components.BottomSheetDetent
import com.composables.ui.theme.colors
import com.composables.ui.theme.onPrimaryColor
import com.composables.ui.theme.onPanelColor
import com.composeunstyled.theme.Theme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
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
import org.robolectric.shadows.ShadowWindowManagerImpl

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OperationSnackbarMigrationTest {
    @Test fun sharedSnackbarDoesNotClearItsTextWhileExiting() = runTest {
        val visible = mutableStateOf(false)
        val message = mutableStateOf("")
        withUi {
            Box(Modifier.fillMaxSize()) {
                IrioSnackbar(visible.value, message.value, 0.dp, onDismiss = {})
            }
        }.use { ui ->
            ui.frames()
            message.value = "Exit fixture"; visible.value = true
            ui.frames(40)
            visible.value = false; message.value = ""
            ui.frames(4)
            assertTrue("A dismissed error must retain its original text during exit", ui.hasText("Exit fixture"))
            ui.frames(40)
            assertFalse(ui.hasText("Exit fixture"))
        }
    }

    @Test fun providerSaveFailureAndHeaderValidationAreNotInlineText() = runTest {
        val fixture = ProviderConfig("fixture", "Fixture", "https://example.test", "")
        var requests = 0
        var expected = Color.Unspecified
        withUi {
            expected = Theme[colors][onPrimaryColor]
            ProviderDetailScreen("fixture", {}, {}, loadProvider = { _, _ -> fixture },
                fetchModels = { requests++; ModelFetchResult(error = "Save failed fixture") })
        }.use { ui ->
            ui.frames(30)
            val before = ui.text(tr("保存")).boundsInRoot.top
            ui.click(tr("保存"))
            ui.until { ui.hasText("Save failed fixture") }
            assertEquals(1, requests)
            assertEquals(expected, ui.textColor("Save failed fixture"))
            assertTrue("Save error must be inside a snackbar", ui.isSnackbarText("Save failed fixture"))
            assertEquals("Error must not insert a row into the form", before, ui.text(tr("保存")).boundsInRoot.top, 0.5f)
        }
        val invalid = fixture.copy(customHeaders = listOf(ProviderHeader("invalid name", "fixture")))
        withUi {
            ProviderDetailScreen("fixture", {}, {}, loadProvider = { _, _ -> invalid },
                fetchModels = { error("Invalid headers must not trigger a network request") })
        }.use { ui ->
            ui.frames(30)
            ui.click(tr("保存"))
            ui.until { ui.hasText(tr("请求头名称无效")) }
            assertTrue(ui.isSnackbarText(tr("请求头名称无效")))
            assertTrue(ui.hasEditableText("invalid name"))
            ui.scope.advanceTimeBy(5_100); ui.frames(30)
            assertFalse(ui.hasText(tr("请求头名称无效")))
            ui.click(tr("保存"))
            ui.until { ui.hasText(tr("请求头名称无效")) }
            assertTrue("The same invalid input must notify again", ui.isSnackbarText(tr("请求头名称无效")))
        }
    }

    @Test fun addProviderFailureStaysInTheDialogAndRetainsTheDraft() = runTest {
        var requests = 0
        withUi {
            ProviderSettingsScreen(onBack = {}, loadProviders = { emptyList() },
                fetchModels = { requests++; ModelFetchResult(error = "Add failed fixture") })
        }.use { ui ->
            ui.frames(30)
            ui.click(tr("添加提供商"))
            ui.frames(40)
            ui.edit(tr("名称"), "Draft fixture")
            ui.edit(tr("端点 URL"), "https://example.test")
            ui.frames()
            ui.click(tr("保存"))
            ui.until { ui.hasText("Add failed fixture") }
            ui.frames(40)
            assertEquals(1, requests)
            assertTrue(ui.isSnackbarText("Add failed fixture"))
            assertEquals("Notice must be in the modal window, not behind it",
                ui.ownerOfText(tr("取消")), ui.ownerOfText("Add failed fixture"))
            assertTrue("The notice must sit above the dialog buttons", ui.text("Add failed fixture").boundsInRoot.bottom <= ui.text(tr("取消")).boundsInRoot.top)
            assertTrue(ui.hasEditableText("Draft fixture"))
            assertTrue(ui.hasEditableText("https://example.test"))
        }
    }

    @Test fun longDiagnosticsAreAvailableInFullAndExitRetainsTheOriginalSummary() = runTest {
        val visible = mutableStateOf(false)
        val message = mutableStateOf("")
        val full = "HTTP 403\n" + "Detailed server diagnostic. ".repeat(40)
        withUi {
            Box(Modifier.fillMaxSize()) {
                OperationErrorSnackbar(visible.value, message.value, 0.dp, onDismiss = { visible.value = false; message.value = "" })
            }
        }.use { ui ->
            ui.frames()
            message.value = full; visible.value = true
            ui.frames(40)
            assertTrue(ui.hasText("HTTP 403…"))
            assertFalse(ui.hasText(full))
            ui.click(tr("查看"))
            ui.frames(40)
            assertTrue("Full diagnostics must remain readable, not be silently truncated", ui.hasText(full))
            ui.click("关闭")
            ui.frames(40)
            visible.value = false; message.value = ""
            ui.frames(1)
            assertTrue("Exit must not replace the message with an empty string", ui.hasText("HTTP 403…"))
            ui.frames(40)
            assertFalse(ui.hasText("HTTP 403…"))
        }
    }

    @Test fun mcpLoginNoticeIsInItsAuthenticationSheetAndRepeatedErrorsRestartIt() = runTest {
        lateinit var sheets: GitHubMcpSheetState
        withUi {
            val details = rememberBottomSheetState(initialDetent = BottomSheetDetent.FullyExpanded)
            val auth = rememberBottomSheetState(initialDetent = BottomSheetDetent.FullyExpanded)
            sheets = androidx.compose.runtime.remember { GitHubMcpSheetState(details, auth) }
            ScaledBottomSheet(state = details, onDismissRequest = {}) { Text("management fixture", color = Theme[colors][onPanelColor]) }
            ScaledBottomSheet(state = auth, onDismissRequest = {}) {
                Box(Modifier.fillMaxSize()) {
                    Text("authentication fixture", color = Theme[colors][onPanelColor])
                    McpLoginErrorSnackbar(sheets)
                }
            }
        }.use { ui ->
            ui.frames(40)
            sheets.loginError = "Auth failed fixture"
            ui.frames(40)
            assertTrue(ui.isSnackbarText("Auth failed fixture"))
            assertEquals(ui.ownerOfText("authentication fixture"), ui.ownerOfText("Auth failed fixture"))
            assertNotEquals(ui.ownerOfText("management fixture"), ui.ownerOfText("Auth failed fixture"))
            assertNull(sheets.detailsError)
            ui.scope.advanceTimeBy(3_800); ui.frames(1)
            val oldNonce = sheets.loginErrorNonce
            sheets.loginError = "Auth failed fixture"
            assertTrue(sheets.loginErrorNonce > oldNonce)
            ui.frames(1)
            ui.scope.advanceTimeBy(1_500); ui.frames(1)
            assertNotNull("Repeated identical login failure restarts the timer", sheets.loginError)
            ui.scope.advanceTimeBy(3_600); ui.frames(40)
            assertNull(sheets.loginError)
        }
    }

    @Test fun linkPairingAndRuntimeErrorsShareOneNotice() {
        val notice = LinkSettingsNotice()
        notice.onStatus("Listen failed")
        val nonce = notice.nonce
        notice.onPairError("Pair failed")
        assertEquals("Pair failed", notice.message)
        assertTrue(notice.nonce > nonce)
        notice.onStatus(null)
        assertTrue("Clearing runtime status must not dismiss pairing feedback", notice.visible)
        notice.onStatus("No devices")
        assertEquals("No devices", notice.message)
        val runtimeNonce = notice.nonce
        notice.onStatus("No devices")
        assertTrue(notice.nonce > runtimeNonce)
        notice.dismiss()
        assertFalse(notice.visible)
        assertEquals("No devices", notice.message)
    }

    private fun TestScope.withUi(content: @Composable () -> Unit): Ui {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        ThemeStore.ensureLoaded(controller.get())
        val previous = ThemeStore.preference
        ThemeStore.save(controller.get(), ThemePreference.DARK)
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(controller.get())
        view.setParentCompositionContext(recomposer)
        controller.get().setContentView(view)
        view.setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) { AppTheme(content) } }
        return Ui(this, view, clock) {
            view.disposeComposition(); recomposer.close(); runner.cancel()
            ThemeStore.save(controller.get(), previous)
            controller.pause().stop().destroy()
        }
    }

    private class Ui(val scope: TestScope, val view: ComposeView, val clock: BroadcastFrameClock, val dispose: () -> Unit) : AutoCloseable {
        var time = 0L
        fun roots(): List<View> {
            val windows = shadowOf(view.context.getSystemService(WindowManager::class.java)) as ShadowWindowManagerImpl
            return (listOf(view) + windows.views).distinct()
        }
        fun frames(count: Int = 8) { repeat(count) {
            Snapshot.sendApplyNotifications(); scope.advanceTimeBy(16); scope.runCurrent()
            clock.sendFrame(time.also { time += 16_000_000L }); scope.runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
            for (root in roots()) {
                root.requestLayout()
                root.measure(View.MeasureSpec.makeMeasureSpec(420, if (root === view) View.MeasureSpec.EXACTLY else View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(1200, if (root === view) View.MeasureSpec.EXACTLY else View.MeasureSpec.AT_MOST))
                root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            }
        } }
        fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
        fun nodes(root: View): List<SemanticsNode> {
            val own = if (root is ViewRootForTest) descend(root.semanticsOwner.unmergedRootSemanticsNode) else emptyList()
            return own + if (root is ViewGroup) (0 until root.childCount).flatMap { nodes(root.getChildAt(it)) } else emptyList()
        }
        fun all() = roots().flatMap(::nodes).distinctBy { it.id }
        fun hasText(value: String) = all().any { it.hasText(value) }
        fun SemanticsNode.hasText(value: String) = config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == value }
        fun text(value: String) = all().single { it.hasText(value) }
        fun ownerOfText(value: String) = roots().single { root -> nodes(root).any { it.hasText(value) } }
        fun isSnackbarText(value: String) = all().any { node ->
            node.config.getOrElse(SemanticsProperties.TestTag) { "" } == "irio-snackbar" && descend(node).any { it.hasText(value) }
        }
        fun textColor(value: String): Color {
            val results = mutableListOf<TextLayoutResult>()
            text(value).config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
            return results.single().layoutInput.style.color
        }
        fun click(value: String) {
            val node = all().filter { it.config.contains(SemanticsActions.OnClick) && descend(it).any { n -> n.hasText(value) } }
                .minBy { descend(it).size }
            assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
            frames(1)
        }
        fun edit(label: String, value: String) {
            val node = all().single { it.config.contains(SemanticsActions.SetText) && it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.contains(label) }
            assertTrue(node.config[SemanticsActions.SetText].action!!.invoke(androidx.compose.ui.text.AnnotatedString(value)))
            frames(1)
        }
        fun hasEditableText(value: String) = all().any { it.config.getOrElse(SemanticsProperties.EditableText) { androidx.compose.ui.text.AnnotatedString("") }.text == value }
        fun until(condition: () -> Boolean) {
            repeat(100) { if (condition()) return; frames(1); Thread.yield() }
            assertTrue("Expected operation feedback", condition())
            frames(30)
        }
        override fun close() = dispose()
    }
}
