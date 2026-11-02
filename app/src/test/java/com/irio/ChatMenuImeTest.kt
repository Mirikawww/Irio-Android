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
    @Test fun openingAttachmentMenuDoesNotTakeFocusFromComposerIme() = verify(Menu.ATTACH, true)
    @Test fun openingContextRingWithoutImeKeepsNormalMenuFocus() = verify(Menu.CONTEXT, false)
    @Test fun openingModelSwitcherWithoutImeKeepsNormalMenuFocus() = verify(Menu.MODEL, false)
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

    private fun verify(menu: Menu, showIme: Boolean, transientImeInset: Boolean = false) = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val host = ComposeView(controller.get())
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val expanded = mutableStateOf(false)
        val draft = mutableStateOf("Draft")
        var attachmentState: ChatScreenState? = null
        var imeVisible = false
        var frameTime = 0L
        try {
            host.setParentCompositionContext(recomposer)
            controller.get().setContentView(host, ViewGroup.LayoutParams(360, 640))
            controller.windowFocusChanged(true)
            host.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
                    AppTheme {
                        Column {
                            BasicTextField(draft.value, onValueChange = { draft.value = it },
                                modifier = Modifier.testTag("fixture-composer"))
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
                                    ChatComposerBar(state, 600.dp)
                                }
                            }
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
            }
            fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
            fun nodes(view: View): List<SemanticsNode> =
                (if (view is ViewRootForTest) descendants(view.semanticsOwner.unmergedRootSemanticsNode) else emptyList()) +
                    (if (view is ViewGroup) (0 until view.childCount).flatMap { nodes(view.getChildAt(it)) } else emptyList())
            fun field() = nodes(host).single { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "fixture-composer" }
            repeat(8) { frame() }
            assertTrue(field().config[SemanticsActions.RequestFocus].action!!.invoke())
            frame()
            assertTrue(field().config[SemanticsProperties.Focused])
            if (showIme) ViewCompat.dispatchApplyWindowInsets(host, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 300))
                .setVisible(WindowInsetsCompat.Type.ime(), true).build())
            repeat(3) { frame() }
            assertEquals("Fixture IME precondition", showIme, imeVisible)
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
            val trigger = nodes(host).first { node ->
                node.config.contains(SemanticsActions.OnClick) && if (label == null)
                    node.config.contains(SemanticsProperties.Role) &&
                        node.config[SemanticsProperties.Role] == androidx.compose.ui.semantics.Role.Button
                else if (menu in setOf(Menu.PROTOCOL, Menu.SEARCH, Menu.STT_PROVIDER, Menu.STT_MODEL, Menu.STT_LANGUAGE, Menu.VOICE, Menu.LINK)) descendants(node).any {
                    label in it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
                }
                else label in node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
            }
            assertTrue(trigger.config[SemanticsActions.OnClick].action!!.invoke())
            repeat(8) { frame() }
            if (menu != Menu.SEARCH && menu != Menu.LINK)
                assertTrue("$menu menu must open", if (menu == Menu.ATTACH) attachmentState!!.attachMenuOpen else expanded.value)
            val dialog = ShadowDialog.getLatestDialog()
            assertTrue(dialog.isShowing)
            val shadow = org.robolectric.shadow.api.Shadow.extract<RecordingDialogShadow>(dialog)
            assertEquals("$menu must preserve composer IME from the first window show, not repair focus afterward",
                if (showIme) WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE else 0,
                shadow.flagsAtShow!! and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            assertEquals("$menu dialog window focus must match IME state",
                if (showIme) WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE else 0,
                dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
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
            }
        } finally {
            host.disposeComposition(); recomposer.close(); runner.cancel()
            controller.pause().stop().destroy()
        }
    }
}
