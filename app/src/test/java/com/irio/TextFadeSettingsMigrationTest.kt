package com.irio

import android.os.Looper
import android.view.View
import android.view.ViewGroup
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
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class TextFadeSettingsMigrationTest {
    @Test fun laboratoryOwnsTheExistingPreferenceAndAppearanceNoLongerExposesIt() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        ThemeStore.ensureLoaded(activity)
        val preferences = activity.getSharedPreferences("appearance_settings", android.content.Context.MODE_PRIVATE)
        val originalGlyph = preferences.all["glyph_fade"] as? Boolean
        val originalLanguage = ThemeStore.language
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(activity)
        val laboratory = mutableStateOf(true)
        var time = 0L
        fun frames() = repeat(12) {
            shadowOf(Looper.getMainLooper()).idle()
            Snapshot.sendApplyNotifications(); testScheduler.runCurrent()
            time += 16_000_000L
            clock.sendFrame(time); testScheduler.runCurrent()
            view.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1500, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 400, 1500)
            fun flush(v: View) {
                if (v is ViewRootForTest) v.measureAndLayoutForTest()
                if (v is ViewGroup) (0 until v.childCount).forEach { flush(v.getChildAt(it)) }
            }
            flush(view)
        }
        fun nodes(): List<SemanticsNode> {
            fun descend(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::descend)
            fun roots(v: View): List<ViewRootForTest> = (if (v is ViewRootForTest) listOf(v) else emptyList()) +
                (if (v is ViewGroup) (0 until v.childCount).flatMap { roots(v.getChildAt(it)) } else emptyList())
            return roots(view).flatMap { descend(it.semanticsOwner.unmergedRootSemanticsNode) }.distinctBy { it.id }
        }
        fun hasText(node: SemanticsNode, value: String) =
            node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == value }
        fun toggle(): SemanticsNode {
            nodes().singleOrNull {
                it.config.contains(SemanticsProperties.ToggleableState) &&
                    it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.contains(tr("文本淡入"))
            }?.let { return it }
            var owner = nodes().single { hasText(it, tr("文本淡入")) }
            fun descend(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::descend)
            while (true) {
                descend(owner).singleOrNull { it.config.contains(SemanticsProperties.ToggleableState) }?.let { return it }
                owner = requireNotNull(owner.parent)
            }
        }
        try {
            ThemeStore.saveGlyphFade(activity, true)
            view.setParentCompositionContext(recomposer)
            activity.setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme {
                        if (laboratory.value) LaboratorySettingsScreen(onBack = {})
                        else AppearanceSettingsScreen(onBack = {})
                    }
                }
            }
            for (language in AppLanguage.entries) {
                ThemeStore.saveLanguage(activity, language)
                laboratory.value = true; frames()
                assertTrue("Laboratory must contain the moved switch", nodes().any { hasText(it, tr("文本淡入")) })
                assertEquals(ToggleableState.On, toggle().config[SemanticsProperties.ToggleableState])
                assertFalse("Experimental badges must not remain in either language", nodes().any {
                    hasText(it, "实验性") || hasText(it, "Experimental")
                })
                assertTrue(toggle().config[SemanticsActions.OnClick].action!!.invoke()); frames()
                assertFalse(ThemeStore.glyphFade)
                assertFalse(preferences.getBoolean("glyph_fade", true))
                laboratory.value = false; frames()
                assertFalse("Appearance must not retain a duplicate entry", nodes().any { hasText(it, tr("文本淡入")) })
                assertTrue(nodes().any { hasText(it, tr("图片生成流式传输")) })
                ThemeStore.reload(activity)
                laboratory.value = true; frames()
                assertEquals(ToggleableState.Off, toggle().config[SemanticsProperties.ToggleableState])
                assertTrue(toggle().config[SemanticsActions.OnClick].action!!.invoke()); frames()
                assertTrue(ThemeStore.glyphFade)
                assertTrue(preferences.getBoolean("glyph_fade", false))
                val hit = searchSettings(tr("文本淡入")).single()
                activity.openSettingsSearch(hit)
                val intent = shadowOf(activity).nextStartedActivity
                assertEquals(LaboratorySettingsActivity::class.java.name, intent.component!!.className)
                assertEquals("laboratory.glyph", intent.getStringExtra(SETTINGS_SEARCH_TARGET))
            }
        } finally {
            view.disposeComposition(); recomposer.cancel(); runner.cancel()
            controller.pause().stop().destroy()
            preferences.edit().apply {
                if (originalGlyph == null) remove("glyph_fade") else putBoolean("glyph_fade", originalGlyph)
            }.commit()
            ThemeStore.saveLanguage(activity, originalLanguage)
            ThemeStore.reload(activity)
        }
    }
}
