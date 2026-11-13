package com.irio

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.composables.ui.components.Text
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModelSwitcherQuickActionsTest {
    @Test fun iconActionsPreserveThinkingReadoutAccessibilityAndToggleStates() = runTest {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val context = activity.get()
        ThemeStore.ensureLoaded(context)
        val oldLanguage = ThemeStore.language
        val prefs = context.getSharedPreferences("appearance_settings", android.content.Context.MODE_PRIVATE)
        val originalHaptics = prefs.all.filterKeys { it.startsWith("haptics_") }
        val originalSystemHaptics = android.provider.Settings.System.getInt(context.contentResolver, "haptic_feedback_enabled", 1)
        android.provider.Settings.System.putInt(context.contentResolver, "haptic_feedback_enabled", 1)
        HapticPreferences.saveEnabled(context, true)
        listOf("fast_mode", "web_search", "auto_read").forEach { prefs.edit().putBoolean("haptics_$it", true).commit() }
        val vibrator = context.getSystemService(android.os.VibratorManager::class.java).defaultVibrator
        val vibration = org.robolectric.Shadows.shadowOf(vibrator)
        vibration.setHasVibrator(true)
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(context)
        val fast = mutableStateOf(false)
        val effort = mutableStateOf(ReasoningEffort.OFF)
        val preview = mutableStateOf(ThinkingRollState(0))
        val search = mutableStateOf(false)
        val read = mutableStateOf(false)
        val available = mutableStateOf(true)
        val active = mutableStateOf(true)
        val fontScale = mutableStateOf(1f)
        var time = 0L
        fun frames(count: Int = 8) = repeat(count) {
            Snapshot.sendApplyNotifications(); runCurrent()
            advanceTimeBy(16); runCurrent(); Snapshot.sendApplyNotifications()
            time += 16_000_000L; clock.sendFrame(time); runCurrent()
            view.requestLayout()
            view.measure(View.MeasureSpec.makeMeasureSpec(248, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 248, 600)
            val bitmap = android.graphics.Bitmap.createBitmap(248, 600, android.graphics.Bitmap.Config.ARGB_8888)
            try { view.draw(android.graphics.Canvas(bitmap)) } finally { bitmap.recycle() }
        }
        fun nodes(): List<SemanticsNode> {
            fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
            return descend((view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
        }
        fun label(value: String) = nodes().single {
            it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { text -> text.text == tr(value) }
        }
        fun button(value: String): SemanticsNode {
            var node = label(value)
            while (!node.config.contains(SemanticsActions.OnClick)) node = node.parent!!
            return node
        }
        try {
            view.setParentCompositionContext(recomposer)
            context.setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale.value)) {
                    AppTheme {
                        Column {
                            Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text("Model", modifier = Modifier.weight(1f))
                                ModelSwitcherThinkingReadout(
                                    listOf(ReasoningEffort.OFF, ReasoningEffort.HIGH)[preview.value.index],
                                    enabled = true,
                                )
                            }
                            ModelSwitcherParameterControls(
                                imageModel = false, imageOptions = ImageGenerationOptions(), compactInteractive = active.value,
                                supportsOpenAiOptions = true, reasoningEffort = effort.value,
                                supportsThinking = true, reasoningLevels = listOf(ReasoningEffort.OFF, ReasoningEffort.HIGH),
                                webSearchAvailable = available.value, webSearchEnabled = search.value, autoRead = read.value,
                                fastMode = fast.value, onFastModeChange = { fast.value = it },
                                onReasoningChange = { effort.value = it }, onThinkingPreviewChange = { preview.value = it },
                                onWebSearchChange = { search.value = it },
                                onAutoReadChange = { read.value = it }, onImageOptionsChange = {}, onAspectRatio = {},
                            )
                        }
                    }
                }
            }
            for (language in listOf(AppLanguage.ZH_CN, AppLanguage.EN)) {
                ThemeStore.saveLanguage(context, language)
                for (scale in listOf(1f, 2f)) {
                    fontScale.value = scale; frames()
                    val first = button("快速").boundsInRoot
                    val middle = button("网络搜索").boundsInRoot
                    val last = button("自动朗读").boundsInRoot
                    assertEquals("Three capsules must share one row", first.top, middle.top, 0.1f)
                    assertEquals(first.top, last.top, 0.1f)
                    assertEquals("Tag group must be leading aligned", 6f, first.left, 0.1f)
                    assertEquals(first.height, last.height, 0.1f)
                    if (scale == 1f) assertTrue("Default quick actions must stay compact", first.height in 40f..72f)
                    assertTrue(first.right < middle.left && middle.right < last.left)
                    assertEquals(4f, middle.left - first.right, 0.1f)
                    assertEquals(4f, last.left - middle.right, 0.1f)
                    val thinking = nodes().filter {
                        tr("思考强度") in it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
                    }.maxBy { it.boundsInRoot.top }
                    val readout = nodes().filter {
                        tr("思考强度") in it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
                    }.minBy { it.boundsInRoot.top }
                    assertTrue("Thinking depth replaces the header fast-mode button", readout.boundsInRoot.bottom <= thinking.boundsInRoot.top)
                    assertTrue(readout.boundsInRoot.left > 0f)
                    assertEquals("Text-model actions use the same zero outer gap as generation parameters", 0f,
                        first.top - thinking.boundsInRoot.bottom, 0.1f)
                    for ((name, tag) in listOf("快速" to "model-fast-mode-icon", "网络搜索" to "model-web-search-icon", "自动朗读" to "model-auto-read-icon")) {
                        vibrator.cancel()
                        assertTrue(button(name).config[SemanticsActions.OnClick].action!!.invoke())
                        assertTrue("Each accepted quick-action toggle must vibrate in both directions: $name", vibration.isVibrating)
                        frames(25)
                        val icon = nodes().single { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == tag }
                        assertEquals(18f, icon.boundsInRoot.width, 0.1f)
                        assertTrue("Tag icon must precede its label", icon.boundsInRoot.right < label(name).boundsInRoot.left)
                        assertEquals(icon.boundsInRoot.center.y, label(name).boundsInRoot.center.y, 1f)
                        val layouts = mutableListOf<TextLayoutResult>()
                        label(name).config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
                        val layout = layouts.single()
                        assertEquals(14f, layout.layoutInput.style.fontSize.value, 0.1f)
                        assertFalse("$language/$scale/$name must not overflow: size=${layout.size}, paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}, width=${layout.didOverflowWidth}, height=${layout.didOverflowHeight}, lines=${layout.lineCount}, exceeded=${layout.multiParagraph.didExceedMaxLines}", layout.hasVisualOverflow)
                    }
                    frames(110)
                }
            }
            fontScale.value = 1f
            ThemeStore.saveLanguage(context, AppLanguage.ZH_CN)
            frames()
            val slider = nodes().filter {
                tr("思考强度") in it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
            }.maxBy { it.boundsInRoot.top }
            assertTrue(slider.config[SemanticsActions.SetProgress].action!!.invoke(1f))
            frames()
            assertEquals(ReasoningEffort.HIGH, effort.value)
            assertEquals(1, preview.value.index)
            assertTrue(nodes().any { tr(ReasoningEffort.HIGH.label) in it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() } })
            assertTrue(button("快速").config[SemanticsActions.OnClick].action!!.invoke())
            frames()
            assertTrue(fast.value)
            assertEquals(ToggleableState.On, button("快速").config[SemanticsProperties.ToggleableState])
            assertTrue(button("网络搜索").config[SemanticsActions.OnClick].action!!.invoke())
            frames()
            assertTrue(button("自动朗读").config[SemanticsActions.OnClick].action!!.invoke())
            frames()
            assertTrue(fast.value); assertTrue(search.value); assertTrue(read.value)
            assertEquals(ToggleableState.On, button("网络搜索").config[SemanticsProperties.ToggleableState])
            assertEquals(ToggleableState.On, button("自动朗读").config[SemanticsProperties.ToggleableState])
            for ((name, events) in listOf("快速" to listOf(HapticEvent.FAST_MODE_ON, HapticEvent.FAST_MODE_OFF),
                "网络搜索" to listOf(HapticEvent.WEB_SEARCH_ON, HapticEvent.WEB_SEARCH_OFF),
                "自动朗读" to listOf(HapticEvent.AUTO_READ_ON, HapticEvent.AUTO_READ_OFF))) {
                events.forEach { HapticPreferences.saveEvent(context, it, false) }
                repeat(2) {
                    vibrator.cancel()
                    assertTrue(button(name).config[SemanticsActions.OnClick].action!!.invoke())
                    assertFalse("A disabled feedback location must still toggle without vibration: $name", vibration.isVibrating)
                    frames()
                }
                events.forEach { HapticPreferences.saveEvent(context, it, true) }
                for ((on, off) in listOf(false to true, true to false)) {
                    HapticPreferences.saveEvent(context, events[0], on)
                    HapticPreferences.saveEvent(context, events[1], off)
                    vibrator.cancel(); assertTrue(button(name).config[SemanticsActions.OnClick].action!!.invoke())
                    assertEquals("$name off must use only its own direction", off, vibration.isVibrating); frames()
                    vibrator.cancel(); assertTrue(button(name).config[SemanticsActions.OnClick].action!!.invoke())
                    assertEquals("$name on must use only its own direction", on, vibration.isVibrating); frames()
                }
                events.forEach { HapticPreferences.saveEvent(context, it, true) }
            }
            available.value = false; frames()
            assertTrue(button("网络搜索").config.contains(SemanticsProperties.Disabled))
            assertEquals(ToggleableState.Off, button("网络搜索").config[SemanticsProperties.ToggleableState])
            vibrator.cancel()
            button("网络搜索").config[SemanticsActions.OnClick].action!!.invoke()
            assertFalse("Unavailable web search must not emit toggle feedback", vibration.isVibrating)
            active.value = false; frames()
            vibrator.cancel()
            button("快速").config[SemanticsActions.OnClick].action!!.invoke()
            button("自动朗读").config[SemanticsActions.OnClick].action!!.invoke()
            assertFalse("Inactive controls must not vibrate", vibration.isVibrating)
            assertTrue("Transitioning controls must not change saved state", fast.value && read.value)
        } finally {
            view.disposeComposition(); recomposer.close(); runner.cancel()
            ThemeStore.saveLanguage(context, oldLanguage)
            vibrator.cancel()
            android.provider.Settings.System.putInt(context.contentResolver, "haptic_feedback_enabled", originalSystemHaptics)
            prefs.edit().apply {
                prefs.all.keys.filter { it.startsWith("haptics_") }.forEach(::remove)
                originalHaptics.forEach { (key, value) -> when (value) {
                    is Boolean -> putBoolean(key, value)
                    is String -> putString(key, value)
                } }
            }.commit()
            activity.pause().stop().destroy()
        }
    }
}
