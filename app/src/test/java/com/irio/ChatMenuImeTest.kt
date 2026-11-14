package com.irio

import android.os.Looper
import android.app.Dialog
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowDialog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, shadows = [ChatMenuImeTest.RecordingDialogShadow::class, ChatMenuImeTest.NoLinkNetwork::class])
class ChatMenuImeTest {
    @Implements(LinkRuntime::class, isInAndroidSdk = false)
    class NoLinkNetwork {
        private val state = MutableStateFlow(LinkUiState(settings = LinkSettings(enabled = true)))
        @Implementation fun getState(): StateFlow<LinkUiState> = state
        @Implementation fun ensure(context: android.content.Context) = Unit
        @Implementation fun refreshPairing() = Unit
    }
    @Implements(Dialog::class)
    class RecordingDialogShadow : ShadowDialog() {
        @RealObject private lateinit var dialog: Dialog
        var flagsAtShow: Int? = null
        @Implementation
        override fun show() {
            flagsAtShow = dialog.window!!.attributes.flags
            super.show()
        }
    }
    private enum class Menu { CONTEXT, MODEL, IMAGE_MODEL, ATTACH, PROTOCOL, SEARCH, STT_PROVIDER, STT_MODEL, STT_LANGUAGE, VOICE, LINK }

    @Test fun openingContextRingDoesNotTakeFocusFromComposerIme() = verify(Menu.CONTEXT, true)
    @Test fun openingModelSwitcherDoesNotTakeFocusFromComposerIme() = verify(Menu.MODEL, true)
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w360dp-h640dp-mdpi")
    @Test fun openingAttachmentMenuDoesNotTakeFocusFromComposerIme() = verify(Menu.ATTACH, true)
    @Test fun openingContextRingWithoutImeKeepsNormalMenuFocus() = verify(Menu.CONTEXT, false)
    @Test fun openingModelSwitcherWithoutImeKeepsNormalMenuFocus() = verify(Menu.MODEL, false)
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w360dp-h640dp-mdpi")
    @Test fun openingAttachmentMenuWithoutImeKeepsNormalMenuFocus() = verify(Menu.ATTACH, false)
    @Test fun transientImeInsetsDoNotMakeAnOpenMenuClaimFocus() = verify(Menu.CONTEXT, true, true)
    @Test fun imageParameterInputsCanTakeImeFocusAfterOpeningTheMenu() = verify(Menu.IMAGE_MODEL, true)
    @Test fun openingProviderProtocolPreservesFieldIme() = verify(Menu.PROTOCOL, true)
    @Test fun openingProviderProtocolWithoutImeKeepsNormalMenuFocus() = verify(Menu.PROTOCOL, false)
    @Test fun openingSearchProviderPreservesFieldIme() = verify(Menu.SEARCH, true)
    @Test fun openingSearchProviderWithoutImeKeepsNormalFocus() = verify(Menu.SEARCH, false)
    @Test fun openingSttProviderPreservesFieldIme() = verify(Menu.STT_PROVIDER, true)
    @Test fun openingSttProviderWithoutImeKeepsNormalFocus() = verify(Menu.STT_PROVIDER, false)
    @Test fun openingSttModelPreservesFieldIme() = verify(Menu.STT_MODEL, true)
    @Test fun openingSttModelWithoutImeKeepsNormalFocus() = verify(Menu.STT_MODEL, false)
    @Test fun openingSttLanguagePreservesFieldIme() = verify(Menu.STT_LANGUAGE, true)
    @Test fun openingSttLanguageWithoutImeKeepsNormalFocus() = verify(Menu.STT_LANGUAGE, false)
    @Test fun openingVoicePreservesFieldIme() = verify(Menu.VOICE, true)
    @Test fun openingVoiceWithoutImeKeepsNormalFocus() = verify(Menu.VOICE, false)
    @Test fun openingLinkSyncModePreservesFieldIme() = verify(Menu.LINK, true)
    @Test fun openingLinkSyncModeWithoutImeKeepsNormalFocus() = verify(Menu.LINK, false)

    @Test fun contextToolbarKeepsStatusBarClearanceAcrossBackgroundReturn() =
        verify(Menu.CONTEXT, false, backgroundRoundTrip = true)
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun modelToolbarKeepsStatusBarClearanceAcrossBackgroundReturn() =
        verify(Menu.MODEL, false, backgroundRoundTrip = true)
    @Test fun contextToolbarKeepsStatusBarClearanceWithImeAcrossBackgroundReturn() =
        verify(Menu.CONTEXT, true, backgroundRoundTrip = true)
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun modelToolbarKeepsStatusBarClearanceWithImeAcrossBackgroundReturn() =
        verify(Menu.MODEL, true, backgroundRoundTrip = true)

    private fun verify(menu: Menu, showIme: Boolean, transientImeInset: Boolean = false,
        backgroundRoundTrip: Boolean = false) = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val host = ComposeView(controller.get())
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val expanded = mutableStateOf(false)
        val draft = mutableStateOf("Draft")
        var attachmentState: ChatScreenState? = null
        var photoSelections = 0
        var fileSelections = 0
        var imeVisible = false
        var visibleStatusTop = 0
        var frameTime = 0L
        val canvas = android.graphics.Canvas(android.graphics.Bitmap.createBitmap(360, 640,
            android.graphics.Bitmap.Config.ARGB_8888))
        try {
            host.setParentCompositionContext(recomposer)
            controller.get().setContentView(host, ViewGroup.LayoutParams(360, 640))
            controller.windowFocusChanged(true)
            host.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
                    visibleStatusTop = WindowInsets.statusBars.getTop(LocalDensity.current)
                    AppTheme {
                        Column {
                            BasicTextField(draft.value, onValueChange = { draft.value = it },
                                modifier = Modifier.testTag("fixture-composer"))
                            @androidx.compose.runtime.Composable
                            fun MenuContent() {
                                when (menu) {
                                    Menu.SEARCH -> SearchSettingsScreen(onBack = {})
                                    Menu.LINK -> LinkSettingsScreen(onBack = {})
                                    Menu.STT_PROVIDER -> SttProviderMenu(SttProviderKind.GROQ, expanded.value,
                                        { expanded.value = it }, {})
                                    Menu.STT_MODEL -> SttModelMenu(SttProviderKind.GROQ, SttStore.defaultModel(SttProviderKind.GROQ),
                                        expanded.value, { expanded.value = it }, {})
                                    Menu.STT_LANGUAGE -> SttLanguageMenu("en", expanded.value, { expanded.value = it }, {})
                                    Menu.VOICE -> VoiceMenu(expanded.value, { expanded.value = it }, tr("系统默认"), "", emptyList(), true, {})
                                    Menu.PROTOCOL -> ProtocolPicker(
                                        protocol = ProviderProtocol.CHAT_COMPLETIONS,
                                        expanded = expanded.value,
                                        onExpandedChange = { expanded.value = it },
                                        onSelect = {},
                                    )
                                    Menu.CONTEXT -> ContextUsageRing(
                                        breakdown = ContextUsageBreakdown(0, 0, 0, 100, 0, 0, 100),
                                        compressionCount = 0, showCompression = false, compressEnabled = false,
                                        onCompress = {}, expanded = expanded.value, onExpandedChange = { expanded.value = it },
                                    )
                                    Menu.MODEL, Menu.IMAGE_MODEL -> ModelPicker(
                                        alignLeft = true, selectedModel = if (menu == Menu.IMAGE_MODEL) "gpt-image-1" else "fixture-model",
                                        providers = listOf(ProviderConfig("fixture", "Fixture", "https://example.test", "",
                                            models = listOf(if (menu == Menu.IMAGE_MODEL) "gpt-image-1" else "fixture-model"))),
                                        activeProviderId = "fixture", mergeModels = false, useModelsDev = false,
                                        useModelsDevReasoning = false, reasoningEffort = ReasoningEffort.OFF,
                                        fastMode = false, webSearchAvailable = false, webSearchEnabled = false,
                                        autoRead = false, expanded = expanded.value,
                                        onExpandedChange = { expanded.value = it }, onSelectedModelChange = { _, _ -> },
                                        onReasoningChange = {}, onFastModeChange = {}, onWebSearchChange = {},
                                        onAutoReadChange = {},
                                    )
                                    Menu.ATTACH -> Box(Modifier.fillMaxSize()) {
                                        val state = rememberChatScreenState()
                                        attachmentState = state
                                        state.pickPhotos = { photoSelections++ }
                                        state.pickFiles = { fileSelections++ }
                                        ChatComposerBar(state, 600.dp)
                                    }
                                }
                            }
                            if (backgroundRoundTrip) IrioToolbar(title = { MenuContent() }, compact = true)
                            else MenuContent()
                        }
                    }
                }
            }
            fun frame() {
                Snapshot.sendApplyNotifications(); runCurrent()
                advanceTimeBy(16); runCurrent()
                frameTime += 16_000_000L; clock.sendFrame(frameTime); runCurrent()
                shadowOf(Looper.getMainLooper()).idle()
                host.requestLayout()
                host.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
                host.layout(0, 0, 360, 640)
                if (backgroundRoundTrip) host.draw(canvas)
                if (menu == Menu.ATTACH) {
                    host.draw(canvas)
                    ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView?.let { decor ->
                        decor.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
                        decor.layout(0, 0, 360, 640)
                        decor.draw(canvas)
                    }
                }
            }
            fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
            fun nodes(view: View): List<SemanticsNode> =
                (if (view is ViewRootForTest) descendants(view.semanticsOwner.unmergedRootSemanticsNode) else emptyList()) +
                    (if (view is ViewGroup) (0 until view.childCount).flatMap { nodes(view.getChildAt(it)) } else emptyList())
            fun field() = nodes(host).single { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "fixture-composer" }
            fun statusBars(visible: Boolean, top: Int = 24) {
                ViewCompat.dispatchApplyWindowInsets(host, WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.statusBars(), if (visible) Insets.of(0, top, 0, 0) else Insets.NONE)
                    .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars(), Insets.of(0, top, 0, 0))
                    .setVisible(WindowInsetsCompat.Type.statusBars(), visible)
                    .setInsets(WindowInsetsCompat.Type.ime(), if (showIme) Insets.of(0, 0, 0, 300) else Insets.NONE)
                    .setVisible(WindowInsetsCompat.Type.ime(), showIme).build())
                repeat(3) { frame() }
            }
            repeat(8) { frame() }
            assertTrue(field().config[SemanticsActions.RequestFocus].action!!.invoke())
            frame()
            assertTrue(field().config[SemanticsProperties.Focused])
            if (showIme) ViewCompat.dispatchApplyWindowInsets(host, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 300))
                .setVisible(WindowInsetsCompat.Type.ime(), true).build())
            repeat(3) { frame() }
            assertEquals("Fixture IME precondition", showIme, imeVisible)
            if (backgroundRoundTrip) statusBars(true)
            val label = when (menu) {
                Menu.CONTEXT -> null
                Menu.MODEL, Menu.IMAGE_MODEL, Menu.STT_MODEL -> tr("选择模型")
                Menu.ATTACH -> tr("添加附件")
                Menu.PROTOCOL -> tr("选择协议")
                Menu.SEARCH -> tr("选择搜索提供方")
                Menu.STT_PROVIDER -> tr("选择提供商")
                Menu.STT_LANGUAGE -> tr("选择语言")
                Menu.VOICE -> tr("选择音色")
                Menu.LINK -> tr("选择同步方式")
            }
            fun triggerNode() = nodes(host).first { node ->
                node.config.contains(SemanticsActions.OnClick) && if (label == null)
                    node.config.contains(SemanticsProperties.Role) &&
                        node.config[SemanticsProperties.Role] == androidx.compose.ui.semantics.Role.Button
                else if (menu in setOf(Menu.PROTOCOL, Menu.SEARCH, Menu.STT_PROVIDER, Menu.STT_MODEL, Menu.STT_LANGUAGE, Menu.VOICE, Menu.LINK)) descendants(node).any {
                    label in it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
                }
                else label in node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
            }
            val trigger = triggerNode()
            assertTrue(trigger.config[SemanticsActions.OnClick].action!!.invoke())
            repeat(if (menu == Menu.ATTACH) 35 else 8) { frame() }
            if (menu != Menu.SEARCH && menu != Menu.LINK)
                assertTrue("$menu menu must open", if (menu == Menu.ATTACH) attachmentState!!.attachMenuOpen else expanded.value)
            val dialog = ShadowDialog.getLatestDialog()
            assertTrue(dialog.isShowing)
            if (menu == Menu.ATTACH) {
                val menuNodes = nodes(dialog.window!!.decorView)
                val surface = menuNodes.single {
                    it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "attachment-menu-surface"
                }
                assertEquals(220f, surface.boundsInRoot.width, .5f)
                assertEquals(124f, surface.boundsInRoot.height, .5f)
                fun item(text: String) = menuNodes.single { node ->
                    node.config.contains(SemanticsActions.OnClick) && descendants(node).any {
                        it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { value -> value.text == text }
                    }
                }
                val photos = item(tr("照片"))
                val files = item(tr("文件"))
                assertTrue("Attachment menu must cover the plus trigger: files=${files.boundsInWindow}, trigger=${trigger.boundsInWindow}",
                    files.boundsInWindow.contains(trigger.boundsInWindow.center))
                assertEquals("Attachment menu keeps a compact reference-style surface", 208f, photos.boundsInRoot.width, .5f)
                assertEquals("Compact rows keep even icon spacing", 56f, photos.boundsInRoot.height, .5f)
                for (name in listOf("photos", "files")) {
                    val icon = menuNodes.single { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "attachment-icon:$name" }
                    assertEquals(40f, icon.boundsInRoot.width, .5f)
                    assertEquals(40f, icon.boundsInRoot.height, .5f)
                    val glyph = menuNodes.single { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "attachment-glyph:$name" }
                    assertEquals(22f, glyph.boundsInRoot.width, .5f)
                    assertEquals(22f, glyph.boundsInRoot.height, .5f)
                    val row = if (name == "photos") photos else files
                    assertEquals("Attachment icon must be centered within its menu row: $name",
                        row.boundsInRoot.center.y, icon.boundsInRoot.center.y, .5f)
                    val text = menuNodes.single { node ->
                        node.config.getOrElse(SemanticsProperties.Text) { emptyList() }
                            .any { it.text == tr(if (name == "photos") "照片" else "文件") }
                    }
                    assertEquals("Attachment text must be centered within its menu row: $name",
                        row.boundsInRoot.center.y, text.boundsInRoot.center.y, .5f)
                    assertEquals("Reference keeps a clear gap between circle and label", 12f,
                        text.boundsInRoot.left - icon.boundsInRoot.right, .5f)
                }
                val capture = android.graphics.Bitmap.createBitmap(360, 640, android.graphics.Bitmap.Config.ARGB_8888)
                try {
                    capture.eraseColor(android.graphics.Color.WHITE)
                    val captureCanvas = android.graphics.Canvas(capture)
                    host.draw(captureCanvas)
                    dialog.window!!.decorView.draw(captureCanvas)
                    val target = java.io.File("build/reports/attachment-menu-${if (showIme) "ime" else "normal"}.png")
                    requireNotNull(target.parentFile).mkdirs()
                    target.outputStream().use { capture.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                } finally { capture.recycle() }
            }
            val shadow = org.robolectric.shadow.api.Shadow.extract<RecordingDialogShadow>(dialog)
            assertEquals("$menu must preserve composer IME from the first window show, not repair focus afterward",
                if (showIme) WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE else 0,
                shadow.flagsAtShow!! and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            assertEquals("$menu dialog window focus must match IME state",
                if (showIme) WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE else 0,
                dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            if (backgroundRoundTrip) {
                val originalTop = trigger.boundsInRoot.top
                assertTrue("The toolbar must initially clear the 24px status bar", originalTop >= 24f)
                controller.windowFocusChanged(false)
                controller.pause().stop()
                statusBars(false)
                assertEquals("Fixture must deliver the hidden status bar inset", 0, visibleStatusTop)
                controller.start().resume()
                controller.windowFocusChanged(showIme)
                repeat(8) { frame() }
                assertTrue("Returning must retain the open menu", dialog.isShowing && expanded.value)
                assertEquals("An open $menu must not move the toolbar into the status bar after returning",
                    originalTop, triggerNode().boundsInRoot.top, 0.5f)
                statusBars(true, 32)
                assertEquals("Fixture must deliver the new inset", 32, visibleStatusTop)
                assertEquals("Real status bar geometry changes must still update toolbar clearance",
                    originalTop + 8f, triggerNode().boundsInRoot.top, 0.5f)
                statusBars(true)
            }
            if (showIme) {
                assertTrue("Composer keeps focus for continued typing", field().config[SemanticsProperties.Focused])
                assertTrue("The IME inset stays visible", imeVisible)
            }
            if (transientImeInset) {
                ViewCompat.dispatchApplyWindowInsets(host, WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.NONE)
                    .setVisible(WindowInsetsCompat.Type.ime(), false).build())
                repeat(3) { frame() }
                assertEquals("An inset update must not let an already open menu steal focus",
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            }
            if (menu == Menu.IMAGE_MODEL) {
                val aspectRatio = nodes(dialog.window!!.decorView).first {
                    it.config.contains(SemanticsActions.OnClick) &&
                        tr("宽高比") in it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
                }
                assertTrue(aspectRatio.config[SemanticsActions.OnClick].action!!.invoke())
                repeat(35) { frame() }
                assertEquals("The model menu's editable parameter page must accept IME focus",
                    0, dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            }
            if (menu != Menu.IMAGE_MODEL && !transientImeInset) {
                if (showIme) controller.get().onBackPressedDispatcher.onBackPressed()
                else (dialog as ComponentDialog).onBackPressedDispatcher.onBackPressed()
                repeat(20) { frame() }
                assertFalse("Back dismisses $menu before affecting the composer",
                    if (menu == Menu.ATTACH) attachmentState!!.attachMenuOpen else expanded.value)
                assertFalse("Closing animation releases the menu window", dialog.isShowing)
                if (showIme) {
                    assertTrue("Closing the menu preserves composer focus", field().config[SemanticsProperties.Focused])
                    assertTrue("Closing the menu preserves IME", imeVisible)
                }
                assertTrue(trigger.config[SemanticsActions.OnClick].action!!.invoke())
                repeat(8) { frame() }
                val reopened = ShadowDialog.getLatestDialog()
                assertTrue(reopened !== dialog && reopened.isShowing)
                assertEquals("Reopening also applies the focus policy before show",
                    if (showIme) WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE else 0,
                    org.robolectric.shadow.api.Shadow.extract<RecordingDialogShadow>(reopened)
                        .flagsAtShow!! and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                if (menu == Menu.ATTACH) {
                    for ((index, text) in listOf(tr("照片"), tr("文件")).withIndex()) {
                        if (index > 0) assertTrue(triggerNode().config[SemanticsActions.OnClick].action!!.invoke())
                        repeat(35) { frame() }
                        val currentDialog = ShadowDialog.getLatestDialog()
                        val action = nodes(currentDialog.window!!.decorView).single { node ->
                            node.config.contains(SemanticsActions.OnClick) && descendants(node).any {
                                it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { value -> value.text == text }
                            }
                        }
                        assertTrue(action.config[SemanticsActions.OnClick].action!!.invoke())
                        repeat(20) { frame() }
                        assertFalse("Selection must close the menu", attachmentState!!.attachMenuOpen)
                        assertFalse("Selection must release the menu window", currentDialog.isShowing)
                    }
                    assertEquals("Photo choice must still call its picker", 1, photoSelections)
                    assertEquals("File choice must still call its picker", 1, fileSelections)
                }
            }
        } finally {
            host.disposeComposition(); recomposer.close(); runner.cancel()
            controller.pause().stop().destroy()
        }
    }
}
