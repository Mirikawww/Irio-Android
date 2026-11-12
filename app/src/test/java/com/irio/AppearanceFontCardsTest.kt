package com.irio

import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
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
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppearanceFontCardsTest {
    @Test fun optInReplacesOnlyFontTabsWithSideBySideRealFontPreviewsAndKeepsSelection() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val context = controller.get()
        ThemeStore.ensureLoaded(context)
        val oldEnabled = ThemeStore.betterSettingsDisplay
        val oldFont = ThemeStore.appFont
        val oldLanguage = ThemeStore.language
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(context)
        val density = mutableStateOf(Density(1f))
        var time = 0L
        fun frames() = repeat(12) {
            shadowOf(Looper.getMainLooper()).idle()
            Snapshot.sendApplyNotifications(); runCurrent()
            time += 16_000_000L; clock.sendFrame(time); runCurrent()
            view.requestLayout()
            view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 360, 1600)
            (view.getChildAt(0) as ViewRootForTest).measureAndLayoutForTest()
        }
        fun descendants(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::descendants)
        fun nodes() = descendants((view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
        fun hasText(n: SemanticsNode, text: String) = n.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == text }
        fun choice(font: AppFont): SemanticsNode = nodes().single {
            it.config.contains(SemanticsProperties.Selected) && descendants(it).any { n -> hasText(n, tr(font.label)) }
        }
        fun layout(node: SemanticsNode): TextLayoutResult {
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts))
            return layouts.single()
        }
        try {
            ThemeStore.saveLanguage(context, AppLanguage.ZH_CN)
            ThemeStore.saveAppFont(context, AppFont.SANS)
            ThemeStore.saveBetterSettingsDisplay(context, false)
            view.setParentCompositionContext(recomposer)
            context.setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides density.value) {
                    AppTheme { AppearanceSettingsScreen(onBack = {}) }
                }
            }
            frames()
            assertTrue(nodes().none { hasText(it, "Aa") })
            assertEquals(Role.Tab, choice(AppFont.SANS).config[SemanticsProperties.Role])
            ThemeStore.saveBetterSettingsDisplay(context, true)
            frames()
            assertEquals("Both fonts must be visible without selecting them first", 2, nodes().count { hasText(it, "Aa") })
            for (language in AppLanguage.entries) for (scale in listOf(1f, 2f)) {
                ThemeStore.saveLanguage(context, language)
                density.value = Density(1f, scale)
                frames()
                val title = nodes().single { hasText(it, tr("字体")) }
                val sans = choice(AppFont.SANS)
                val serif = choice(AppFont.SERIF)
                assertEquals(Role.RadioButton, sans.config[SemanticsProperties.Role])
                assertEquals(sans.boundsInRoot.top, serif.boundsInRoot.top, .5f)
                assertEquals(sans.boundsInRoot.width, serif.boundsInRoot.width, .5f)
                assertTrue("Cards must move to the next line below the title", sans.boundsInRoot.top >= title.boundsInRoot.bottom)
                assertTrue("$language / $scale cards must not overlap: ${sans.boundsInRoot} / ${serif.boundsInRoot}",
                    sans.boundsInRoot.right < serif.boundsInRoot.left)
                assertTrue(serif.boundsInRoot.right <= 360f)
                assertEquals("Font cards must be more compact", 84f * scale, sans.size.height.toFloat(), 1f)
                for (font in AppFont.entries) {
                    fun region(part: String) = nodes().single {
                        it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "font-${font.stored}-$part"
                    }
                    val preview = region("preview")
                    val name = region("name")
                    assertEquals("Preview must occupy two thirds of the card", name.size.height * 2f,
                        preview.size.height.toFloat(), 1f)
                    assertEquals(preview.boundsInRoot.bottom, name.boundsInRoot.top, .5f)
                    assertEquals(choice(font).boundsInRoot.width, name.boundsInRoot.width, .5f)
                }
                val a = layout(descendants(sans).single { hasText(it, "Aa") })
                val b = layout(descendants(serif).single { hasText(it, "Aa") })
                for (card in listOf(sans, serif)) {
                    val sample = descendants(card).single { hasText(it, "Aa") }
                    val result = layout(sample)
                    val input = result.layoutInput
                    val typeface = input.fontFamilyResolver.resolve(input.style.fontFamily,
                        input.style.fontWeight ?: androidx.compose.ui.text.font.FontWeight.Normal).value as android.graphics.Typeface
                    val paint = android.graphics.Paint().apply {
                        this.typeface = typeface
                        textSize = with(input.density) { input.style.fontSize.toPx() }
                    }
                    val ink = android.graphics.Rect()
                    paint.getTextBounds("Aa", 0, 2, ink)
                    val region = nodes().single {
                        it.config.getOrElse(SemanticsProperties.TestTag) { "" } ==
                            "font-${if (card == sans) "sans" else "serif"}-preview"
                    }
                    val center = sample.positionInRoot.y + result.firstBaseline + (ink.top + ink.bottom) / 2f
                    assertEquals("Visible Aa glyphs must be centered within pixel rounding", region.boundsInRoot.center.y, center, .5f)
                }
                assertEquals(FontFamily.Default, a.layoutInput.style.fontFamily)
                assertNotEquals("The serif sample must use the bundled font, not the currently selected sans", a.layoutInput.style.fontFamily, b.layoutInput.style.fontFamily)
                assertTrue("Native text must have different glyph metrics for the two fonts", kotlin.math.abs(a.getLineRight(0) - b.getLineRight(0)) > .1f)
                for (sample in listOf(a, b)) {
                    assertEquals("The Aa sample must stay on one line", 1, sample.lineCount)
                    assertTrue("Sample must fit apart from subpixel rounding", sample.getLineRight(0) <= sample.size.width + .75f)
                    assertTrue("Sample must not be clipped vertically", sample.getLineBottom(0) <= sample.size.height + .75f)
                }
            }
            assertTrue(choice(AppFont.SERIF).config[SemanticsActions.OnClick].action!!.invoke())
            frames()
            assertEquals(AppFont.SERIF, ThemeStore.appFont)
            assertEquals("serif", context.getSharedPreferences("appearance_settings", 0).getString("app_font", null))
            assertTrue(choice(AppFont.SERIF).config[SemanticsProperties.Selected])
            assertEquals(FontFamily.Default, layout(nodes().first { hasText(it, "Aa") }).layoutInput.style.fontFamily)
            ThemeStore.saveBetterSettingsDisplay(context, false)
            frames()
            assertTrue(nodes().none { hasText(it, "Aa") })
            assertEquals(Role.Tab, choice(AppFont.SERIF).config[SemanticsProperties.Role])
            assertTrue(choice(AppFont.SERIF).config[SemanticsProperties.Selected])
            assertTrue(choice(AppFont.SANS).config[SemanticsActions.OnClick].action!!.invoke())
            frames()
            assertEquals(AppFont.SANS, ThemeStore.appFont)
        } finally {
            view.disposeComposition(); recomposer.close(); runner.cancel()
            ThemeStore.saveBetterSettingsDisplay(context, oldEnabled)
            ThemeStore.saveAppFont(context, oldFont)
            ThemeStore.saveLanguage(context, oldLanguage)
            controller.pause().stop().destroy()
        }
    }
}
