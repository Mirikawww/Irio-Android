package com.irio

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class StartupUpdateSnackbarTest {
    @Test fun startupResultUsesTheBottomSnackbarAndDismissesWithoutRechecking() = runTest {
        withUi { fixture ->
            var requests = 0
            val result = availableUpdate()
            val label = tr("发现新版本：%s").format(result.release.tag)
            fixture.check.start { requests++; result }
            fixture.frames(30)
            val message = fixture.text(label)
            assertNotNull(message)
            assertNotNull(fixture.text(tr("查看")))
            assertTrue("The update snackbar must sit at the bottom above the system bar", message!!.boundsInRoot.bottom <= 600.5f)
            assertTrue("The update snackbar must not appear at the top", message.boundsInRoot.top > 480f)
            fixture.scope.advanceTimeBy(5_100L)
            fixture.frames(30)
            assertNull(fixture.check.notice.value)
            assertFalse(fixture.check.start { requests++; UpdateCheckResult.UpToDate })
            fixture.frames()
            assertNull(fixture.text(label))
            assertEquals(1, requests)
        }
    }

    @Test fun noUpdateResultsNeverShowASnackbarOrAViewButton() = runTest {
        for (result in listOf(UpdateCheckResult.UpToDate, UpdateCheckResult.NoReleases)) {
            withUi { fixture ->
                fixture.check.start { result }
                fixture.frames(30)
                assertNull(fixture.check.notice.value)
                assertNull(fixture.text(tr("当前已是最新版本")))
                assertNull(fixture.text(tr("所选渠道暂无已发布版本")))
                assertNull(fixture.text(tr("查看")))
                fixture.pause()
                fixture.frames()
                fixture.resume()
                fixture.frames(30)
                assertNull("Returning to the foreground must not resurrect a silent result", fixture.check.notice.value)
                assertNull(fixture.text(tr("当前已是最新版本")))
                assertNull(fixture.text(tr("所选渠道暂无已发布版本")))
            }
        }
    }

    @Test fun aResultReceivedInBackgroundWaitsUntilTheScreenResumes() = runTest {
        withUi { fixture ->
            val result = CompletableDeferred<UpdateCheckResult>()
            fixture.check.start { result.await() }
            fixture.scope.runCurrent()
            fixture.pause()
            fixture.frames()
            val update = availableUpdate()
            val label = tr("发现新版本：%s").format(update.release.tag)
            result.complete(update)
            fixture.frames()
            fixture.scope.advanceTimeBy(10_000L)
            fixture.frames()
            assertNotNull("Background time must not consume the pending notice", fixture.check.notice.value)
            assertNull(fixture.text(label))
            fixture.resume()
            fixture.frames(30)
            assertNotNull(fixture.text(label))
            fixture.scope.advanceTimeBy(4_000L)
            fixture.frames()
            assertNotNull(fixture.check.notice.value)
            fixture.scope.advanceTimeBy(1_500L)
            fixture.frames(30)
            assertNull(fixture.check.notice.value)
        }
    }

    @Test fun existingTitleNoticesTakePriorityWithoutLosingTheUpdateNotice() = runTest {
        withUi { fixture ->
            fixture.title.value = "title generation notice"
            val update = availableUpdate()
            val label = tr("发现新版本：%s").format(update.release.tag)
            fixture.check.start { update }
            fixture.frames(30)
            assertNotNull(fixture.text("title generation notice"))
            assertNull(fixture.text(label))
            assertNull("The View button must not attach to a different notification", fixture.text(tr("查看")))
            fixture.scope.advanceTimeBy(5_100L)
            fixture.frames(30)
            assertNull(fixture.title.value)
            assertNotNull("The queued update should follow the title notice", fixture.text(label))
            assertNotNull(fixture.text(tr("查看")))
            assertNotNull(fixture.check.notice.value)
            fixture.scope.advanceTimeBy(5_100L)
            fixture.frames(30)
            assertNull(fixture.check.notice.value)
        }
    }

    private fun availableUpdate() = UpdateCheckResult.Available(
        GitHubUpdate("1.0.0", "$IRIO_PROJECT_URL/releases/tag/1.0.0", false),
    )

    @Test fun viewActionOpensTheMarkdownUpdateDialogAndCloseDoesNotRepeatTheNotice() = runTest {
        withUi { fixture ->
            val release = GitHubUpdate("1.0.0", "$IRIO_PROJECT_URL/releases/tag/1.0.0", false,
                "## Startup release notes\n\nA **new feature** is available.")
            fixture.check.start { UpdateCheckResult.Available(release) }
            fixture.frames(30)
            assertNotNull(fixture.text(tr("发现新版本：%s").format(release.tag)))
            assertNull("The update dialog must wait for the View action", fixture.text("Startup release notes"))
            fixture.click(requireNotNull(fixture.text(tr("查看"))))
            fixture.frames(30)
            assertNotNull("The action must open the existing Markdown update dialog", fixture.text("Startup release notes"))
            assertNotNull(fixture.text(tr("前往下载")))
            val close = fixture.text(if (ThemeStore.language == AppLanguage.EN) "Close" else "关闭")
            assertNotNull(close)
            assertNull("Viewing the update consumes its pending notification", fixture.check.notice.value)
            fixture.click(requireNotNull(close))
            fixture.frames(30)
            assertNull(fixture.text("Startup release notes"))
            assertNull(fixture.text(tr("查看")))
            assertNull(fixture.text(tr("发现新版本：%s").format(release.tag)))
        }
    }

    @Test fun downloadFromTheStartupDialogOpensTheMatchingReleasePage() = runTest {
        withUi { fixture ->
            val release = GitHubUpdate("1.1.0-beta.2", "$IRIO_PROJECT_URL/releases/tag/1.1.0-beta.2", true)
            fixture.check.start { UpdateCheckResult.Available(release) }
            fixture.frames(30)
            fixture.click(requireNotNull(fixture.text(tr("查看"))))
            fixture.frames(30)
            fixture.click(requireNotNull(fixture.text(tr("前往下载"))))
            fixture.frames(30)
            assertEquals(release.url, shadowOf(fixture.view.context as ComponentActivity).nextStartedActivity.dataString)
            assertNull(fixture.check.notice.value)
        }
    }

    private suspend fun TestScope.withUi(check: (Fixture) -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(controller.get())
        val fixture = Fixture(this, view, clock, StartupUpdateCheck(this), { controller.pause() }, { controller.resume() })
        try {
            view.setParentCompositionContext(recomposer)
            controller.get().setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme {
                        Box(Modifier.fillMaxSize()) {
                            ChatNoticeSnackbar(titleNotice = fixture.title.value, titleNoticeNonce = 0,
                                onDismissTitle = { fixture.title.value = null }, bottomInset = 24.dp,
                                startupCheck = fixture.check)
                        }
                    }
                }
            }
            fixture.frames()
            check(fixture)
        } finally {
            view.disposeComposition(); recomposer.close(); runner.cancel()
            controller.pause().stop().destroy()
        }
    }

    private class Fixture(
        val scope: TestScope,
        val view: ComposeView,
        val clock: BroadcastFrameClock,
        val check: StartupUpdateCheck,
        val pause: () -> Unit,
        val resume: () -> Unit,
    ) {
        val title = mutableStateOf<String?>(null)
        private var time = 0L
        fun frames(count: Int = 8) = repeat(count) {
            Snapshot.sendApplyNotifications()
            scope.advanceTimeBy(16L); scope.runCurrent()
            time += 16_000_000L; clock.sendFrame(time); scope.runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
            view.requestLayout()
            view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 360, 640)
        }
        fun text(value: String): SemanticsNode? {
            fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
            fun roots(view: View): List<ViewRootForTest> =
                (if (view is ViewRootForTest) listOf(view) else emptyList()) +
                    (if (view is ViewGroup) (0 until view.childCount).flatMap { roots(view.getChildAt(it)) } else emptyList())
            val globalClass = Class.forName("android.view.WindowManagerGlobal")
            val global = globalClass.getMethod("getInstance").invoke(null)
            val views = globalClass.getDeclaredField("mViews").apply { isAccessible = true }.get(global) as List<*>
            return (views.filterIsInstance<View>() + view).flatMap(::roots).distinct().flatMap {
                descend(it.semanticsOwner.unmergedRootSemanticsNode)
            }.firstOrNull {
                it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { text -> text.text == value }
            }
        }
        fun click(target: SemanticsNode) {
            var node: SemanticsNode? = target
            while (node != null && !node.config.contains(SemanticsActions.OnClick)) node = node.parent
            assertNotNull(node)
            assertTrue(node!!.config[SemanticsActions.OnClick].action!!.invoke())
        }
    }
}
