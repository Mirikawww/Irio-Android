package com.irio

import android.content.res.Configuration
import android.provider.Settings
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AppFontSystemWeightTest {
    @Test fun honorWeightScaleAffectsTheActualLoraVariationAxis() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val context = controller.get()
        ThemeStore.ensureLoaded(context)
        val originalFont = ThemeStore.appFont
        val originalScale = Settings.Secure.getString(context.contentResolver, "font_weight_scale")
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(context)
        var family: FontFamily? = null
        var frameTime = 0L
        fun frames() {
            repeat(8) {
                shadowOf(android.os.Looper.getMainLooper()).idle()
                Snapshot.sendApplyNotifications()
                runCurrent()
                frameTime += 16_000_000L
                clock.sendFrame(frameTime)
                runCurrent()
                view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, 360, 300)
            }
        }
        fun axis(weight: FontWeight = FontWeight.Normal, style: FontStyle = FontStyle.Normal): Float {
            val font = (family as FontListFontFamily).single { it.weight == weight && it.style == style } as AndroidFont
            return font.variationSettings.settings.single { it.axisName == "wght" }.toVariationValue(null)
        }
        fun scale(value: String?) {
            Settings.Secure.putString(context.contentResolver, "font_weight_scale", value)
            context.contentResolver.notifyChange(Settings.Secure.getUriFor("font_weight_scale"), null)
            frames()
        }
        try {
            // Observed on the phone: the vendor slider is at 164, while Android reports zero.
            Settings.Secure.putInt(context.contentResolver, "font_weight_scale", 164)
            ThemeStore.saveAppFont(context, AppFont.SERIF)
            val configuration = mutableStateOf(Configuration(context.resources.configuration).apply { fontWeightAdjustment = 0 })
            view.setParentCompositionContext(recomposer)
            context.setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalConfiguration provides configuration.value) {
                    AppTheme { family = rememberAppFontFamily() }
                }
            }
            frames()
            assertEquals("Honor's maximum font-weight scale must reach the real Lora wght axis, not remain Regular",
                656f, axis(), 0.01f)
            assertEquals("Italic must receive the same system weight", 656f, axis(style = FontStyle.Italic), 0.01f)
            assertEquals("Bold must clamp to Lora's real maximum", 700f, axis(FontWeight.Bold), 0.01f)

            scale("100")
            assertEquals("Changing the system setting must invalidate the cached family", 400f, axis(), 0.01f)
            scale("164")
            assertEquals(656f, axis(), 0.01f)

            configuration.value = Configuration(configuration.value).apply { fontWeightAdjustment = 300 }
            frames()
            assertEquals("Compose already applies the standard adjustment; do not add the OEM scale twice", 400f, axis(), 0.01f)
            assertEquals(700f, axis(FontWeight.Bold), 0.01f)

            configuration.value = Configuration(configuration.value).apply {
                fontWeightAdjustment = Configuration.FONT_WEIGHT_ADJUSTMENT_UNDEFINED
            }
            frames()
            assertEquals("An undefined Android adjustment must still allow vendor detection", 656f, axis(), 0.01f)
            scale(null)
            assertEquals("Unavailable settings must safely fall back to Regular", 400f, axis(), 0.01f)

            configuration.value = Configuration(configuration.value).apply { fontWeightAdjustment = 0 }
            scale("not-a-number")
            assertEquals(400f, axis(), 0.01f)
            scale("0")
            assertEquals(400f, axis(), 0.01f)
            ThemeStore.saveAppFont(context, AppFont.SANS)
            frames()
            assertNull("Sans-serif must remain controlled by the system", family)
        } finally {
            view.disposeComposition(); recomposer.close(); runner.cancel()
            Settings.Secure.putString(context.contentResolver, "font_weight_scale", originalScale)
            ThemeStore.saveAppFont(context, originalFont)
            controller.pause().stop().destroy()
        }
    }
}
