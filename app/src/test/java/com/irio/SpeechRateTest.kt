package com.irio

import android.content.Context
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, shadows = [SettingsImeFocusTest.NoCredentialWrites::class])
class SpeechRateTest {
    @Test fun rateDefaultsToNormalAndPersistsEverySupportedTenthWithoutChangingOtherPreferences() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("speech_settings", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        assertEquals(1f, SpeechStore.loadSpeechRate(context), 0f)
        SpeechStore.saveVoiceId(context, "saved voice")
        SpeechStore.saveAutoRead(context, true)
        for (tenths in 5..30) {
            val rate = tenths / 10f
            SpeechStore.saveSpeechRate(context, rate)
            assertEquals(rate, SpeechStore.loadSpeechRate(context), 0f)
            assertEquals(rate, prefs.getFloat("speech_rate", 0f), 0f)
        }
        assertEquals("saved voice", SpeechStore.loadVoiceId(context))
        assertTrue(SpeechStore.loadAutoRead(context))
    }

    @Test fun rateIsClampedSnappedAndSafeForInvalidStoredValues() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("speech_settings", Context.MODE_PRIVATE)
        listOf(-1f to 0.5f, 0f to 0.5f, 5f to 3f, 1.24f to 1.2f,
            Float.NaN to 1f, Float.POSITIVE_INFINITY to 1f, Float.NEGATIVE_INFINITY to 1f).forEach { (input, expected) ->
            SpeechStore.saveSpeechRate(context, input)
            assertEquals(expected, SpeechStore.loadSpeechRate(context), 0f)
            prefs.edit().putFloat("speech_rate", input).apply()
            assertEquals(expected, SpeechStore.loadSpeechRate(context), 0f)
        }
    }

    @Test fun changingSpeedInvalidatesTheSynthesizedAudioCache() {
        val original = speechCacheKey(42L, "voice", 1f, "same message")
        assertEquals(original, speechCacheKey(42L, "voice", 1f, "same message"))
        listOf(0.5f, 1.5f, 3f).forEach { rate ->
            assertNotEquals(original, speechCacheKey(42L, "voice", rate, "same message"))
        }
        assertNotEquals(original, speechCacheKey(42L, "other voice", 1f, "same message"))
        assertNotEquals(original, speechCacheKey(42L, "voice", 1f, "edited message"))
    }

    @Test fun savedRateIsAppliedToTheTtsEngineWithoutChangingTheSelectedVoice() {
        val context = RuntimeEnvironment.getApplication()
        val engine = RecordingTts(context)
        val field = SpeechPlayer::class.java.getDeclaredField("tts").apply { isAccessible = true }
        val previous = field.get(SpeechPlayer)
        val apply = SpeechPlayer::class.java.getDeclaredMethod("applyVoice", String::class.java, Float::class.javaPrimitiveType)
            .apply { isAccessible = true }
        try {
            field.set(SpeechPlayer, engine)
            listOf(0.5f, 1f, 3f).forEach { rate ->
                SpeechStore.saveSpeechRate(context, rate)
                apply.invoke(SpeechPlayer, "fixture voice", SpeechStore.loadSpeechRate(context))
                assertEquals(rate, engine.appliedRate, 0f)
                assertEquals("fixture voice", engine.selectedVoice?.name)
            }
        } finally {
            field.set(SpeechPlayer, previous)
            engine.shutdown()
        }
    }

    @Test fun readingSpeedLabelIsTranslated() {
        assertEquals("Reading speed", translate(AppLanguage.EN, "朗读速度"))
    }

    @Test fun speechScreenExposesAnIndependentPersistentSpeedSlider() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val context = controller.get()
        val prefs = context.getSharedPreferences("speech_settings", Context.MODE_PRIVATE)
        prefs.edit().remove("speech_rate").apply()
        ThemeStore.ensureLoaded(context)
        val previousLanguage = ThemeStore.language
        ThemeStore.saveLanguage(context, AppLanguage.ZH_CN)
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val host = ComposeView(context)
        var frame = 0L
        try {
            host.setParentCompositionContext(recomposer)
            context.setContentView(host)
            host.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme { SpeechSettingsScreen(onBack = {}) }
                }
            }
            fun settle() = repeat(8) {
                Snapshot.sendApplyNotifications()
                runCurrent()
                clock.sendFrame(++frame * 16_000_000L)
                runCurrent()
                shadowOf(Looper.getMainLooper()).idle()
                host.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
                host.layout(0, 0, 360, 800)
            }
            fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
            fun nodes() = descend((host.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
            settle()
            val text = nodes().flatMap { it.config.getOrElse(SemanticsProperties.Text) { emptyList() } }.map { it.text }
            assertTrue("TTS/STT must expose the reading speed setting", "朗读速度" in text)
            assertTrue("Default speed must be displayed as 1.0x", "1.0×" in text)
            val slider = nodes().single { it.config.contains(SemanticsActions.SetProgress) }
            val range = slider.config[SemanticsProperties.ProgressBarRangeInfo]
            assertEquals(5f..30f, range.range)
            assertEquals(10f, range.current, 0f)
            assertEquals(24, range.steps)
            assertTrue(slider.config[SemanticsActions.SetProgress].action!!.invoke(25f))
            settle()
            assertEquals(2.5f, prefs.getFloat("speech_rate", 0f), 0f)
            assertTrue(nodes().any { node -> node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == "2.5×" } })
        } finally {
            host.disposeComposition()
            recomposer.close()
            runner.cancel()
            ThemeStore.saveLanguage(context, previousLanguage)
            controller.pause().stop().destroy()
        }
    }

    private class RecordingTts(context: Context) : TextToSpeech(context, {}) {
        private val fixtureVoice = Voice("fixture voice", Locale.US, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, emptySet())
        var appliedRate = 0f
        var selectedVoice: Voice? = null
        override fun getVoices(): Set<Voice> = setOf(fixtureVoice)
        override fun getDefaultVoice(): Voice = fixtureVoice
        override fun setVoice(voice: Voice): Int { selectedVoice = voice; return SUCCESS }
        override fun setSpeechRate(speechRate: Float): Int { appliedRate = speechRate; return SUCCESS }
    }
}
