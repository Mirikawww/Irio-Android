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
import androidx.compose.ui.unit.Density
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
        val originalSystemHaptics = android.provider.Settings.System.getInt(context.contentResolver, "haptic_feedback_enabled", 1)
        android.provider.Settings.System.putInt(context.contentResolver, "haptic_feedback_enabled", 1)
        val vibrator = context.getSystemService(android.os.VibratorManager::class.java).defaultVibrator
        val vibration = shadowOf(vibrator)
        vibration.setHasVibrator(true)
        preferences.edit().remove("better_settings_display").commit()
        preferences.edit().apply { preferences.all.keys.filter { it.startsWith("haptics_") }.forEach(::remove) }.commit()
        ThemeStore.reload(context)
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(context)
        val laboratoryOpen = mutableStateOf(false)
        val hapticsOpen = mutableStateOf(false)
        var backPressed = false
        var time = 0L
        fun nodes(): List<SemanticsNode> {
            fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
            fun views(node: View): List<View> = listOf(node) + if (node is ViewGroup)
                (0 until node.childCount).flatMap { views(node.getChildAt(it)) } else emptyList()
            return views(view).filterIsInstance<ViewRootForTest>()
                .flatMap { descend(it.semanticsOwner.unmergedRootSemanticsNode) }.distinctBy { it.id }
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
                        if (hapticsOpen.value) HapticSettingsScreen(onBack = { hapticsOpen.value = false })
                        else if (laboratoryOpen.value) LaboratorySettingsScreen(onBack = { backPressed = true })
                        else SettingsScreen(onBack = {})
                    }
                }
            }
            fun frames() = repeat(8) {
                shadowOf(Looper.getMainLooper()).idle()
                Snapshot.sendApplyNotifications()
                advanceTimeBy(16)
                runCurrent()
                time += 16_000_000L
                clock.sendFrame(time)
                runCurrent()
                view.requestLayout()
                view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, 360, 2000)
                fun flush(node: View) {
                    if (node is ViewRootForTest) node.measureAndLayoutForTest()
                    if (node is ViewGroup) (0 until node.childCount).forEach { flush(node.getChildAt(it)) }
                }
                flush(view)
            }
            fun tap(node: SemanticsNode) {
                fun descendants(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::descendants)
                fun roots(v: View): List<ViewRootForTest> =
                    (if (v is ViewRootForTest) listOf(v) else emptyList()) +
                        (if (v is ViewGroup) (0 until v.childCount).flatMap { roots(v.getChildAt(it)) } else emptyList())
                val root = roots(view).single { descendants(it.semanticsOwner.unmergedRootSemanticsNode).any { n -> n.id == node.id } } as View
                val rootLocation = IntArray(2); root.getLocationOnScreen(rootLocation)
                val hostLocation = IntArray(2); view.getLocationOnScreen(hostLocation)
                val point = node.boundsInRoot.center
                val down = android.os.SystemClock.uptimeMillis()
                for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                    val event = android.view.MotionEvent.obtain(down, down + if (action == 0) 0 else 16,
                        action, point.x + rootLocation[0] - hostLocation[0], point.y + rootLocation[1] - hostLocation[1], 0)
                    try { view.dispatchTouchEvent(event) } finally { event.recycle() }
                }
                frames()
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
            val hapticsTitle = nodes().singleOrNull {
                it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { text -> text.text == tr("触感") }
            }
            assertNotNull("Laboratory must expose a separate Haptics category", hapticsTitle)
            assertTrue(requireNotNull(hapticsTitle).config.contains(SemanticsProperties.Heading))
            assertTrue("The laboratory page must keep its settings title", nodes().any {
                it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { text -> text.text == tr("实验室") }
            })
            assertTrue("The laboratory must expose the new setting", nodes().any {
                it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { text -> text.text == tr("更好的设置项展示") }
            })
            fun toggle(labelText: String = "更好的设置项展示"): SemanticsNode {
                nodes().singleOrNull {
                    it.config.contains(SemanticsProperties.ToggleableState) &&
                        it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.contains(tr(labelText))
                }?.let { return it }
                var owner = nodes().single {
                    it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { text -> text.text == tr(labelText) }
                }
                fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
                while (true) {
                    descend(owner).singleOrNull { it.config.contains(SemanticsProperties.ToggleableState) }?.let { return it }
                    owner = requireNotNull(owner.parent)
                }
            }
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
            assertEquals(androidx.compose.ui.state.ToggleableState.Off,
                toggle("触感反馈").config[SemanticsProperties.ToggleableState])
            tap(button("触感反馈"))
            assertEquals("com.irio.HapticSettingsActivity", shadowOf(context).nextStartedActivity.component!!.className)
            assertFalse("Navigation while off must not enable haptics", HapticPreferences.load(context).enabled)
            assertFalse(nodes().any { it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { t -> t.text == tr("触感力度") } })
            tap(toggle("触感反馈"))
            assertNull("The Switch must not also navigate", shadowOf(context).nextStartedActivity)
            repeat(6) { frames() }
            assertTrue(HapticPreferences.load(context).enabled)
            assertFalse("Per-location choices must move off the Laboratory page", nodes().any {
                it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { t -> t.text == tr("发送消息") }
            })
            assertEquals("The master control must remain a switch", androidx.compose.ui.semantics.Role.Switch,
                toggle("触感反馈").config[SemanticsProperties.Role])
            val divider = nodes().single { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "haptics-switch-divider" }
            val master = toggle("触感反馈")
            assertEquals(1f, divider.boundsInRoot.width, 0.5f)
            assertEquals(24f, divider.boundsInRoot.height, 0.5f)
            assertTrue("OneUI divider sits to the left of the Switch", divider.boundsInRoot.right < master.boundsInRoot.left)
            tap(divider)
            assertEquals("com.irio.HapticSettingsActivity", shadowOf(context).nextStartedActivity.component!!.className)
            hapticsOpen.value = true; frames()
            for (removedLabel in listOf("触感力度", "轻", "中", "重", "体验触感")) {
                assertFalse("Removed haptic controls must not remain visible: $removedLabel", nodes().any {
                    it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { t -> t.text == tr(removedLabel) }
                })
            }
            for (event in HapticEvent.entries) {
                val control = toggle(event.label)
                assertEquals("Haptic subitems must be checkboxes", androidx.compose.ui.semantics.Role.Checkbox,
                    control.config[SemanticsProperties.Role])
                val label = nodes().single {
                    it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { t -> t.text == tr(event.label) }
                }
                assertTrue("The checkbox must reserve its leading slot before the label: ${event.label}; label=${label.boundsInRoot}, control=${control.boundsInRoot}",
                    label.boundsInRoot.left >= control.boundsInRoot.left + 10f)
                assertEquals(androidx.compose.ui.state.ToggleableState.On, control.config[SemanticsProperties.ToggleableState])
            }
            val subitemLabels = HapticEvent.entries.map { event -> nodes().single {
                it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { t -> t.text == tr(event.label) }
            } }
            subitemLabels.zipWithNext().forEach { (previous, next) ->
                assertEquals("Haptic subitems must use the same compact 2dp vertical padding as glass options",
                    4f, next.boundsInRoot.top - previous.boundsInRoot.bottom, 0.5f)
            }
            repeat(2) {
                vibrator.cancel()
                assertTrue(toggle("复制消息").config[SemanticsActions.OnClick].action!!.invoke())
                assertTrue("Checkboxes must vibrate for checking and unchecking", vibration.isVibrating)
                frames()
            }
            preferences.edit().putBoolean("haptics_checkbox_toggle", false).commit()
            vibrator.cancel()
            assertTrue(toggle("复制消息").config[SemanticsActions.OnClick].action!!.invoke())
            assertFalse("Disabling checkbox feedback must silence the control, not just its event preview", vibration.isVibrating)
            preferences.edit().putBoolean("haptics_checkbox_toggle", true).commit()
            frames()
            assertFalse(HapticPreferences.load(context).isEnabled(HapticEvent.COPY_MESSAGE))
            repeat(2) {
                vibrator.cancel()
                assertTrue(toggle("复选框关闭").config[SemanticsActions.OnClick].action!!.invoke())
                assertTrue("The checkbox feedback preference must acknowledge its own on/off action", vibration.isVibrating)
                frames()
            }
            assertTrue(HapticPreferences.load(context).isEnabled(HapticEvent.SEND_MESSAGE))

            for ((on, off) in listOf(false to true, true to false)) {
                preferences.edit().putBoolean("haptics_checkbox_toggle_on", on).putBoolean("haptics_checkbox_toggle_off", off).commit()
                vibrator.cancel(); assertTrue(toggle("复制消息").config[SemanticsActions.OnClick].action!!.invoke())
                assertEquals("Checking must use only the checkbox-on choice", on, vibration.isVibrating); frames()
                vibrator.cancel(); assertTrue(toggle("复制消息").config[SemanticsActions.OnClick].action!!.invoke())
                assertEquals("Unchecking must use only the checkbox-off choice", off, vibration.isVibrating); frames()
            }
            preferences.edit().remove("haptics_checkbox_toggle_on").remove("haptics_checkbox_toggle_off").commit()
            var childBack = nodes().single {
                it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.contains(tr("返回"))
            }
            while (!childBack.config.contains(SemanticsActions.OnClick)) childBack = requireNotNull(childBack.parent)
            assertTrue(childBack.config[SemanticsActions.OnClick].action!!.invoke())
            frames()
            assertFalse("The child page must return to Laboratory", hapticsOpen.value)
            vibrator.cancel()
            ThemeStore.saveBetterSettingsDisplay(context, true)
            frames()
            assertFalse("Restoring/programmatically changing a switch must not replay touch feedback", vibration.isVibrating)
            ThemeStore.saveBetterSettingsDisplay(context, false)
            frames()
            repeat(2) {
                vibrator.cancel()
                assertTrue(toggle().config[SemanticsActions.OnClick].action!!.invoke())
                assertTrue("Ordinary switches must vibrate when turning both on and off", vibration.isVibrating)
                frames()
            }
            for ((on, off) in listOf(false to true, true to false)) {
                preferences.edit().putBoolean("haptics_switch_toggle_on", on).putBoolean("haptics_switch_toggle_off", off).commit()
                vibrator.cancel(); assertTrue(toggle().config[SemanticsActions.OnClick].action!!.invoke())
                assertEquals("Enabling must use only the switch-on choice", on, vibration.isVibrating); frames()
                vibrator.cancel(); assertTrue(toggle().config[SemanticsActions.OnClick].action!!.invoke())
                assertEquals("Disabling must use only the switch-off choice", off, vibration.isVibrating); frames()
            }
            preferences.edit().remove("haptics_switch_toggle_on").remove("haptics_switch_toggle_off").commit()
            preferences.edit().putBoolean("haptics_switch_toggle", false).commit()
            vibrator.cancel()
            assertTrue(toggle().config[SemanticsActions.OnClick].action!!.invoke())
            assertFalse("The ordinary-switch feedback choice must apply immediately", vibration.isVibrating)
            frames()
            preferences.edit().putBoolean("haptics_switch_toggle", true).commit()
            vibrator.cancel()
            assertTrue(toggle("触感反馈").config[SemanticsActions.OnClick].action!!.invoke())
            assertTrue("Turning the master off must acknowledge the last switch action before disabling feedback", vibration.isVibrating)
            repeat(6) { frames() }
            assertFalse(nodes().any { it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { t -> t.text == tr("复制消息") } })
            laboratoryOpen.value = false; frames()
            laboratoryOpen.value = true; frames()
            assertTrue(toggle("触感反馈").config[SemanticsActions.OnClick].action!!.invoke())
            repeat(6) { frames() }
            hapticsOpen.value = true; frames()
            assertEquals(androidx.compose.ui.state.ToggleableState.Off, toggle("复制消息").config[SemanticsProperties.ToggleableState])
            HapticPreferences.saveEnabled(context, false)
            hapticsOpen.value = false; frames()
            hapticsOpen.value = true; frames()
            assertEquals("Child choices stay configurable without enabling the master", androidx.compose.ui.state.ToggleableState.Off,
                toggle("复制消息").config[SemanticsProperties.ToggleableState])
            assertFalse(HapticPreferences.load(context).enabled)
            hapticsOpen.value = false; frames()

            var back = nodes().single {
                it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.contains(tr("返回"))
            }
            while (!back.config.contains(SemanticsActions.OnClick)) back = requireNotNull(back.parent)
            assertTrue(back.config[SemanticsActions.OnClick].action!!.invoke())
            assertTrue("The page back button must invoke navigation", backPressed)
        } finally {
            vibrator.cancel()
            android.provider.Settings.System.putInt(context.contentResolver, "haptic_feedback_enabled", originalSystemHaptics)
            view.disposeComposition()
            recomposer.close()
            runner.cancel()
            controller.pause().stop().destroy()
            preferences.edit().apply {
                preferences.all.keys.filter { it.startsWith("haptics_") }.forEach(::remove)
                originalSettings.filterKeys { it.startsWith("haptics_") }.forEach { (key, value) -> when (value) {
                    is Boolean -> putBoolean(key, value)
                    is String -> putString(key, value)
                } }
                val original = originalSettings["better_settings_display"] as? Boolean
                if (original == null) remove("better_settings_display") else putBoolean("better_settings_display", original)
            }.commit()
            ThemeStore.reload(context)
        }
    }
}
