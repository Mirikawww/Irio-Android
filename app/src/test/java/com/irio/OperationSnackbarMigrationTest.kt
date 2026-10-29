package com.irio

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.MotionEvent
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.composables.ui.components.Text
import com.composables.ui.components.rememberBottomSheetState
import com.composables.ui.components.BottomSheetDetent
import com.composables.ui.theme.colors
import com.composables.ui.theme.onPrimaryColor
import com.composables.ui.theme.onPanelColor
import com.composeunstyled.theme.Theme
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowWindowManagerImpl

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OperationSnackbarMigrationTest {
    @Test fun docsMcpManagementMatchesPeerSheetsAndPreservesEnablementOnOpen() = runTest {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        org.robolectric.shadows.ShadowEnvironment.addExternalDir(app.cacheDir.resolve("external").absolutePath)
        IrioDocsMcpStore.saveEnabled(app, true)
        withUi { McpSettingsScreen(onBack = {}) }.use { ui ->
            ui.frames(40)
            val card = ui.all().single { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "irio-docs-mcp" }
            assertFalse("List card must not expose an inline switch", ui.descend(card).any { it.config.contains(SemanticsProperties.ToggleableState) })
            val manage = ui.descend(card).single { node -> node.config.contains(SemanticsActions.OnClick) && ui.descend(node).any {
                it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { text -> text.text == tr("管理") }
            } }
            assertTrue("Same compact management action as peers", manage.config[SemanticsActions.OnClick].action!!.invoke())
            ui.frames(40)
            assertTrue("Opening management must not disable tools", IrioDocsMcpStore.loadEnabled(app))
            assertTrue(ui.hasText(tr("信息")))
            assertTrue(ui.hasText(tr("开发者")))
            assertTrue(ui.hasText("Pirikawww"))
            assertTrue(ui.hasText("irio.mirikawww.workers.dev"))
            assertFalse("Do not add an access-permission information row", ui.hasText(tr("访问权限")))
            assertTrue(ui.hasText(tr("工具数量")))
            assertTrue(ui.hasText("3"))
            assertTrue(ui.hasText(tr("移除集成")))
            val toggle = ui.all().single { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "irio-docs-mcp-enabled" }
            assertTrue(toggle.config[SemanticsActions.OnClick].action!!.invoke()); ui.frames(10)
            assertFalse(IrioDocsMcpStore.loadEnabled(app))
            assertFalse(chatToolRegistry(app, false, "").handles("irio_docs_mcp_search_irio_docs"))
            assertTrue(toggle.config[SemanticsActions.OnClick].action!!.invoke()); ui.frames(10)
            assertTrue(IrioDocsMcpStore.loadEnabled(app))
            assertTrue(chatToolRegistry(app, false, "").handles("irio_docs_mcp_search_irio_docs"))
        }
        withUi { McpSettingsScreen(onBack = {}) }.use { ui ->
            ui.frames(40)
            val card = ui.all().single { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "irio-docs-mcp" }
            assertTrue(ui.descend(card).any { it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { text -> text.text == tr("管理") } })
            assertTrue(IrioDocsMcpStore.loadEnabled(app))
        }
        assertTrue(searchSettings("Irio Docs").any { it.id == "mcp.irio_docs" })
    }

    @Test fun docsMcpRemovalAndAnonymousReinstallUseTheSameManagementHierarchy() = runTest {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        org.robolectric.shadows.ShadowEnvironment.addExternalDir(app.cacheDir.resolve("external").absolutePath)
        IrioDocsMcpStore.install(app)
        withUi { McpSettingsScreen(onBack = {}) }.use { ui ->
            ui.frames(40)
            fun card() = ui.all().single { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "irio-docs-mcp" }
            fun open(label: String) {
                val action = ui.descend(card()).single { node -> node.config.contains(SemanticsActions.OnClick) && ui.descend(node).any {
                    it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { text -> text.text == tr(label) }
                } }
                assertTrue(action.config[SemanticsActions.OnClick].action!!.invoke()); ui.frames(40)
            }
            open("管理")
            ui.click(tr("移除集成")); ui.frames(40)
            assertFalse(IrioDocsMcpStore.loadInstalled(app))
            assertFalse(IrioDocsMcpStore.loadEnabled(app))
            assertFalse(chatToolRegistry(app, false, "").handles("irio_docs_mcp_search_irio_docs"))
            open("安装")
            assertTrue(ui.hasText(tr("开发者")))
            assertFalse(ui.hasText(tr("移除集成")))
            assertFalse(ui.hasText(tr("使用 OAuth 登录")))
            assertFalse(ui.hasText("Personal Access Token"))
            assertFalse("Opening install details must not install implicitly", IrioDocsMcpStore.loadInstalled(app))
            // Target the detail action, not an Install button behind the modal.
            val install = ui.all().single { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "irio-docs-mcp-install" }
            assertTrue(install.config[SemanticsActions.OnClick].action!!.invoke()); ui.frames(40)
            assertTrue(IrioDocsMcpStore.loadInstalled(app))
            assertTrue(IrioDocsMcpStore.loadEnabled(app))
            assertTrue(ui.hasText(tr("工具数量")))
            assertTrue(ui.hasText(tr("移除集成")))
        }
    }

    @Test fun manualModelDeleteActionDoesNotIncreaseTheModelRowHeight() = runTest {
        withUi {
            ProviderDetailScreen("fixture", {}, {}, loadProvider = { _, _ ->
                ProviderConfig("fixture", "Fixture", "https://example.test", "",
                    models = listOf("remote", "manual"), customModels = listOf("manual"))
            })
        }.use { ui ->
            ui.frames(40)
            ui.click(tr("模型")); ui.frames(40)
            fun row(id: String) = ui.all().single {
                it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "provider-model:$id"
            }
            val remote = row("remote").boundsInRoot
            val manual = row("manual").boundsInRoot
            assertEquals("The normal model row must retain the established compact height", 52f, remote.height, 0.5f)
            assertEquals("Adding a delete action must not make a custom model row taller", remote.height, manual.height, 0.5f)
            val delete = ui.descend(row("manual")).first { node ->
                node.config.contains(SemanticsActions.OnClick) && ui.descend(node).any { child ->
                    child.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
                        .contains(tr("删除模型 %s").format("manual"))
                }
            }
            assertTrue("Keep a usable delete touch target", delete.boundsInRoot.height >= 48f)
            val icon = ui.descend(delete).single { node ->
                node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
                    .contains(tr("删除模型 %s").format("manual"))
            }
            assertEquals("The delete glyph must stay compact inside its touch target", 20f, icon.boundsInRoot.width, 0.5f)
            assertEquals(20f, icon.boundsInRoot.height, 0.5f)
        }
    }

    @Test fun customModelButtonStaysAboveTabsButMovesWithTheModelPageLikeRefresh() = runTest {
        withUi {
            ProviderDetailScreen("fixture", {}, {}, loadProvider = { _, _ ->
                ProviderConfig("fixture", "Fixture", "https://example.test", "", models = List(30) { "model-$it" })
            })
        }.use { ui ->
            ui.frames(40)
            ui.click(tr("模型")); ui.frames(40)
            val before = ui.text(tr("添加模型")).boundsInRoot
            assertTrue("Entry must be visible directly above tabs without scrolling the model list",
                before.height > 1f && before.bottom < ui.text(tr("模型")).boundsInRoot.top)
            var addButton: SemanticsNode? = ui.text(tr("添加模型"))
            while (addButton != null && !addButton.config.contains(SemanticsActions.OnClick)) addButton = addButton.parent
            var tab: SemanticsNode? = ui.text(tr("模型"))
            while (tab != null && !tab.config.contains(SemanticsActions.OnClick)) tab = tab.parent
            assertEquals("Do not count the shared haze bleed as extra spacing between the action and tabs",
                16f, tab!!.boundsInRoot.top - addButton!!.boundsInRoot.bottom, 1f)
            var scroll: SemanticsNode? = ui.text("model-0")
            while (scroll != null && !scroll.config.contains(SemanticsProperties.VerticalScrollAxisRange)) scroll = scroll.parent
            assertNotNull(scroll)
            assertTrue(scroll!!.config[SemanticsActions.ScrollBy].action!!.invoke(0f, 100_000f))
            ui.frames(40)
            val entry = ui.text(tr("添加模型"))
            assertEquals("List scrolling must not move the page's bottom action", before.top, entry.boundsInRoot.top, 1f)
            val addStart = entry.positionInRoot.x
            val refreshStart = ui.text(tr("刷新模型")).positionInRoot.x
            ui.click(tr("配置"))
            val frames = (0 until 40).map {
                ui.frames(1)
                val nodes = ui.all()
                fun x(label: String) = nodes.firstOrNull { node ->
                    node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == label }
                }?.positionInRoot?.x
                x(tr("添加模型"))?.minus(addStart) to x(tr("刷新模型"))?.minus(refreshStart)
            }
            val paired = frames.mapNotNull { (add, refresh) -> if (add != null && refresh != null) add to refresh else null }
            assertTrue("Both actions must ride the outgoing model page", paired.any { it.first > 5f })
            paired.forEach { (add, refresh) -> assertEquals("Add and refresh must use the same page translation", refresh, add, 1f) }
            ui.frames(40)
            val outgoing = ui.all().firstOrNull { node ->
                node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == tr("添加模型") }
            }
            assertTrue("Outgoing model action must no longer be visible: ${outgoing?.boundsInRoot}, ${outgoing?.positionInRoot}",
                outgoing == null || outgoing.boundsInRoot.width <= 1f)
        }
    }

    @Test fun customModelDialogAnimatesInAndOutAtItsStableCompositionSite() = runTest {
        withUi {
            ProviderDetailScreen("fixture", {}, {}, loadProvider = { _, _ ->
                ProviderConfig("fixture", "Fixture", "https://example.test", "")
            })
        }.use { ui ->
            ui.frames(40)
            ui.click(tr("模型")); ui.frames(40)
            repeat(2) {
                ui.click(tr("添加模型"))
                val widths = (0 until 40).map {
                    ui.frames(1)
                    ui.all().firstOrNull { node ->
                        node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == tr("取消") }
                    }?.boundsInRoot?.width ?: 0f
                }
                val settled = widths.last()
                assertTrue("Dialog should have an intermediate entrance frame, not appear at full size immediately",
                    widths.any { it > 1f && it < settled - 0.5f })
                ui.click(tr("取消"))
                ui.frames(1)
                assertTrue("Dialog content must remain for the exit animation", ui.hasText(tr("取消")))
                ui.frames(40)
                assertFalse(ui.hasText(tr("取消")))
            }
        }
    }

    @Test fun customModelEntryIsAboveModelTabsAndTheDialogSavesWithoutFetching() = runTest {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val keyField = ProviderStore::class.java.getDeclaredField("cachedSecretKey").apply { isAccessible = true }
        val oldKey = keyField.get(ProviderStore)
        keyField.set(ProviderStore, javax.crypto.spec.SecretKeySpec(ByteArray(32) { 7 }, "AES"))
        try {
            ProviderStore.preferences(context).edit().clear().commit()
            ProviderStore.saveProvider(context, ProviderConfig("custom-fixture", "Fixture", "https://example.test", "fixture",
                models = listOf("existing"), selectedModel = "existing"))
            var requests = 0
            withUi {
                ProviderDetailScreen("custom-fixture", {}, {}, fetchModels = {
                    requests++
                    ModelFetchResult(models = listOf("refreshed"))
                })
            }.use { ui ->
                ui.frames(40)
                assertFalse("The entry belongs only on the models page", ui.hasText(tr("添加模型")))
                ui.click(tr("模型")); ui.frames(40)
                assertTrue("Missing custom model entry", ui.hasText(tr("添加模型")))
                assertTrue("The entry must sit above the bottom tabs",
                    ui.text(tr("添加模型")).boundsInRoot.bottom < ui.text(tr("模型")).boundsInRoot.top)
                ui.click(tr("添加模型")); ui.frames(40)
                ui.click(tr("添加")); ui.frames(30)
                assertTrue(ui.isSnackbarText(tr("模型 ID 不能为空")))
                ui.edit(tr("模型 ID"), "  vendor/My-Model:custom  \ngpt-5.6-sol\ngpt-5.6-terra\ngpt-5.6-luna")
                ui.click(tr("添加")); ui.frames(40)
                assertFalse(ui.hasEditableText("  vendor/My-Model:custom  "))
                assertTrue(ui.hasText("vendor/My-Model:custom"))
                assertEquals(0, requests)
                val saved = ProviderStore.loadProvider(context, "custom-fixture")!!
                assertTrue(saved.enabledModels().contains("vendor/My-Model:custom"))
                assertTrue(saved.enabledModels().containsAll(listOf("gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna")))
                assertEquals("existing", saved.selectedModel)
                ui.click(tr("刷新模型"))
                ui.until { ui.hasText("refreshed") }
                assertTrue("Custom model must survive a server refresh", ui.hasText("vendor/My-Model:custom"))
                assertEquals(1, requests)
                ui.click(tr("添加模型")); ui.frames(40)
                ui.edit(tr("模型 ID"), "vendor/My-Model:custom")
                ui.click(tr("添加")); ui.frames(30)
                assertTrue(ui.isSnackbarText(tr("模型 ID 已存在")))
                assertEquals(ui.ownerOfText(tr("取消")), ui.ownerOfText(tr("模型 ID 已存在")))
                ui.edit(tr("模型 ID"), "bad model")
                ui.click(tr("添加")); ui.frames(30)
                assertTrue(ui.isSnackbarText(tr("模型 ID 不能包含空白字符")))
                ui.click(tr("取消")); ui.frames(40)
                assertFalse(ProviderStore.loadProvider(context, "custom-fixture")!!.models.contains("bad model"))
                fun deleteAction(id: String): SemanticsNode? {
                    var node = ui.all().firstOrNull { candidate ->
                        candidate.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
                            .contains(tr("删除模型 %s").format(id))
                    }
                    while (node != null && !node.config.contains(SemanticsActions.OnClick)) node = node.parent
                    return node
                }
                assertNull("Discovered models do not get a manual-delete action", deleteAction("refreshed"))
                val delete = deleteAction("gpt-5.6-sol")
                assertNotNull("A manual model needs a delete action after its switch", delete)
                val row = ui.text("gpt-5.6-sol").parent!!
                val toggle = ui.descend(row).first { it.config.contains(SemanticsProperties.ToggleableState) }
                assertTrue(delete!!.boundsInRoot.left > toggle.boundsInRoot.left)
                assertTrue(delete.config[SemanticsActions.OnClick].action!!.invoke())
                ui.frames(40)
                assertTrue("Opening confirmation must not delete", "gpt-5.6-sol" in ProviderStore.loadProvider(context, "custom-fixture")!!.models)
                assertTrue(ui.hasText(tr("确定要删除模型“%s”吗？").format("gpt-5.6-sol")))
                ui.click(tr("取消")); ui.frames(40)
                assertTrue("gpt-5.6-sol" in ProviderStore.loadProvider(context, "custom-fixture")!!.models)
                assertTrue(deleteAction("gpt-5.6-sol")!!.config[SemanticsActions.OnClick].action!!.invoke())
                ui.frames(40)
                ui.click(tr("删除")); ui.frames(40)
                val afterDelete = ProviderStore.loadProvider(context, "custom-fixture")!!
                assertFalse("gpt-5.6-sol" in afterDelete.models)
                assertFalse("gpt-5.6-sol" in afterDelete.customModels)
                assertFalse(ui.hasText("gpt-5.6-sol"))
                assertTrue(ui.hasText("gpt-5.6-terra"))
            }
        } finally {
            ProviderStore.preferences(context).edit().clear().commit()
            keyField.set(ProviderStore, oldKey)
        }
    }

    @Test fun customModelDialogUsesLocalizedControlsAndCancelDoesNotAdd() = runTest {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        ThemeStore.ensureLoaded(context)
        val previous = ThemeStore.language
        try {
            for (language in AppLanguage.entries) {
                ThemeStore.saveLanguage(context, language)
                var adds = 0
                var dismissals = 0
                withUi {
                    ProviderCustomModelDialog(visible = true, onDismiss = { dismissals++ }, onAdd = { adds++; null })
                }.use { ui ->
                    ui.frames(40)
                    assertTrue(ui.hasText(if (language == AppLanguage.EN) "Add model" else "添加模型"))
                    ui.edit(tr("模型 ID"), "draft-id")
                    val cancel = ui.text(tr("取消")).boundsInRoot
                    val add = ui.text(tr("添加")).boundsInRoot
                    assertEquals("Dialog actions remain on one row", cancel.center.y, add.center.y, 1f)
                    assertTrue(cancel.right < add.left)
                    ui.click(tr("取消"))
                    assertEquals(1, dismissals)
                    assertEquals(0, adds)
                }
            }
        } finally {
            ThemeStore.saveLanguage(context, previous)
        }
    }

    @Test fun providerErrorsCanBeShownAgainAfterSwipingOnThePageAndInTheDialog() = runTest {
        for (inDialog in listOf(false, true)) {
            var requests = 0
            val message = "Repeated provider failure"
            withUi {
                if (inDialog) ProviderSettingsScreen(onBack = {}, loadProviders = { emptyList() },
                    fetchModels = { requests++; ModelFetchResult(error = message) })
                else ProviderDetailScreen("fixture", {}, {},
                    loadProvider = { _, _ -> ProviderConfig("fixture", "Fixture", "https://example.test", "") },
                    fetchModels = { requests++; ModelFetchResult(error = message) })
            }.use { ui ->
                ui.frames(30)
                if (inDialog) {
                    ui.click(tr("添加提供商")); ui.frames(40)
                    ui.edit(tr("名称"), "Draft fixture")
                    ui.edit(tr("端点 URL"), "https://example.test")
                }
                for (direction in listOf(-1f, 1f)) {
                    ui.click(tr("保存"))
                    ui.until { ui.hasText(message) }
                    ui.frames(30)
                    assertTrue(ui.isSnackbarText(message))
                    val before = ui.text(message).boundsInRoot
                    ui.swipeText(message, direction)
                    ui.frames(60)
                    assertFalse("Swiped feedback must leave its owning surface", ui.hasText(message))
                    ui.click(tr("保存"))
                    ui.until { ui.hasText(message) }
                    ui.frames(40)
                    val after = ui.text(message).boundsInRoot
                    assertEquals("Repeated feedback must return to the same visible position", before.left, after.left, 1f)
                    assertEquals(before.width, after.width, 1f)
                    assertTrue(ui.isSnackbarText(message))
                    if (inDialog) assertEquals(ui.ownerOfText(tr("取消")), ui.ownerOfText(message))
                }
                assertEquals(4, requests)
            }
        }
    }

    @Test fun toolPrerequisiteNoticeStillShowsAfterASwipeAndAnotherTap() = runTest {
        val entry = ToolSettingsEntry(MemoryTool.GET, "读取记忆", "Read memory", ToolBlockedReason.MEMORY)
        val message = entry.blockedReason!!.message()
        withUi { ToolSettingsScreen(onBack = {}, loadTools = { listOf(entry) }) }.use { ui ->
            ui.frames(40)
            fun trigger() {
                val node = ui.all().single { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "tool-expand:${entry.name}" }
                assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
                ui.frames(40)
            }
            trigger()
            val initial = ui.text(message).boundsInRoot
            ui.swipeText(message, 1f)
            ui.frames(60)
            assertFalse(ui.hasText(message))
            trigger()
            assertTrue(ui.isSnackbarText(message))
            assertEquals(initial.left, ui.text(message).boundsInRoot.left, 1f)
            assertEquals(initial.width, ui.text(message).boundsInRoot.width, 1f)
        }
    }

    @Test fun sharedSnackbarDoesNotClearItsTextWhileExiting() = runTest {
        val visible = mutableStateOf(false)
        val message = mutableStateOf("")
        withUi {
            Box(Modifier.fillMaxSize()) {
                IrioSnackbar(visible.value, message.value, 0.dp, onDismiss = {})
            }
        }.use { ui ->
            ui.frames()
            message.value = "Exit fixture"; visible.value = true
            ui.frames(40)
            visible.value = false; message.value = ""
            ui.frames(4)
            assertTrue("A dismissed error must retain its original text during exit", ui.hasText("Exit fixture"))
            ui.frames(40)
            assertFalse(ui.hasText("Exit fixture"))
        }
    }

    @Test fun providerSaveFailureAndHeaderValidationAreNotInlineText() = runTest {
        val fixture = ProviderConfig("fixture", "Fixture", "https://example.test", "")
        var requests = 0
        var expected = Color.Unspecified
        withUi {
            expected = Theme[colors][onPrimaryColor]
            ProviderDetailScreen("fixture", {}, {}, loadProvider = { _, _ -> fixture },
                fetchModels = { requests++; ModelFetchResult(error = "Save failed fixture") })
        }.use { ui ->
            ui.frames(30)
            val before = ui.text(tr("保存")).boundsInRoot.top
            ui.click(tr("保存"))
            ui.until { ui.hasText("Save failed fixture") }
            assertEquals(1, requests)
            assertEquals(expected, ui.textColor("Save failed fixture"))
            assertTrue("Save error must be inside a snackbar", ui.isSnackbarText("Save failed fixture"))
            assertEquals("Error must not insert a row into the form", before, ui.text(tr("保存")).boundsInRoot.top, 0.5f)
        }
        val invalid = fixture.copy(customHeaders = listOf(ProviderHeader("invalid name", "fixture")))
        withUi {
            ProviderDetailScreen("fixture", {}, {}, loadProvider = { _, _ -> invalid },
                fetchModels = { error("Invalid headers must not trigger a network request") })
        }.use { ui ->
            ui.frames(30)
            ui.click(tr("保存"))
            ui.until { ui.hasText(tr("请求头名称无效")) }
            assertTrue(ui.isSnackbarText(tr("请求头名称无效")))
            assertTrue(ui.hasEditableText("invalid name"))
            ui.scope.advanceTimeBy(5_100); ui.frames(30)
            assertFalse(ui.hasText(tr("请求头名称无效")))
            ui.click(tr("保存"))
            ui.until { ui.hasText(tr("请求头名称无效")) }
            assertTrue("The same invalid input must notify again", ui.isSnackbarText(tr("请求头名称无效")))
        }
    }

    @Test fun addProviderFailureStaysInTheDialogAndRetainsTheDraft() = runTest {
        var requests = 0
        withUi {
            ProviderSettingsScreen(onBack = {}, loadProviders = { emptyList() },
                fetchModels = { requests++; ModelFetchResult(error = "Add failed fixture") })
        }.use { ui ->
            ui.frames(30)
            ui.click(tr("添加提供商"))
            ui.frames(40)
            ui.edit(tr("名称"), "Draft fixture")
            ui.edit(tr("端点 URL"), "https://example.test")
            ui.frames()
            ui.click(tr("保存"))
            ui.until { ui.hasText("Add failed fixture") }
            ui.frames(40)
            assertEquals(1, requests)
            assertTrue(ui.isSnackbarText("Add failed fixture"))
            assertEquals("Notice must be in the modal window, not behind it",
                ui.ownerOfText(tr("取消")), ui.ownerOfText("Add failed fixture"))
            assertTrue("The notice must sit above the dialog buttons", ui.text("Add failed fixture").boundsInRoot.bottom <= ui.text(tr("取消")).boundsInRoot.top)
            assertTrue(ui.hasEditableText("Draft fixture"))
            assertTrue(ui.hasEditableText("https://example.test"))
        }
    }

    @Test fun longDiagnosticsAreAvailableInFullAndExitRetainsTheOriginalSummary() = runTest {
        val visible = mutableStateOf(false)
        val message = mutableStateOf("")
        val full = "HTTP 403\n" + "Detailed server diagnostic. ".repeat(40)
        withUi {
            Box(Modifier.fillMaxSize()) {
                OperationErrorSnackbar(visible.value, message.value, 0.dp, onDismiss = { visible.value = false; message.value = "" })
            }
        }.use { ui ->
            ui.frames()
            message.value = full; visible.value = true
            ui.frames(40)
            assertTrue(ui.hasText("HTTP 403…"))
            assertFalse(ui.hasText(full))
            ui.click(tr("查看"))
            ui.frames(40)
            assertTrue("Full diagnostics must remain readable, not be silently truncated", ui.hasText(full))
            ui.click("关闭")
            ui.frames(40)
            visible.value = false; message.value = ""
            ui.frames(1)
            assertTrue("Exit must not replace the message with an empty string", ui.hasText("HTTP 403…"))
            ui.frames(40)
            assertFalse(ui.hasText("HTTP 403…"))
        }
    }

    @Test fun mcpLoginNoticeIsInItsAuthenticationSheetAndRepeatedErrorsRestartIt() = runTest {
        lateinit var sheets: GitHubMcpSheetState
        withUi {
            val details = rememberBottomSheetState(initialDetent = BottomSheetDetent.FullyExpanded)
            val auth = rememberBottomSheetState(initialDetent = BottomSheetDetent.FullyExpanded)
            sheets = androidx.compose.runtime.remember { GitHubMcpSheetState(details, auth) }
            ScaledBottomSheet(state = details, onDismissRequest = {}) { Text("management fixture", color = Theme[colors][onPanelColor]) }
            ScaledBottomSheet(state = auth, onDismissRequest = {}) {
                Box(Modifier.fillMaxSize()) {
                    Text("authentication fixture", color = Theme[colors][onPanelColor])
                    McpLoginErrorSnackbar(sheets)
                }
            }
        }.use { ui ->
            ui.frames(40)
            sheets.loginError = "Auth failed fixture"
            ui.frames(40)
            assertTrue(ui.isSnackbarText("Auth failed fixture"))
            assertEquals(ui.ownerOfText("authentication fixture"), ui.ownerOfText("Auth failed fixture"))
            assertNotEquals(ui.ownerOfText("management fixture"), ui.ownerOfText("Auth failed fixture"))
            assertNull(sheets.detailsError)
            ui.scope.advanceTimeBy(3_800); ui.frames(1)
            val oldNonce = sheets.loginErrorNonce
            sheets.loginError = "Auth failed fixture"
            assertTrue(sheets.loginErrorNonce > oldNonce)
            ui.frames(1)
            ui.scope.advanceTimeBy(1_500); ui.frames(1)
            assertNotNull("Repeated identical login failure restarts the timer", sheets.loginError)
            ui.scope.advanceTimeBy(3_600); ui.frames(40)
            assertNull(sheets.loginError)
        }
    }

    @Test fun linkPairingAndRuntimeErrorsShareOneNotice() {
        val notice = LinkSettingsNotice()
        notice.onStatus("Listen failed")
        val nonce = notice.nonce
        notice.onPairError("Pair failed")
        assertEquals("Pair failed", notice.message)
        assertTrue(notice.nonce > nonce)
        notice.onStatus(null)
        assertTrue("Clearing runtime status must not dismiss pairing feedback", notice.visible)
        notice.onStatus("No devices")
        assertEquals("No devices", notice.message)
        val runtimeNonce = notice.nonce
        notice.onStatus("No devices")
        assertTrue(notice.nonce > runtimeNonce)
        notice.dismiss()
        assertFalse(notice.visible)
        assertEquals("No devices", notice.message)
    }

    private fun TestScope.withUi(content: @Composable () -> Unit): Ui {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        ThemeStore.ensureLoaded(controller.get())
        val previous = ThemeStore.preference
        ThemeStore.save(controller.get(), ThemePreference.DARK)
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(controller.get())
        view.setParentCompositionContext(recomposer)
        controller.get().setContentView(view)
        view.setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) { AppTheme(content) } }
        return Ui(this, view, clock) {
            view.disposeComposition(); recomposer.close(); runner.cancel()
            ThemeStore.save(controller.get(), previous)
            controller.pause().stop().destroy()
        }
    }

    private class Ui(val scope: TestScope, val view: ComposeView, val clock: BroadcastFrameClock, val dispose: () -> Unit) : AutoCloseable {
        var time = 0L
        fun roots(): List<View> {
            val windows = shadowOf(view.context.getSystemService(WindowManager::class.java)) as ShadowWindowManagerImpl
            return (listOf(view) + windows.views).distinct()
        }
        fun frames(count: Int = 8) { repeat(count) {
            Snapshot.sendApplyNotifications(); scope.advanceTimeBy(16); scope.runCurrent()
            clock.sendFrame(time.also { time += 16_000_000L }); scope.runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
            for (root in roots()) {
                root.requestLayout()
                root.measure(View.MeasureSpec.makeMeasureSpec(420, if (root === view) View.MeasureSpec.EXACTLY else View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(1200, if (root === view) View.MeasureSpec.EXACTLY else View.MeasureSpec.AT_MOST))
                root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            }
        } }
        fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
        fun nodes(root: View): List<SemanticsNode> {
            val own = if (root is ViewRootForTest) descend(root.semanticsOwner.unmergedRootSemanticsNode) else emptyList()
            return own + if (root is ViewGroup) (0 until root.childCount).flatMap { nodes(root.getChildAt(it)) } else emptyList()
        }
        fun all() = roots().flatMap(::nodes).distinctBy { it.id }
        fun hasText(value: String) = all().any { it.hasText(value) }
        fun SemanticsNode.hasText(value: String) = config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == value }
        fun text(value: String) = all().single { it.hasText(value) }
        // A visible Activity's decor can also contain view; prefer the direct host listed first.
        fun ownerOfText(value: String) = roots().first { root -> nodes(root).any { it.hasText(value) } }
        fun isSnackbarText(value: String) = all().any { node ->
            node.config.getOrElse(SemanticsProperties.TestTag) { "" } == "irio-snackbar" && descend(node).any { it.hasText(value) }
        }
        fun textColor(value: String): Color {
            val results = mutableListOf<TextLayoutResult>()
            text(value).config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
            return results.single().layoutInput.style.color
        }
        fun swipeText(value: String, direction: Float) {
            val root = ownerOfText(value)
            val bounds = text(value).boundsInRoot
            val down = SystemClock.uptimeMillis()
            fun touch(action: Int, step: Int) {
                val event = MotionEvent.obtain(down, down + step * 16L, action,
                    bounds.center.x + direction * 300f * step.coerceAtMost(8) / 8f, bounds.center.y, 0)
                try { root.dispatchTouchEvent(event) } finally { event.recycle() }
                frames(1)
            }
            touch(MotionEvent.ACTION_DOWN, 0)
            (1..8).forEach { touch(MotionEvent.ACTION_MOVE, it) }
            touch(MotionEvent.ACTION_UP, 9)
        }
        fun click(value: String) {
            val node = all().filter { it.config.contains(SemanticsActions.OnClick) && descend(it).any { n -> n.hasText(value) } }
                .minBy { descend(it).size }
            assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
            frames(1)
        }
        fun edit(label: String, value: String) {
            val node = all().single { it.config.contains(SemanticsActions.SetText) && it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.contains(label) }
            assertTrue(node.config[SemanticsActions.SetText].action!!.invoke(androidx.compose.ui.text.AnnotatedString(value)))
            frames(1)
        }
        fun hasEditableText(value: String) = all().any { it.config.getOrElse(SemanticsProperties.EditableText) { androidx.compose.ui.text.AnnotatedString("") }.text == value }
        fun until(condition: () -> Boolean) {
            repeat(100) { if (condition()) return; frames(1); Thread.yield() }
            assertTrue("Expected operation feedback", condition())
            frames(30)
        }
        override fun close() = dispose()
    }
}
