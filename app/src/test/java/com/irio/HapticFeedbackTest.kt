package com.irio

import android.content.Context
import android.os.VibrationAttributes
import android.os.VibratorManager
import android.provider.Settings
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class HapticFeedbackTest {
    @Test fun toggleDirectionsAreSeparateSearchableChoicesWithLegacyDefaults() {
        val groups = listOf("switch_toggle" to "普通开关", "checkbox_toggle" to "复选框",
            "fast_mode" to "快速开关", "web_search" to "网络搜索开关", "auto_read" to "自动朗读开关")
        for ((legacy, label) in groups) {
            val on = HapticEvent.entries.singleOrNull { it.stored == "${legacy}_on" }
            val off = HapticEvent.entries.singleOrNull { it.stored == "${legacy}_off" }
            assertNotNull("$label needs an independent enable choice", on)
            assertNotNull("$label needs an independent disable choice", off)
            val enabled = requireNotNull(on); val disabled = requireNotNull(off)
            preferences.edit().putBoolean("haptics_$legacy", false).commit()
            assertFalse(HapticPreferences.load(context).isEnabled(enabled))
            assertFalse(HapticPreferences.load(context).isEnabled(disabled))
            HapticPreferences.saveEvent(context, enabled, true)
            assertTrue(HapticPreferences.load(context).isEnabled(enabled))
            assertFalse(HapticPreferences.load(context).isEnabled(disabled))
            HapticPreferences.saveEnabled(context, true)
            AppHaptics.perform(context, enabled); assertTrue(shadow.isVibrating); vibrator.cancel()
            AppHaptics.perform(context, disabled); assertFalse(shadow.isVibrating)
            assertEquals(label + "开启", enabled.label)
            assertEquals(label + "关闭", disabled.label)
        }
        for (stored in listOf("reply_completed", "model_reaction", "quick_long_press", "quick_long_release")) {
            assertTrue("The new location must be independently configurable: $stored", HapticEvent.entries.any { it.stored == stored })
        }
    }

    @Test fun obsoleteStrengthChoicesCannotReduceTheFixedHeavyPulse() {
        HapticPreferences.saveEnabled(context, true)
        for (oldValue in listOf("light", "medium", "heavy", "unknown")) {
            preferences.edit().putString("haptics_strength", oldValue).commit()
            AppHaptics.perform(context, HapticEvent.SEND_MESSAGE)
            assertEquals("All feedback must now use the previous Heavy pulse", 34L, shadow.milliseconds)
            vibrator.cancel()
        }
    }
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val preferences get() = context.getSharedPreferences("appearance_settings", Context.MODE_PRIVATE)
    private val vibrator get() = context.getSystemService(VibratorManager::class.java).defaultVibrator
    private val shadow get() = shadowOf(vibrator)
    private var original: Map<String, *> = emptyMap<String, Any>()
    private var originalSystemSetting = 1

    @Before fun prepare() {
        original = preferences.all.filterKeys { it.startsWith("haptics_") }
        originalSystemSetting = Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1)
        preferences.edit().apply { preferences.all.keys.filter { it.startsWith("haptics_") }.forEach(::remove) }.commit()
        Settings.System.putInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1)
        shadow.setHasVibrator(true)
        shadow.setHasAmplitudeControl(true)
        vibrator.cancel()
    }

    @After fun restore() {
        preferences.edit().apply {
            preferences.all.keys.filter { it.startsWith("haptics_") }.forEach(::remove)
            original.forEach { (key, value) -> when (value) {
                is Boolean -> putBoolean(key, value)
                is String -> putString(key, value)
            } }
        }.commit()
        Settings.System.putInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, originalSystemSetting)
        vibrator.cancel()
    }

    @Test fun experimentIsOptInAndAllLocationsHaveIndependentSavedChoices() {
        assertFalse(HapticPreferences.load(context).enabled)
        for (event in HapticEvent.entries) {
            assertTrue(HapticPreferences.load(context).isEnabled(event))
            AppHaptics.perform(context, event)
            assertFalse(shadow.isVibrating)
        }
        HapticPreferences.saveEnabled(context, true)
        for (event in HapticEvent.entries) {
            HapticPreferences.saveEvent(context, event, false)
            assertFalse(HapticPreferences.load(context).isEnabled(event))
            assertTrue(HapticPreferences.load(context).enabled)
            AppHaptics.perform(context, event)
            assertFalse(shadow.isVibrating)
            HapticPreferences.saveEvent(context, event, true)
            AppHaptics.perform(context, event)
            assertTrue("Enabled event must reach the actual vibrator: $event", shadow.isVibrating)
            assertEquals(34L, shadow.milliseconds)
            vibrator.cancel()
        }
        HapticPreferences.saveEvent(context, HapticEvent.COPY_MESSAGE, false)
        HapticPreferences.saveEnabled(context, false)
        HapticPreferences.saveEnabled(context, true)
        assertFalse(HapticPreferences.load(context).isEnabled(HapticEvent.COPY_MESSAGE))
        assertTrue(HapticPreferences.load(context).isEnabled(HapticEvent.SEND_MESSAGE))
    }

    @Test fun fixedHeavyPulseRespectsSystemAndHardwareGates() {
        HapticPreferences.saveEnabled(context, true)
        for (amplitudeControl in listOf(true, false)) {
            shadow.setHasAmplitudeControl(amplitudeControl)
            AppHaptics.perform(context, HapticEvent.SEND_MESSAGE)
            assertTrue(shadow.isVibrating)
            assertEquals(34L, shadow.milliseconds)
            val attributes = shadow.vibrationAttributesFromLastVibration as VibrationAttributes
            assertEquals(VibrationAttributes.USAGE_TOUCH, attributes.usage)
            assertEquals(0, attributes.flags)
            vibrator.cancel()
        }
        Settings.System.putInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 0)
        AppHaptics.perform(context, HapticEvent.SEND_MESSAGE)
        assertFalse(shadow.isVibrating)
        Settings.System.putInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1)
        shadow.setHasVibrator(false)
        AppHaptics.perform(context, HapticEvent.SEND_MESSAGE)
        assertFalse(shadow.isVibrating)
    }

    @Test fun everyRemainingControlIsSearchableInBothLanguages() {
        assertTrue(searchSettings("触感力度", label = { it }).isEmpty())
        val controls = listOf("laboratory.haptics" to "触感反馈") +
            HapticEvent.entries.map { it.searchId to it.label }
        for (language in AppLanguage.entries) {
            for ((id, title) in controls) {
                val translated = translate(language, title)
                if (language == AppLanguage.EN) assertNotEquals(title, translated)
                val hit = searchSettings(translated, label = { translate(language, it) }).single { it.id == id }
                assertEquals(if (id == "laboratory.haptics") LaboratorySettingsActivity::class.java
                    else HapticSettingsActivity::class.java, hit.destination)
                assertEquals(id, hit.anchor)
            }
        }
        val activity = Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java).setup()
        try { for ((id, title) in controls.drop(1)) {
            val hit = searchSettings(title, label = { it }).single { it.id == id }
            activity.get().openSettingsSearch(hit)
            val intent = shadowOf(activity.get()).nextStartedActivity
            assertEquals(HapticSettingsActivity::class.java.name, intent.component!!.className)
            assertEquals(id, intent.getStringExtra(SETTINGS_SEARCH_TARGET))
            assertFalse(HapticPreferences.load(context).enabled)
        } } finally { activity.pause().stop().destroy() }
    }
}
