package com.irio

import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class PersonalizationSettingsTest {
    @Test fun promptEditorLoadsExistingContentAndPersistsEditsWithoutAnotherNavigationStep() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val context = controller.get()
        val original = SystemPromptStore.load(context)
        SystemPromptStore.save(context, "Existing global system prompt")
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(context)
        val showing = mutableStateOf(true)
        var time = 0L
        fun frames() = repeat(8) {
            shadowOf(Looper.getMainLooper()).idle()
            Snapshot.sendApplyNotifications()
            runCurrent()
            time += 16_000_000L
            clock.sendFrame(time)
            runCurrent()
            view.requestLayout()
            view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 360, 1000)
        }
        fun nodes(): List<SemanticsNode> {
            fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
            return descend((view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
        }
        fun field() = nodes().single { it.config.contains(SemanticsProperties.EditableText) }
        fun setText(text: String) {
            assertTrue(field().config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(text)))
            frames()
        }
        try {
            view.setParentCompositionContext(recomposer)
            context.setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme { if (showing.value) PersonalizationSettingsScreen(onBack = {}) }
                }
            }
            frames()
            assertEquals("The existing prompt must be directly editable on opening the page",
                "Existing global system prompt", field().config[SemanticsProperties.EditableText].text)
            val promptHeading = nodes().single { node ->
                node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == tr("系统提示词") }
            }
            assertTrue("The prompt section must use the shared accessible heading", promptHeading.config.contains(SemanticsProperties.Heading))
            val headingLayouts = mutableListOf<TextLayoutResult>()
            promptHeading.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(headingLayouts)
            assertEquals(13f, headingLayouts.single().layoutInput.style.fontSize.value, 0f)
            assertEquals(18f, headingLayouts.single().layoutInput.style.lineHeight.value, 0f)
            assertEquals(FontWeight.Medium, headingLayouts.single().layoutInput.style.fontWeight)
            assertEquals(field().boundsInRoot.left + 16f, promptHeading.boundsInRoot.left, 0.5f)
            assertTrue("The new page heading must be visible", nodes().any { node ->
                node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == tr("个性化") }
            })
            setText("Updated prompt")
            controller.pause()
            assertEquals("Backgrounding must save the current edit without disposing the page",
                "Updated prompt", SystemPromptStore.load(context))
            controller.resume()
            val limit = "x".repeat(SystemPromptStore.MAX_CHARS)
            setText(limit)
            setText(limit + "x")
            assertEquals("The original prompt length limit must still apply",
                limit, field().config[SemanticsProperties.EditableText].text)
            showing.value = false
            frames()
            assertEquals("Leaving the page must save the latest edit", limit, SystemPromptStore.load(context))
            showing.value = true
            frames()
            assertEquals("Reopening must load the saved prompt", limit,
                field().config[SemanticsProperties.EditableText].text)
            setText("")
            showing.value = false
            frames()
            assertEquals("Clearing the prompt must persist as an empty value", "", SystemPromptStore.load(context))
        } finally {
            view.disposeComposition()
            recomposer.close()
            runner.cancel()
            controller.pause().stop().destroy()
            SystemPromptStore.save(context, original)
        }
    }

    @Test fun homepageOpensPersonalizationAndModelsNoLongerHasThePromptEntry() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val context = controller.get()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(context)
        val models = mutableStateOf(false)
        var time = 0L
        fun frames() = repeat(8) {
            shadowOf(Looper.getMainLooper()).idle()
            Snapshot.sendApplyNotifications()
            runCurrent()
            time += 16_000_000L
            clock.sendFrame(time)
            runCurrent()
            view.requestLayout()
            view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 360, 2000)
        }
        fun nodes(): List<SemanticsNode> {
            fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
            return descend((view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
        }
        fun hasText(node: SemanticsNode, text: String) =
            node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == tr(text) }
        try {
            view.setParentCompositionContext(recomposer)
            context.setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme {
                        if (models.value) ModelSettingsScreen(onBack = {}) else SettingsScreen(onBack = {})
                    }
                }
            }
            frames()
            val entry = nodes().singleOrNull { hasText(it, "个性化") }
            assertNotNull("Settings homepage must expose Personalization", entry)
            var button = entry
            while (button != null && !button.config.contains(SemanticsActions.OnClick)) button = button.parent
            assertNotNull("Personalization must be a real navigation button", button)
            val modelTitle = nodes().single { hasText(it, "模型") }
            var modelButton: SemanticsNode? = modelTitle
            while (modelButton != null && !modelButton.config.contains(SemanticsActions.OnClick)) {
                modelButton = modelButton.parent
            }
            assertEquals("Personalization must sit directly above Models in the same group",
                button!!.boundsInRoot.bottom, modelButton!!.boundsInRoot.top, 0.5f)
            assertTrue(button.config[SemanticsActions.OnClick].action!!.invoke())
            assertEquals("com.irio.PersonalizationSettingsActivity",
                shadowOf(context).nextStartedActivity.component!!.className)
            models.value = true
            frames()
            assertFalse("The old prompt navigation row must leave Models",
                nodes().any { hasText(it, "编辑系统提示词") })
            assertTrue("Unrelated model settings must remain",
                nodes().any { hasText(it, "消息时间戳") })
        } finally {
            view.disposeComposition()
            recomposer.close()
            runner.cancel()
            controller.pause().stop().destroy()
        }
    }
}
