package com.irio

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import com.composables.ui.theme.panelColor
import com.composables.ui.theme.secondaryColor
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
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ToolSettingsRowTest {
    private val entry = ToolSettingsEntry(TimeTool.NAME, "获取时间", TimeTool.DESCRIPTION)

    @Test
    fun expansionAndCollapsePassThroughIntermediateHeightsAndKeepTheDraft() = runTest {
        withRow(entry) { row ->
            repeat(4) { row.frame() }
            val compact = row.view.height
            row.expanded.value = true
            val opening = List(55) { row.frame(); row.view.height }
            val full = row.view.height
            assertTrue("Expanding must reveal the description editor", full > compact + 100)
            assertTrue("Expansion must animate height rather than jump", opening.any { it > compact + 1 && it < full - 1 })
            row.description.edit { replace(0, length, "An edited model-facing description") }
            row.expanded.value = false
            val closing = List(55) { row.frame(); row.view.height }
            assertEquals("Collapse must return to the compact header height", compact, row.view.height)
            assertTrue("Collapse must animate height rather than jump", closing.any { it > compact + 1 && it < full - 1 })
            row.expanded.value = true
            repeat(4) { row.frame() }
            row.expanded.value = false
            repeat(55) { row.frame() }
            assertEquals("Rapid reversal must settle at the latest requested state", compact, row.view.height)
            assertEquals("An interrupted animation must not reset the edit", "An edited model-facing description", row.description.text.toString())
        }
    }

    @Test
    fun blockedHeaderAndSwitchExplainThePrerequisiteWithoutExpandingOrChangingPreference() = runTest {
        withRow(entry.copy(blockedReason = ToolBlockedReason.MEMORY)) { row ->
            repeat(4) { row.frame() }
            row.click("tool-expand:${entry.name}")
            assertNull("A disabled switch must not have an independent clickable overlay", row.node("tool-blocked-toggle:${entry.name}"))
            val toggle = row.node("tool-toggle:${entry.name}")!!
            assertTrue(toggle.config.contains(SemanticsProperties.Disabled))
            assertFalse("Disabled switch must expose no independent click action", toggle.config.contains(SemanticsActions.OnClick))
            row.tap("tool-toggle:${entry.name}")
            assertEquals("Tapping a disabled switch must reach the card background", 2, row.blocked)
            assertFalse(row.expanded.value)
            assertTrue("Blocked interactions must preserve the user's enable preference", row.checked.value)
            assertEquals(0, row.toggleChanges)
        }
    }

    @Test
    fun switchAndHeaderHaveIndependentActionsAndEditorUsesTheLatestText() = runTest {
        withRow(entry) { row ->
            repeat(4) { row.frame() }
            row.tap("tool-toggle:${entry.name}")
            assertFalse(row.checked.value)
            assertFalse("Toggling must not also expand", row.expanded.value)
            row.click("tool-expand:${entry.name}")
            repeat(55) { row.frame() }
            assertTrue("An individually disabled tool must still allow description edits", row.expanded.value)
            row.description.edit { replace(0, length, "Latest description") }
            row.click("tool-save:${entry.name}")
            assertEquals("Latest description", row.saved)
            row.click("tool-reset:${entry.name}")
            assertEquals(entry.defaultDescription, row.description.text.toString())
        }
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun resetDescriptionUsesTheNeutralCancelBackground() = runTest {
        withRow(entry) { row ->
            row.expanded.value = true
            repeat(55) { row.frame() }
            val bitmap = android.graphics.Bitmap.createBitmap(row.view.width, row.view.height, android.graphics.Bitmap.Config.ARGB_8888)
            try {
                row.view.draw(android.graphics.Canvas(bitmap))
                fun fill(tag: String): Int {
                    val bounds = row.node(tag)!!.boundsInRoot
                    return bitmap.getPixel(bounds.center.x.toInt(), (bounds.top + 6).toInt())
                }
                val save = fill("tool-save:${entry.name}")
                val reset = fill("tool-reset:${entry.name}")
                val saveBounds = row.node("tool-save:${entry.name}")!!.boundsInRoot
                val resetBounds = row.node("tool-reset:${entry.name}")!!.boundsInRoot
                assertTrue("Reference save button must have an opaque fill", android.graphics.Color.alpha(save) > 240)
                assertEquals("Reset description must use the visible neutral fill", row.neutralColor, reset)
                assertNotEquals("Reset description must contrast with its secondary card", row.cardColor, reset)
                assertNotEquals("Only Save should use the accent fill", save, reset)
                assertEquals("Reset button must match Save width", saveBounds.width, resetBounds.width, 0.01f)
                assertEquals("Reset button must match Save height", saveBounds.height, resetBounds.height, 0.01f)
                assertEquals(
                    "Capsule background must leave the outer corner clear",
                    row.cardColor,
                    bitmap.getPixel(resetBounds.left.toInt() + 1, resetBounds.top.toInt() + 1),
                )
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun TestScope.withRow(entry: ToolSettingsEntry, check: (RowHarness) -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(controller.get())
        val row = RowHarness(this, view, clock, entry)
        try {
            view.setParentCompositionContext(recomposer)
            controller.get().setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme {
                        row.neutralColor = com.composeunstyled.theme.Theme[com.composables.ui.theme.colors][panelColor].toArgb()
                        row.cardColor = com.composeunstyled.theme.Theme[com.composables.ui.theme.colors][secondaryColor].toArgb()
                        ToolSettingsRow(
                            entry, row.checked.value, row.expanded.value, row.description,
                            onExpandedChange = { row.expanded.value = it },
                            onCheckedChange = { row.toggleChanges++; row.checked.value = it },
                            onSave = { row.saved = row.description.text.toString() },
                            onReset = { row.description.edit { replace(0, length, entry.defaultDescription) } },
                            onBlocked = { row.blocked++ },
                        )
                    }
                }
            }
            check(row)
        } finally {
            view.disposeComposition()
            recomposer.close()
            runner.cancel()
            controller.pause().stop().destroy()
        }
    }

    private class RowHarness(val scope: TestScope, val view: ComposeView, val clock: BroadcastFrameClock, entry: ToolSettingsEntry) {
        val expanded = mutableStateOf(false)
        val checked = mutableStateOf(true)
        val description = TextFieldState(entry.defaultDescription)
        var blocked = 0
        var toggleChanges = 0
        var saved: String? = null
        var neutralColor: Int = 0
        var cardColor: Int = 0
        private var time = 0L

        fun frame() {
            Snapshot.sendApplyNotifications()
            scope.runCurrent()
            time += 16_000_000L
            clock.sendFrame(time)
            scope.runCurrent()
            view.requestLayout()
            view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        }

        fun node(tag: String): SemanticsNode? {
            fun visit(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::visit)
            val root = (view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode
            return visit(root).firstOrNull { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == tag }
        }

        fun tap(tag: String) {
            val bounds = node(tag)!!.boundsInRoot
            val start = android.os.SystemClock.uptimeMillis()
            for ((action, time) in listOf(android.view.MotionEvent.ACTION_DOWN to start, android.view.MotionEvent.ACTION_UP to start + 40)) {
                val event = android.view.MotionEvent.obtain(start, time, action, bounds.center.x, bounds.center.y, 0)
                try { view.dispatchTouchEvent(event) } finally { event.recycle() }
            }
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            frame()
        }

        fun click(tag: String) {
            val node = node(tag)
            assertNotNull("Missing actionable control $tag", node)
            assertTrue("$tag must be clickable", node!!.config[SemanticsActions.OnClick].action!!.invoke())
            frame()
        }
    }
}
