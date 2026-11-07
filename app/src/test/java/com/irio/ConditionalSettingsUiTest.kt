package com.irio

import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConditionalSettingsUiTest {
    @Test fun appearanceParametersFollowTheirOwnSwitchAndRetainValues() = runTest {
        withScreen(prepare = { context ->
            ThemeStore.ensureLoaded(context)
            ThemeStore.saveLanguage(context, AppLanguage.ZH_CN)
            ThemeStore.saveAutoRetry(context, false)
            ThemeStore.saveRetryLimit(context, 7)
            ThemeStore.saveRetryCooldown(context, 23)
            ThemeStore.saveLongTextPaste(context, false)
            ThemeStore.saveLongTextPasteThreshold(context, 3000)
            ThemeStore.saveGlassEnabled(context, false)
            GlassSurface.entries.forEach {
                ThemeStore.saveGlassSurface(context, it, it == GlassSurface.COMPOSER)
            }
            ThemeStore.saveImageUploadCompression(context, false)
            ThemeStore.saveImageUploadQuality(context, 75)
            ThemeStore.saveImageUploadFormat(context, ImageUploadFormat.JPEG)
        }, content = { AppearanceSettingsScreen(onBack = {}) }) { _ ->
            val groups = listOf(
                "自动重试" to listOf("重试次数上限", "重试冷却时长"),
                "粘贴长文本为文件" to listOf("字符阈值"),
                "毛玻璃" to listOf("侧边栏按钮", "消息框、语音框"),
                "图片压缩与转换" to listOf("转换格式", "质量"),
            )
            groups.forEach { (_, children) -> children.forEach { assertHidden(it) } }
            assertTrue("Independent disclosure arrows must be removed", nodes().none {
                it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.any { label -> label == "展开" || label == "收起" }
            })
            for ((title, children) in groups) {
                toggle(title)
                frames(45)
                children.forEach { assertVisible(it) }
                groups.filter { it.first != title }.flatMap { it.second }.forEach { assertHidden(it) }
                toggle(title)
                frames(45)
                children.forEach { assertHidden(it) }
                toggle(title)
                frames(45)
                children.forEach { assertVisible(it) }
                toggle(title)
                frames(45)
            }
            assertEquals(7, ThemeStore.retryLimit)
            assertEquals(23, ThemeStore.retryCooldownSeconds)
            assertEquals(3000, ThemeStore.longTextPasteThreshold)
            assertEquals(75, ThemeStore.imageUploadQuality)
            assertEquals(ImageUploadFormat.JPEG, ThemeStore.imageUploadFormat)
            assertEquals(setOf(GlassSurface.COMPOSER), ThemeStore.glassSurfaces)
        }
    }

    @Test fun searchForDisabledChildrenLocatesTheMasterWithoutEnablingFeatures() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val context = controller.get()
        val restore = preserveSettings(context)
        try {
            ThemeStore.ensureLoaded(context)
            ThemeStore.saveAutoRetry(context, false)
            ThemeStore.saveLongTextPaste(context, false)
            ThemeStore.saveGlassEnabled(context, false)
            ThemeStore.saveImageUploadCompression(context, false)
            ToolStore.saveContextCompression(context, false)
            ToolStore.saveSubagents(context, false)
            val cases = listOf(
                Triple("appearance.retry.limit", "重试次数上限", "appearance.retry"),
                Triple("appearance.retry.cooldown", "重试冷却时长", "appearance.retry"),
                Triple("appearance.paste.threshold", "字符阈值", "appearance.paste"),
                Triple("appearance.glass.sidebar", "侧边栏按钮", "appearance.glass"),
                Triple("appearance.glass.ring", "上下文圆环", "appearance.glass"),
                Triple("appearance.glass.switcher", "模型切换器", "appearance.glass"),
                Triple("appearance.glass.composer", "消息框、语音框", "appearance.glass"),
                Triple("appearance.imageCompression.format", "转换格式", "appearance.imageCompression"),
                Triple("appearance.imageCompression.quality", "图片压缩质量", "appearance.imageCompression"),
                Triple("model.compressionModel", "压缩模型", "model.compression"),
                Triple("model.trigger", "触发时机", "model.compression"),
                Triple("model.target", "压缩目标", "model.compression"),
                Triple("model.visualize", "可视化上下文", "model.compression"),
                Triple("model.subagentModel", "子智能体默认模型", "model.subagents"),
            )
            for ((id, query, parent) in cases) {
                val entry = searchSettings(query, label = { it }).single { it.id == id }
                context.openSettingsSearch(entry)
                val intent = shadowOf(context).nextStartedActivity
                assertEquals("Disabled $id must locate the parent switch", parent,
                    intent.getStringExtra(SETTINGS_SEARCH_TARGET))
            }
            assertFalse(ThemeStore.autoRetry)
            assertFalse(ThemeStore.longTextPaste)
            assertFalse(ThemeStore.glassEnabled)
            assertFalse(ThemeStore.imageUploadCompression)
            assertFalse(ToolStore.loadContextCompression(context))
            assertFalse(ToolStore.loadSubagents(context))
            ThemeStore.saveAutoRetry(context, true)
            val hit = searchSettings("重试次数上限", label = { it }).single { it.id == "appearance.retry.limit" }
            context.openSettingsSearch(hit)
            assertEquals(hit.anchor, shadowOf(context).nextStartedActivity.getStringExtra(SETTINGS_SEARCH_TARGET))
        } finally {
            restore()
            controller.pause().stop().destroy()
        }
    }

    @Test fun compressionParametersFollowTheSwitchAndRetainValues() = runTest {
        withScreen(prepare = { context ->
            ThemeStore.ensureLoaded(context)
            ThemeStore.saveLanguage(context, AppLanguage.ZH_CN)
            ToolStore.saveContextCompression(context, false)
            ToolStore.saveCompressionTrigger(context, 81)
            ToolStore.saveCompressionTarget(context, 19)
            ToolStore.saveVisualizeContext(context, true)
            ToolStore.saveCompressionModel(context, "test-compression-model")
        }, content = { ModelSettingsScreen(onBack = {}) }) { context ->
            val children = listOf("压缩模型", "触发时机", "压缩目标", "可视化上下文")
            children.forEach { assertHidden(it) }
            toggle("上下文压缩")
            frames(45)
            children.forEach { assertVisible(it) }
            assertTrue(ToolStore.loadContextCompression(context))
            toggle("上下文压缩")
            frames(45)
            children.forEach { assertHidden(it) }
            assertFalse(ToolStore.loadContextCompression(context))
            toggle("上下文压缩")
            frames(45)
            children.forEach { assertVisible(it) }
            assertEquals(81, ToolStore.loadCompressionTrigger(context))
            assertEquals(19, ToolStore.loadCompressionTarget(context))
            assertTrue(ToolStore.loadVisualizeContext(context))
            assertEquals("test-compression-model", ToolStore.loadCompressionModel(context))
        }
    }

    @Test fun subagentModelFollowsTheSwitchAndRetainsSelection() = runTest {
        withScreen(prepare = { context ->
            ThemeStore.saveLanguage(context, AppLanguage.ZH_CN)
            ToolStore.saveSubagents(context, false)
            ToolStore.saveSubagentModel(context, "test-subagent-model")
        }, content = { ModelSettingsScreen(onBack = {}) }) { context ->
            assertHidden("子智能体默认模型")
            toggle("子智能体")
            frames(45)
            assertVisible("子智能体默认模型")
            assertTrue(ToolStore.loadSubagents(context))
            val hit = searchSettings("子智能体默认模型", label = { it }).single { it.id == "model.subagentModel" }
            context.openSettingsSearch(hit)
            assertEquals(hit.anchor, shadowOf(context).nextStartedActivity.getStringExtra(SETTINGS_SEARCH_TARGET))
            toggle("子智能体")
            frames(45)
            assertHidden("子智能体默认模型")
            assertFalse(ToolStore.loadSubagents(context))
            assertEquals("test-subagent-model", ToolStore.loadSubagentModel(context))
            toggle("子智能体")
            frames(45)
            assertVisible("子智能体默认模型")
            assertEquals("test-subagent-model", ToolStore.loadSubagentModel(context))
        }
    }

    @Test fun uninitializedSandboxHidesDeletion() = runTest {
        withScreen(prepare = { context ->
            prepareSandbox(context, enabled = false)
            val layout = LinuxSandboxLayout.of(context.filesDir)
            assertTrue(layout.versionFile.delete())
        }, content = { LinuxSandboxSettingsScreen(onBack = {}) }) { context ->
            assertVisible("启用 Linux 沙盒")
            assertHidden("删除 Linux 沙盒")
            val delete = searchSettings("删除 Linux 沙盒", label = { it }).single { it.id == "linux.delete" }
            context.openSettingsSearch(delete)
            assertEquals("linux.enabled", shadowOf(context).nextStartedActivity.getStringExtra(SETTINGS_SEARCH_TARGET))
        }
    }

    @Test fun disabledSandboxHidesOptionsButKeepsDeletionAvailable() = runTest {
        withScreen(prepare = { context ->
            prepareSandbox(context, enabled = false)
        }, content = { LinuxSandboxSettingsScreen(onBack = {}) }) { context ->
            listOf("单次命令数", "后台命令", "附带上传文件路径", "授予权限", "用安装包里的版本重新准备")
                .forEach { assertHidden(it) }
            assertVisible("启用 Linux 沙盒")
            assertVisible("删除 Linux 沙盒")
            assertHidden("运行环境")
            val child = searchSettings("单次命令数", label = { it }).single { it.id == "linux.commands" }
            context.openSettingsSearch(child)
            assertEquals("linux.enabled", shadowOf(context).nextStartedActivity.getStringExtra(SETTINGS_SEARCH_TARGET))
            val delete = searchSettings("删除 Linux 沙盒", label = { it }).single { it.id == "linux.delete" }
            context.openSettingsSearch(delete)
            assertEquals("linux.delete", shadowOf(context).nextStartedActivity.getStringExtra(SETTINGS_SEARCH_TARGET))
        }
    }

    @Test fun disablingSandboxHidesOptionsImmediatelyWithoutDeletingDataOrPreferences() = runTest {
        withScreen(prepare = { context ->
            prepareSandbox(context, enabled = true)
        }, content = { LinuxSandboxSettingsScreen(onBack = {}) }) { context ->
            listOf("单次命令数", "后台命令", "附带上传文件路径", "授予权限")
                .forEach { assertVisible(it) }
            assertVisible("命令")
            assertHidden("命令执行")
            assertHidden("后台运行")
            val expandedDeleteTop = top("删除 Linux 沙盒")
            toggle("启用 Linux 沙盒")
            frames(1)
            listOf("单次命令数", "后台命令", "附带上传文件路径", "授予权限", "用安装包里的版本重新准备")
                .forEach { assertHidden(it) }
            assertVisible("删除 Linux 沙盒")
            assertHidden("命令")
            val collapsedDeleteTop = top("删除 Linux 沙盒")
            assertTrue(collapsedDeleteTop < expandedDeleteTop)
            frames(45)
            assertEquals("No collapse animation may move the delete button later", collapsedDeleteTop,
                top("删除 Linux 沙盒"), 0.01f)
            val layout = LinuxSandboxLayout.of(context.filesDir)
            assertFalse(LinuxSandboxStore.loadEnabled(layout.stateFile))
            assertEquals(17, LinuxSandboxStore.loadCommandsPerCall(layout.stateFile))
            assertTrue(LinuxSandboxStore.loadBackgroundEnabled(layout.stateFile))
            assertTrue(LinuxSandboxStore.loadAttachmentPaths(layout.stateFile))
            assertTrue(layout.rootfs.isDirectory)
            assertEquals("fixture-version", layout.installedVersion())
        }
    }

    @Test fun linkConnectionHintIsOnlyVisibleWhileEnabled() = runTest {
        val enabled = androidx.compose.runtime.mutableStateOf(false)
        withScreen(prepare = { context ->
            ThemeStore.saveLanguage(context, AppLanguage.ZH_CN)
        }, content = { LinkConnectionHint(enabled.value) }) { _ ->
            val hint = "两端必须在同一 Wi-Fi。已发送的文件不会同步。"
            assertHidden(hint)
            enabled.value = true
            frames(1)
            assertVisible(hint)
            enabled.value = false
            frames(1)
            assertHidden(hint)
        }
    }

    @Test fun providerHeadersUseTheSharedSectionHeading() = runTest {
        withScreen(prepare = { context ->
            ThemeStore.saveLanguage(context, AppLanguage.ZH_CN)
        }, content = { ProviderHeadersEditor(rememberProviderHeadersState()) }) { _ ->
            val heading = nodes().single { node ->
                node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == "自定义请求头" }
            }
            assertTrue("Custom headers must use an accessible section heading",
                heading.config.contains(SemanticsProperties.Heading))
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            heading.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
            assertEquals(13f, layouts.single().layoutInput.style.fontSize.value, 0f)
            assertEquals(18f, layouts.single().layoutInput.style.lineHeight.value, 0f)
            assertVisible("添加请求头")
        }
    }

    @Test fun importAndExportShareTheDataGroup() = runTest {
        withScreen(prepare = { context ->
            ThemeStore.saveLanguage(context, AppLanguage.ZH_CN)
        }, content = { OtherSettingsScreen(onBack = {}) }) { _ ->
            assertVisible("数据")
            assertVisible("导出数据")
            assertVisible("导入数据")
            assertHidden("数据备份")
            assertHidden("数据恢复")
            val title = nodes().single { node ->
                node.config.contains(SemanticsProperties.Heading) &&
                    node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == "数据" }
            }
            assertTrue(title.boundsInRoot.bottom < top("导出数据"))
            assertTrue(top("导出数据") < top("导入数据"))
            fun action(label: String): SemanticsNode {
                var node = nodes().single { candidate ->
                    candidate.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == label }
                }
                while (!node.config.contains(SemanticsActions.OnClick)) node = requireNotNull(node.parent)
                return node
            }
            val exportRow = action("导出数据").boundsInRoot
            val importRow = action("导入数据").boundsInRoot
            assertEquals("Export must keep the normal settings row height", 52f, exportRow.height, 0.5f)
            assertEquals("Import must keep the normal settings row height", 52f, importRow.height, 0.5f)
            assertEquals("Action rows must fill the card rather than shrink inside its padding", 16f, exportRow.left, 0.5f)
            assertEquals(exportRow.bottom, importRow.top, 0.5f)
            assertVisible("历史记录")
        }
    }

    private fun prepareSandbox(context: ComponentActivity, enabled: Boolean) {
        ThemeStore.saveLanguage(context, AppLanguage.ZH_CN)
        ShadowEnvironment.addExternalDir(context.cacheDir.resolve("external").absolutePath)
        shadowOf(context.getSystemService(android.app.AppOpsManager::class.java))
            .setMode("android:manage_external_storage", android.os.Process.myUid(), context.packageName,
                android.app.AppOpsManager.MODE_ERRORED)
        assertFalse("This fixture must not prepare or execute a real sandbox", LinuxSandboxAccess.hasAllFiles())
        val layout = LinuxSandboxLayout.of(context.filesDir)
        layout.rootfs.mkdirs()
        layout.versionFile.writeText("fixture-version")
        LinuxSandboxStore.saveEnabled(layout.stateFile, enabled)
        LinuxSandboxStore.saveCommandsPerCall(layout.stateFile, 17)
        LinuxSandboxStore.saveBackgroundEnabled(layout.stateFile, true)
        LinuxSandboxStore.saveAttachmentPaths(layout.stateFile, true)
    }

    private suspend fun TestScope.withScreen(
        prepare: (ComponentActivity) -> Unit,
        content: @Composable () -> Unit,
        check: suspend Harness.(ComponentActivity) -> Unit,
    ) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val context = controller.get()
        val restore = preserveSettings(context)
        prepare(context)
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(context)
        try {
            view.setParentCompositionContext(recomposer)
            context.setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme { content() }
                }
            }
            val harness = Harness(this, view, clock)
            harness.frames(45)
            harness.check(context)
        } finally {
            view.disposeComposition()
            recomposer.close()
            runner.cancel()
            restore()
            controller.pause().stop().destroy()
        }
    }

    private fun preserveSettings(context: ComponentActivity): () -> Unit {
        ThemeStore.ensureLoaded(context)
        val language = ThemeStore.language
        val retry = ThemeStore.retryOptions()
        val paste = ThemeStore.longTextPaste
        val threshold = ThemeStore.longTextPasteThreshold
        val glass = ThemeStore.glassEnabled
        val surfaces = ThemeStore.glassSurfaces.toSet()
        val image = ThemeStore.imageUploadOptions()
        val compression = ToolStore.loadContextCompression(context)
        val trigger = ToolStore.loadCompressionTrigger(context)
        val target = ToolStore.loadCompressionTarget(context)
        val visualize = ToolStore.loadVisualizeContext(context)
        val model = ToolStore.loadCompressionModel(context)
        val subagents = ToolStore.loadSubagents(context)
        val subagentModel = ToolStore.loadSubagentModel(context)
        return {
            ThemeStore.saveLanguage(context, language)
            ThemeStore.saveAutoRetry(context, retry.enabled)
            ThemeStore.saveRetryLimit(context, retry.maxRetries)
            ThemeStore.saveRetryCooldown(context, retry.cooldownSeconds)
            ThemeStore.saveLongTextPaste(context, paste)
            ThemeStore.saveLongTextPasteThreshold(context, threshold)
            ThemeStore.saveGlassEnabled(context, glass)
            GlassSurface.entries.forEach { ThemeStore.saveGlassSurface(context, it, it in surfaces) }
            ThemeStore.saveImageUploadCompression(context, image.enabled)
            ThemeStore.saveImageUploadQuality(context, image.quality)
            ThemeStore.saveImageUploadFormat(context, image.format)
            ToolStore.saveContextCompression(context, compression)
            ToolStore.saveCompressionTrigger(context, trigger)
            ToolStore.saveCompressionTarget(context, target)
            ToolStore.saveVisualizeContext(context, visualize)
            ToolStore.saveCompressionModel(context, model)
            ToolStore.saveSubagents(context, subagents)
            ToolStore.saveSubagentModel(context, subagentModel)
        }
    }

    private class Harness(val scope: TestScope, val view: ComposeView, val clock: BroadcastFrameClock) {
        private var time = 0L
        fun frames(count: Int) {
            repeat(count) {
                shadowOf(Looper.getMainLooper()).idle()
                Snapshot.sendApplyNotifications()
                scope.runCurrent()
                clock.sendFrame(time.also { time += 16_000_000L })
                scope.runCurrent()
                view.requestLayout()
                view.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(12000, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, 400, 12000)
            }
        }
        fun nodes(): List<SemanticsNode> {
            fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
            return descend((view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
        }
        private fun text(label: String) = nodes().filter {
            it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { value -> value.text == label }
        }
        fun assertHidden(label: String) = assertTrue("Disabled setting must hide $label", text(label).isEmpty())
        fun assertVisible(label: String) = assertTrue("Enabled setting must show $label", text(label).isNotEmpty())
        fun top(label: String) = text(label).single().boundsInRoot.top
        fun toggle(label: String) {
            val title = text(label).single()
            val toggle = nodes().filter { it.config.contains(SemanticsProperties.ToggleableState) }
                .minBy { abs(it.boundsInRoot.center.y - title.boundsInRoot.center.y) }
            assertEquals("Switch must share the title row: $label", title.boundsInRoot.center.y, toggle.boundsInRoot.center.y, 8f)
            assertTrue(toggle.config[SemanticsActions.OnClick].action!!.invoke())
        }
    }
}
