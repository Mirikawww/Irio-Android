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
            assertTrue("The laboratory page must keep its settings title", nodes().any {
                it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { text -> text.text == tr("实验室") }
            })
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
        }
    }
}
