package com.irio

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class BackupExportNoticeTest {
    @Test fun noticeAppearsOnlyAfterSuccessfulDocumentExportAndCanBeDismissed() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val context = controller.get()
        ThemeStore.ensureLoaded(context)
        val language = ThemeStore.language
        ThemeStore.saveLanguage(context, AppLanguage.ZH_CN)
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(context)
        var time = 0L
        var exports = 0
        var fail = false
        val body = ".irio 备份包含 API 密钥及 MCP 登录凭据，请勿公开分享。"
        fun frames(count: Int = 40) = repeat(count) {
            shadowOf(Looper.getMainLooper()).idle()
            Snapshot.sendApplyNotifications(); runCurrent()
            clock.sendFrame(time.also { time += 16_000_000L }); runCurrent()
            view.requestLayout()
            view.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 400, 900)
        }
        fun nodes(): List<SemanticsNode> {
            fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
            fun roots(view: View): List<ViewRootForTest> =
                (if (view is ViewRootForTest) listOf(view) else emptyList()) +
                    (if (view is ViewGroup) (0 until view.childCount).flatMap { roots(view.getChildAt(it)) } else emptyList())
            val cls = Class.forName("android.view.WindowManagerGlobal")
            val global = cls.getMethod("getInstance").invoke(null)
            val views = cls.getDeclaredField("mViews").apply { isAccessible = true }.get(global) as List<*>
            return (views.filterIsInstance<View>() + view).flatMap(::roots).distinct()
                .flatMap { descend(it.semanticsOwner.unmergedRootSemanticsNode) }
        }
        fun hasText(value: String) = nodes().any { node ->
            node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == value }
        }
        fun click(value: String) {
            var node = nodes().single { node ->
                node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == value }
            }
            while (!node.config.contains(SemanticsActions.OnClick)) node = requireNotNull(node.parent)
            assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
            frames()
        }
        fun returnFromPicker(code: Int, uri: Uri? = null) {
            val started = shadowOf(context).nextStartedActivityForResult
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, started.intent.action)
            assertTrue(context.activityResultRegistry.dispatchResult(started.requestCode, code,
                Intent().apply { data = uri }))
            frames()
        }
        try {
            view.setParentCompositionContext(recomposer)
            context.setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme {
                        OtherSettingsScreen(onBack = {}, exportBackup = { _, output ->
                            exports++
                            if (fail) error("Fixture export failure")
                            output.write("fixture backup".toByteArray())
                        })
                    }
                }
            }
            frames()
            assertFalse(hasText("注意"))
            click("导出数据")
            assertFalse("Opening the picker is not export success", hasText("注意"))
            returnFromPicker(Activity.RESULT_CANCELED)
            assertEquals(0, exports)
            assertFalse(hasText("注意"))
            click("导出数据")
            val file = File(context.cacheDir, "notice-fixture.irio")
            returnFromPicker(Activity.RESULT_OK, Uri.fromFile(file))
            assertEquals("fixture backup", file.readText())
            assertEquals(1, exports)
            assertTrue("Successful export must display the notice title", hasText("注意"))
            assertTrue(hasText(body))
            assertFalse("The prefix belongs in the title, not the body", hasText("注意：$body"))
            click("知道了")
            assertFalse(hasText("注意"))
            fail = true
            click("导出数据")
            returnFromPicker(Activity.RESULT_OK, Uri.fromFile(file))
            assertEquals(2, exports)
            assertFalse("Failed export must not show a success notice", hasText("注意"))
            assertTrue(hasText("导出失败"))
        } finally {
            view.disposeComposition(); recomposer.close(); runner.cancel()
            ThemeStore.saveLanguage(context, language)
            controller.pause().stop().destroy()
        }
    }
}
