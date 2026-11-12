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
class LaboratorySettingsTest {
    @Test fun homepageOpensLaboratoryFromAppPreferences() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val context = controller.get()
        val preferences = context.getSharedPreferences("appearance_settings", android.content.Context.MODE_PRIVATE)
        val originalSettings = preferences.all
        preferences.edit().remove("better_settings_display").commit()
        ThemeStore.reload(context)
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(context)
        val laboratoryOpen = mutableStateOf(false)
        var backPressed = false
        var time = 0L
        fun nodes(): List<SemanticsNode> {
            fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
            return descend((view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
        }
        fun button(label: String): SemanticsNode {
            var node = nodes().singleOrNull {
                it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { text -> text.text == tr(label) }
            }
            assertNotNull("Settings homepage must expose $label", node)
            while (node != null && !node.config.contains(SemanticsActions.OnClick)) node = node.parent
            return requireNotNull(node)
        }
        try {
            view.setParentCompositionContext(recomposer)
            context.setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme {
                        if (laboratoryOpen.value) LaboratorySettingsScreen(onBack = { backPressed = true })
                        else SettingsScreen(onBack = {})
                    }
                }
            }
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
            frames()
            val laboratory = button("实验室")
            val other = button("其他")
            assertEquals("Laboratory must sit immediately above Other in the same group",
                other.boundsInRoot.top, laboratory.boundsInRoot.bottom, 0.5f)
            assertEquals(other.boundsInRoot.width, laboratory.boundsInRoot.width, 0.5f)
            assertEquals(other.boundsInRoot.height, laboratory.boundsInRoot.height, 0.5f)
            assertTrue(laboratory.config[SemanticsActions.OnClick].action!!.invoke())
            assertEquals("com.irio.LaboratorySettingsActivity",
                shadowOf(context).nextStartedActivity.component!!.className)
            laboratoryOpen.value = true
            frames()
            val appearanceTitle = nodes().singleOrNull {
                it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { text -> text.text == tr("外观") }
            }
            assertNotNull("The switch must belong to an Appearance group", appearanceTitle)
            assertTrue(requireNotNull(appearanceTitle).config.contains(SemanticsProperties.Heading))
            assertTrue("The laboratory page must keep its settings title", nodes().any {
                it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { text -> text.text == tr("实验室") }
            })
            assertTrue("The laboratory must expose the new setting", nodes().any {
                it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { text -> text.text == tr("更好的设置项展示") }
            })
            fun toggle() = nodes().single { it.config.contains(SemanticsProperties.ToggleableState) }
            assertEquals(androidx.compose.ui.state.ToggleableState.Off,
                toggle().config[SemanticsProperties.ToggleableState])
            assertTrue(toggle().config[SemanticsActions.OnClick].action!!.invoke())
            frames()
            assertTrue(preferences.getBoolean("better_settings_display", false))
            laboratoryOpen.value = false; frames()
            ThemeStore.reload(context)
            laboratoryOpen.value = true; frames()
            assertEquals("Reopening must retain the saved switch", androidx.compose.ui.state.ToggleableState.On,
                toggle().config[SemanticsProperties.ToggleableState])
            assertTrue(toggle().config[SemanticsActions.OnClick].action!!.invoke())
            frames()
            assertFalse(preferences.getBoolean("better_settings_display", true))
            var back = nodes().single {
                it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.contains(tr("返回"))
            }
            while (!back.config.contains(SemanticsActions.OnClick)) back = requireNotNull(back.parent)
            assertTrue(back.config[SemanticsActions.OnClick].action!!.invoke())
            assertTrue("The page back button must invoke navigation", backPressed)
        } finally {
            view.disposeComposition()
            recomposer.close()
            runner.cancel()
            controller.pause().stop().destroy()
            preferences.edit().apply {
                val original = originalSettings["better_settings_display"] as? Boolean
                if (original == null) remove("better_settings_display") else putBoolean("better_settings_display", original)
            }.commit()
            ThemeStore.reload(context)
        }
    }
}
