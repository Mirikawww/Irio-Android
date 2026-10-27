package com.irio

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.composables.ui.components.DropdownMenu
import com.irio.ScaledBottomSheet as BottomSheet
import com.composables.ui.components.BottomSheetDetent
import com.composables.ui.components.rememberBottomSheetState
import com.composables.ui.components.DropdownMenuItem
import com.irio.ScaledDropdownMenuPanel as DropdownMenuPanel
import com.composables.ui.components.Text
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
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

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppTextScaleUiTest {
    @Test fun draggingPreviewsPercentageAndOnlyAppliesTextScaleOnRelease() = runTest {
        withUi(content = {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                AppTheme {
                    Column {
                        TextScaleSettings()
                        Text("scale probe", fontSize = 20.sp)
                    }
                }
            }
        }) {
            ThemeStore.saveTextScaleMode(context, TextScaleMode.CUSTOM)
            frames(40)
            val slider = nodes().single { it.config.contains(SemanticsActions.SetProgress) }
            val bounds = slider.boundsInRoot
            val down = SystemClock.uptimeMillis()
            fun touch(action: Int, step: Int, fraction: Float) {
                val event = MotionEvent.obtain(down, down + step * 20L, action,
                    bounds.left + bounds.width * fraction, bounds.center.y, 0)
                try { view.dispatchTouchEvent(event) } finally { event.recycle() }
                frames(2)
            }
            touch(MotionEvent.ACTION_DOWN, 0, 1f / 3f)
            for (step in 1..8) {
                touch(MotionEvent.ACTION_MOVE, step, 1f / 3f + step * 0.05f)
                assertEquals("Dragging must not apply the font scale", 100, ThemeStore.textScalePercent)
                assertEquals(1f, textLayout("scale probe").layoutInput.density.fontScale, 0f)
                assertEquals("Dragging must not persist the pending percentage", 100,
                    context.getSharedPreferences("appearance_settings", android.content.Context.MODE_PRIVATE)
                        .getInt("text_scale_percent", 100))
            }
            val preview = nodes().single { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) }
                .config[SemanticsProperties.ProgressBarRangeInfo].current.toInt()
            assertTrue("The thumb must move while the applied size stays unchanged", preview > 140)
            text("$preview%")
            touch(MotionEvent.ACTION_UP, 9, 1f / 3f + 0.4f)
            frames()
            assertEquals(preview, ThemeStore.textScalePercent)
            assertEquals(preview / 100f, textLayout("scale probe").layoutInput.density.fontScale, 0f)
        }
    }

    @Test fun rootUsesAbsoluteCustomScaleAndRestoresTheExactSystemDensity() = runTest {
        val incoming = mutableStateOf(Density(2f, 1.4f))
        var observed: Density? = null
        withUi(content = {
            CompositionLocalProvider(LocalDensity provides incoming.value) {
                AppTheme {
                    observed = LocalDensity.current
                    ProvideAppTextScale { Text("probe", fontSize = 20.sp, lineHeight = 28.sp) }
                }
            }
        }) {
            frames()
            assertSame(incoming.value, observed)
            ThemeStore.saveTextScaleMode(context, TextScaleMode.CUSTOM)
            for (percent in listOf(50, 100, 137, 200)) {
                ThemeStore.saveTextScalePercent(context, percent)
                frames()
                assertEquals(2f, observed!!.density, 0f)
                assertEquals(percent / 100f, observed!!.fontScale, 0f)
                assertEquals(percent / 100f, textLayout("probe").layoutInput.density.fontScale, 0f)
            }
            incoming.value = Density(3f, 1.8f)
            frames()
            assertEquals(3f, observed!!.density, 0f)
            assertEquals(2f, observed!!.fontScale, 0f)
            ThemeStore.saveTextScaleMode(context, TextScaleMode.SYSTEM)
            frames()
            assertSame(incoming.value, observed)
            assertEquals(200, ThemeStore.textScalePercent)
        }
    }

    @Test fun selectorAnimatesSliderInBothDirectionsAndPreservesItsCustomValue() = runTest {
        withUi(content = {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                AppTheme {
                    Column {
                        TextScaleSettings()
                        Text("below")
                    }
                }
            }
        }) {
            frames()
            assertTrue(nodes().none { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) })
            val collapsed = text("below").boundsInRoot.top
            select("自定义")
            val expansionSamples = List(40) { frame(); text("below").boundsInRoot.top }
            val expanded = text("below").boundsInRoot.top
            assertTrue("Slider must expand over intermediate frames: $expansionSamples", expansionSamples.any { it > collapsed && it < expanded })
            val slider = nodes().single { it.config.contains(SemanticsActions.SetProgress) }
            val range = slider.config[SemanticsProperties.ProgressBarRangeInfo]
            assertEquals(50f..200f, range.range)
            assertEquals(100f, range.current, 0f)
            assertTrue(slider.config[SemanticsActions.SetProgress].action!!.invoke(137f))
            frames()
            assertEquals(137, ThemeStore.textScalePercent)
            text("137%")
            // Keep the font size fixed for the animation-only assertion.
            ThemeStore.saveTextScalePercent(context, 100)
            frames()
            select("跟随系统")
            val collapseSamples = List(40) { frame(); text("below").boundsInRoot.top }
            assertTrue("Slider must shrink over intermediate frames", collapseSamples.any { it > collapsed && it < expanded })
            assertEquals(collapsed, text("below").boundsInRoot.top, 0.5f)
            assertTrue(nodes().none { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) })
            ThemeStore.saveTextScalePercent(context, 137)
            select("自定义")
            frames(40)
            text("137%")
            assertEquals(TextScaleMode.CUSTOM, ThemeStore.textScaleMode)
        }
    }

    @Test fun selectorFitsAtBothScaleLimitsInBothLanguages() = runTest {
        withUi(content = { AppTheme { Column { TextScaleSettings() } } }) {
            ThemeStore.saveTextScaleMode(context, TextScaleMode.CUSTOM)
            for (language in AppLanguage.entries) for (percent in listOf(50, 200)) {
                ThemeStore.saveLanguage(context, language)
                ThemeStore.saveTextScalePercent(context, percent)
                frames(40)
                for (label in listOf(tr("文本缩放"), tr("跟随系统"), tr("自定义"))) {
                    val node = text(label)
                    assertTrue("$language / $percent / $label must fit horizontally", node.boundsInRoot.left >= 0 && node.boundsInRoot.right <= 360)
                    val layout = textLayout(label)
                    assertFalse("$label must not be clipped vertically", layout.didOverflowHeight)
                    assertTrue("$label must fit apart from subpixel rounding", (0 until layout.lineCount).all {
                        !layout.isLineEllipsized(it) && layout.getLineRight(it) <= layout.size.width + 0.75f
                    })
                }
            }
        }
    }

    @Test fun recycledRowsAndMenuPanelsReceiveTheSameCustomScale() = runTest {
        var rowScale = 0f
        var menuScale = 0f
        withUi(content = {
            AppTheme {
                Column {
                    DropdownMenu(expanded = true, onExpandedChange = {}, panel = {
                        DropdownMenuPanel {
                            menuScale = LocalDensity.current.fontScale
                            DropdownMenuItem(onClick = {}) { Text("menu probe") }
                        }
                    }) { Text("menu") }
                    AndroidComposeRecyclerList(items = listOf("row"), key = { it }, modifier = Modifier.fillMaxWidth()) {
                        rowScale = LocalDensity.current.fontScale
                        Text(it, fontSize = 20.sp)
                    }
                }
            }
        }) {
            ThemeStore.saveTextScaleMode(context, TextScaleMode.CUSTOM)
            for (percent in listOf(50, 150, 200)) {
                ThemeStore.saveTextScalePercent(context, percent)
                frames(30)
                assertEquals("RecyclerView rows must not reset to Android's font scale", percent / 100f, rowScale, 0f)
                assertEquals("Menu panels must not reset to Android's font scale", percent / 100f, menuScale, 0f)
            }
        }
    }

    @Test fun imageCompressionSettingsUseTheExpectedControlOrderAndDefaults() = runTest {
        withUi(content = { AppTheme { Column { ImageUploadSettings() } } }) {
            frames(40)
            text(tr("图片压缩与转换"))
            val toggle = nodes().single { it.config.contains(SemanticsProperties.ToggleableState) }
            assertEquals(androidx.compose.ui.state.ToggleableState.On, toggle.config[SemanticsProperties.ToggleableState])
            text(tr("转换格式")); text("WebP"); text("JPEG"); text(tr("质量")); text("90%")
            val slider = nodes().single { it.config.contains(SemanticsActions.SetProgress) }
            assertEquals(40f..100f, slider.config[SemanticsProperties.ProgressBarRangeInfo].range)
            assertEquals(90f, slider.config[SemanticsProperties.ProgressBarRangeInfo].current, 0.001f)
        }
    }

    @Test fun dialogsAndBottomSheetsReceiveCustomScale() = runTest {
        var dialogScale = 0f
        var sheetScale = 0f
        withUi(content = {
            AppTheme {
                val sheet = rememberBottomSheetState()
                LaunchedEffect(Unit) { sheet.targetDetent = BottomSheetDetent.FullyExpanded }
                IrioAlertDialog(visible = true, onDismissRequest = {}) {
                    dialogScale = LocalDensity.current.fontScale
                    Text("dialog probe")
                }
                BottomSheet(state = sheet, onDismissRequest = {}) {
                    sheetScale = LocalDensity.current.fontScale
                    Text("sheet probe")
                }
            }
        }) {
            ThemeStore.saveTextScaleMode(context, TextScaleMode.CUSTOM)
            ThemeStore.saveTextScalePercent(context, 150)
            frames(60)
            assertEquals("Dialog must reapply custom scale", 1.5f, dialogScale, 0f)
            assertEquals("Bottom sheet must preserve custom scale", 1.5f, sheetScale, 0f)
        }
    }

    @Test fun retainedMessageHolderUpdatesItsTextScaleWithoutRebinding() = runTest {
        val markdownScope = this
        withUi(content = {
            AppTheme {
                val context = LocalContext.current
                val item = remember(context) {
                    val list = MessageRecyclerView(context)
                    list.updateCallbacks(AndroidMessageCallbacks(
                        markdownScope = markdownScope,
                        uploadTracker = AttachmentUploadTracker(), uploadConversationId = "scale-test",
                        onOpenProcess = { _, _ -> }, onOpenAttachment = {}, onCopy = {}, onQuote = {},
                        onAddReaction = {}, onRemoveReaction = {}, reactionAnimations = ReactionBounceTracker(),
                        onRetry = {}, canRetry = { false }, onPrevVersion = { null }, onNextVersion = { null },
                        isEditing = { false }, onEdit = { null }, onPrevEditVersion = { null }, onNextEditVersion = { null },
                        versionInfo = { VersionInfo(1, 1) }, editVersionInfo = { VersionInfo(1, 1) }, newUserMessageId = null,
                    ))
                    val holder = list.adapter!!.createViewHolder(list, 0)
                    holder.javaClass.getDeclaredMethod("bind", ChatMessage::class.java).apply { isAccessible = true }
                        .invoke(holder, ChatMessage(1L, "message probe", true))
                    holder.itemView
                }
                AndroidView(factory = { item })
            }
        }) {
            ThemeStore.saveTextScaleMode(context, TextScaleMode.CUSTOM)
            for (percent in listOf(50, 100, 200)) {
                ThemeStore.saveTextScalePercent(context, percent)
                frames(20)
                assertEquals(percent / 100f, textLayout("message probe").layoutInput.density.fontScale, 0f)
            }
            ThemeStore.saveTextScaleMode(context, TextScaleMode.SYSTEM)
            frames(20)
            assertEquals(context.resources.configuration.fontScale, textLayout("message probe").layoutInput.density.fontScale, 0f)
        }
    }

    private fun TestScope.withUi(content: @Composable () -> Unit, check: Harness.() -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val context = controller.get()
        ThemeStore.ensureLoaded(context)
        val oldMode = ThemeStore.textScaleMode
        val oldPercent = ThemeStore.textScalePercent
        val oldLanguage = ThemeStore.language
        ThemeStore.saveTextScaleMode(context, TextScaleMode.SYSTEM)
        ThemeStore.saveTextScalePercent(context, 100)
        ThemeStore.saveLanguage(context, AppLanguage.ZH_CN)
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(context)
        try {
            view.setParentCompositionContext(recomposer)
            controller.get().setContentView(view)
            view.setContent(content)
            Harness(this, view, clock).check()
        } finally {
            view.disposeComposition(); recomposer.close(); runner.cancel()
            ThemeStore.saveTextScaleMode(context, oldMode)
            ThemeStore.saveTextScalePercent(context, oldPercent)
            ThemeStore.saveLanguage(context, oldLanguage)
            controller.pause().stop().destroy()
        }
    }

    private class Harness(val scope: TestScope, val view: ComposeView, val clock: BroadcastFrameClock) {
        val context get() = view.context
        private var time = 0L
        fun frame(ms: Long = 16) {
            shadowOf(Looper.getMainLooper()).idle()
            Snapshot.sendApplyNotifications()
            scope.runCurrent()
            time += ms * 1_000_000L
            clock.sendFrame(time)
            scope.runCurrent()
            view.requestLayout()
            view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 360, 1200)
        }
        fun frames(count: Int = 8) { repeat(count) { frame() } }
        private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
        fun nodes(): List<SemanticsNode> {
            fun visit(view: View): List<SemanticsNode> {
                val own = if (view is ViewRootForTest) descendants(view.semanticsOwner.unmergedRootSemanticsNode) else emptyList()
                return own + if (view is ViewGroup) (0 until view.childCount).flatMap { visit(view.getChildAt(it)) } else emptyList()
            }
            return visit(view)
        }
        fun text(value: String) = nodes().single { it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { t -> t.text == value } }
        fun textLayout(value: String): TextLayoutResult {
            val result = mutableListOf<TextLayoutResult>()
            text(value).config[SemanticsActions.GetTextLayoutResult].action!!.invoke(result)
            return result.single()
        }
        fun select(value: String) {
            val tab = nodes().single { it.config.contains(SemanticsProperties.Selected) && descendants(it).any { child ->
                child.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { t -> t.text == value }
            } }
            assertTrue(tab.config[SemanticsActions.OnClick].action!!.invoke())
        }
    }
}
