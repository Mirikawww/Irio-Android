package com.irio

import android.os.Looper
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import com.composables.ui.theme.colors
import com.composables.ui.theme.panelColor
import com.composables.ui.theme.secondaryColor
import com.composeunstyled.theme.Theme
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
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
@Config(sdk = [35], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageGenerationControlsTest {
    @Test fun imageParametersKeepThinkingAndBothOriginalSwitches() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(controller.get())
        var searchChanged = false
        var autoReadChanged = false
        try {
            view.setParentCompositionContext(recomposer)
            controller.get().setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme {
                        Column {
                            ModelSwitcherParameterControls(
                                imageModel = true, imageOptions = ImageGenerationOptions(), compactInteractive = true,
                                supportsOpenAiOptions = true, reasoningEffort = ReasoningEffort.MEDIUM,
                                supportsThinking = true, reasoningLevels = listOf(ReasoningEffort.OFF, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
                                webSearchAvailable = true, webSearchEnabled = false, autoRead = false,
                                onReasoningChange = {}, onWebSearchChange = { searchChanged = it },
                                onAutoReadChange = { autoReadChanged = it }, onImageOptionsChange = {}, onAspectRatio = {},
                            )
                        }
                    }
                }
            }
            repeat(12) { frame ->
                Snapshot.sendApplyNotifications()
                runCurrent()
                clock.sendFrame((frame + 1) * 16_000_000L)
                runCurrent()
                view.measure(View.MeasureSpec.makeMeasureSpec(264, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(349, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, 264, 349)
            }
            fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
            val nodes = descend((view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
            assertTrue("Image parameters must not replace the thinking slider", nodes.any {
                it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.contains(tr("思考强度"))
            })
            fun text(label: String) = nodes.single {
                it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { value -> value.text == tr(label) }
            }
            fun toggle(label: String) {
                var node = text(label)
                while (!node.config.contains(SemanticsActions.OnClick)) node = node.parent!!
                assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
            }
            assertTrue(text("网络搜索").boundsInRoot.top < text("自动朗读").boundsInRoot.top)
            val orderedLabels = listOf("分辨率", "宽高比", "背景", "质量", "网络搜索", "自动朗读")
            orderedLabels.zipWithNext().forEach { (before, after) ->
                assertTrue("$before must appear above $after", text(before).boundsInRoot.bottom <= text(after).boundsInRoot.top)
            }
            val thinking = nodes.single {
                it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.contains(tr("思考强度"))
            }
            assertTrue(thinking.boundsInRoot.bottom <= text("分辨率").boundsInRoot.top)
            assertTrue(text("自动朗读").boundsInRoot.bottom <= 349f)
            toggle("网络搜索")
            toggle("自动朗读")
            assertTrue(searchChanged)
            assertTrue(autoReadChanged)
            assertEquals(396f, modelSwitcherCompactHeight(true).value, 0.01f)
            assertEquals(220f, modelSwitcherCompactHeight(false).value, 0.01f)
        } finally {
            view.disposeComposition(); recomposer.close(); runner.cancel()
            controller.pause().stop().destroy()
        }
    }

    @Test fun choicesAreRightAlignedAndUpdateIndependentParameters() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(controller.get())
        val options = mutableStateOf(ImageGenerationOptions())
        var trackColor = 0
        var expectedPanelColor = 0
        var ratioOpened = false
        try {
            view.setParentCompositionContext(recomposer)
            controller.get().setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme {
                        trackColor = Theme[colors][secondaryColor].toArgb()
                        expectedPanelColor = Theme[colors][panelColor].toArgb()
                        Column(Modifier.background(Theme[colors][panelColor])) {
                            ImageGenerationControls(options.value, active = true, onChange = { options.value = it }, onAspectRatio = { ratioOpened = true })
                        }
                    }
                }
            }
            var time = 0L
            fun frame() {
                Snapshot.sendApplyNotifications()
                runCurrent()
                time += 16_000_000L
                clock.sendFrame(time)
                runCurrent()
                view.measure(View.MeasureSpec.makeMeasureSpec(264, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(176, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, 264, 176)
            }
            repeat(8) { frame() }
            fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
            fun nodes() = descend((view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
            fun text(label: String) = nodes().single {
                it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { value -> value.text == label }
            }
            fun click(label: String) {
                var current = text(label)
                while (!current.config.contains(SemanticsActions.OnClick)) current = current.parent!!
                assertTrue(current.config[SemanticsActions.OnClick].action!!.invoke())
                repeat(30) { frame() }
            }
            listOf("分辨率", "宽高比", "背景", "质量").forEach { title ->
                assertEquals(8f, text(tr(title)).boundsInRoot.left, 0.5f)
            }
            listOf("1K", "2K", "4K").forEach { label ->
                assertTrue(text(label).boundsInRoot.left > text(tr("分辨率")).boundsInRoot.right)
                val layouts = mutableListOf<TextLayoutResult>()
                text(label).config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
                val layout = layouts.single()
                assertFalse("$label overflows: width=${layout.didOverflowWidth}, height=${layout.didOverflowHeight}, size=${layout.size}, constraints=${layout.layoutInput.constraints}, style=${layout.layoutInput.style}",
                    layout.hasVisualOverflow)
            }
            val tabs = nodes().single {
                it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.contains(tr("分辨率"))
            }.boundsInRoot
            val bitmap = Bitmap.createBitmap(264, 176, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                assertNotEquals("The segmented control track must differ from the menu panel", expectedPanelColor, trackColor)
                assertEquals("The segmented control must draw its neutral capsule background", trackColor,
                    bitmap.getPixel((tabs.right - 2).toInt(), tabs.center.y.toInt()))
            } finally { bitmap.recycle() }
            click("4K")
            assertEquals(ImageResolution.K4, options.value.resolution)
            click(tr("透明"))
            assertEquals(ImageBackground.TRANSPARENT, options.value.background)
            click(tr("高"))
            assertEquals(ImageQuality.HIGH, options.value.quality)
            assertEquals(ImageResolution.K4, options.value.resolution)
            click(tr("不指定"))
            assertTrue(ratioOpened)
        } finally {
            view.disposeComposition()
            recomposer.close()
            runner.cancel()
            controller.pause().stop().destroy()
        }
    }

    @Test fun optionsPersistPerModelAndProviderAndSnapshotsStayImmutable() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val context = controller.get()
        try {
            val initial = ImageGenerationStore.load(context, "first", "gpt-image-2")
            assertEquals(ImageGenerationOptions(), initial)
            val edited = initial.copy(resolution = ImageResolution.K4, aspectRatio = ImageAspectRatio(7, 5),
                background = ImageBackground.TRANSPARENT, quality = ImageQuality.HIGH)
            ImageGenerationStore.save(context, "first", "gpt-image-2", edited)
            assertEquals(edited, ImageGenerationStore.load(context, "first", "gpt-image-2"))
            assertEquals(initial, ImageGenerationStore.load(context, "other", "gpt-image-2"))
            assertEquals(initial, ImageGenerationStore.load(context, "first", "gpt-image-1"))
            ImageGenerationStore.save(context, "first", "gpt-image-2", edited.copy(quality = ImageQuality.LOW))
            assertEquals(ImageQuality.HIGH, edited.quality)
            assertEquals(ImageQuality.LOW, ImageGenerationStore.load(context, "first", "gpt-image-2").quality)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun aspectRatioMenuOffersAutomaticPresetsAndCustomEntry() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(controller.get())
        val options = mutableStateOf(ImageGenerationOptions(aspectRatio = ImageAspectRatio(1, 1)))
        var customSaves = 0
        try {
            view.setParentCompositionContext(recomposer)
            controller.get().setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme {
                        ImageAspectRatioList(options.value, androidx.compose.ui.Modifier,
                            mutableStateOf(1f), active = true,
                            onSelect = { options.value = options.value.copy(aspectRatio = it, useCustomAspectRatio = false) },
                            onCustomChange = {
                                customSaves++
                                options.value = options.value.copy(aspectRatio = it, useCustomAspectRatio = true, customRatio = it)
                            })
                    }
                }
            }
            var frameTime = 0L
            fun frame() {
                shadowOf(Looper.getMainLooper()).idle()
                Snapshot.sendApplyNotifications()
                runCurrent()
                frameTime += 16_000_000L
                clock.sendFrame(frameTime)
                runCurrent()
                view.measure(View.MeasureSpec.makeMeasureSpec(264, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(650, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, 264, 650)
            }
            repeat(12) { frame() }
            fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
            fun nativeViews(root: View): List<View> = listOf(root) + if (root is ViewGroup) {
                (0 until root.childCount).flatMap { nativeViews(root.getChildAt(it)) }
            } else emptyList()
            val recycler = nativeViews(view).filterIsInstance<androidx.recyclerview.widget.RecyclerView>().single()
            fun nodes() = nativeViews(recycler).filterIsInstance<ViewRootForTest>().flatMap {
                descend(it.semanticsOwner.unmergedRootSemanticsNode)
            }
            fun click(label: String) {
                var node = nodes().single {
                    it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { value -> value.text == label }
                }
                while (!node.config.contains(SemanticsActions.OnClick)) node = node.parent!!
                assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
            }
            click(tr("不指定"))
            repeat(8) { frame() }
            assertNull(options.value.aspectRatio)
            click("16:9")
            repeat(8) { frame() }
            assertEquals(ImageAspectRatio(16, 9), options.value.aspectRatio)
            click("1:3")
            repeat(8) { frame() }
            assertEquals(ImageAspectRatio(1, 3), options.value.aspectRatio)
            click(tr("自定义"))
            repeat(8) { frame() }
            assertTrue(options.value.useCustomAspectRatio)
            assertEquals(ImageAspectRatio(1, 1), options.value.aspectRatio)
            fun field(label: String) = nodes().single {
                it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.contains(tr(label)) &&
                    it.config.contains(SemanticsActions.SetText)
            }
            fun focus(label: String) {
                assertTrue(field(label).config[SemanticsActions.RequestFocus].action!!.invoke())
                repeat(8) { frame() }
            }
            fun enter(label: String, value: String) {
                assertTrue(field(label).config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value)))
                repeat(8) { frame() }
            }
            assertFalse(nodes().any { it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { value -> value.text == tr("确定") } })
            focus("宽度")
            enter("宽度", "2")
            val beforeWidthBlur = customSaves
            focus("高度")
            assertEquals("Width blur must immediately save", beforeWidthBlur + 1, customSaves)
            assertEquals(ImageAspectRatio(2, 1), options.value.aspectRatio)
            enter("高度", "3")
            val beforeHeightBlur = customSaves
            focus("宽度")
            assertEquals("Height blur must immediately save", beforeHeightBlur + 1, customSaves)
            assertEquals(ImageAspectRatio(2, 3), options.value.aspectRatio)
            assertTrue("A custom ratio matching a preset must remain custom", options.value.useCustomAspectRatio)
            enter("宽度", "7")
            enter("高度", "5")
            focus("高度")
            assertEquals(ImageAspectRatio(7, 5), options.value.aspectRatio)
            focus("宽度")
            enter("宽度", "100")
            val beforeInvalidBlur = customSaves
            focus("高度")
            assertEquals(beforeInvalidBlur, customSaves)
            assertEquals("Invalid drafts must not overwrite saved ratios", ImageAspectRatio(7, 5), options.value.aspectRatio)
            click("16:9")
            repeat(8) { frame() }
            assertFalse(options.value.useCustomAspectRatio)
            assertEquals(ImageAspectRatio(16, 9), options.value.aspectRatio)
        } finally {
            view.disposeComposition()
            recomposer.close()
            runner.cancel()
            controller.pause().stop().destroy()
        }
    }
}
