package com.irio

import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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
import org.robolectric.shadows.ShadowWindowManagerImpl

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class MeSettingsTest {
    @Test fun profileEditingMovesFromHomepageToMeAndKeepsExistingDataAndActions() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val context = controller.get()
        ProfileStore.resetForTest(context)
        ProfileStore.ensureLoaded(context)
        ProfileStore.saveName(context, "Existing User")
        ProfileStore.saveAvatar(context, Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(context)
        val me = mutableStateOf(false)
        var time = 0L
        fun roots(): List<View> {
            val windows = shadowOf(context.getSystemService(WindowManager::class.java)) as ShadowWindowManagerImpl
            return (listOf(view) + windows.views).distinct()
        }
        fun frames(count: Int = 40) = repeat(count) {
            Snapshot.sendApplyNotifications(); advanceTimeBy(16); runCurrent()
            time += 16_000_000L; clock.sendFrame(time); runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
            roots().forEach { root ->
                root.requestLayout()
                root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, 400, 1200)
            }
        }
        fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
        fun nodes(root: View): List<SemanticsNode> {
            val own = if (root is ViewRootForTest) descend(root.semanticsOwner.unmergedRootSemanticsNode) else emptyList()
            return own + if (root is ViewGroup) (0 until root.childCount).flatMap { nodes(root.getChildAt(it)) } else emptyList()
        }
        fun all() = roots().flatMap(::nodes).distinctBy { it.id }
        fun hasText(node: SemanticsNode, value: String) = node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == value }
        fun click(value: String) {
            val button = all().filter { it.config.contains(SemanticsActions.OnClick) && descend(it).any { child -> hasText(child, value) } }
                .minBy { descend(it).size }
            assertTrue(button.config[SemanticsActions.OnClick].action!!.invoke()); frames()
        }
        fun avatar() = all().single { tr("头像") in it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() } }
        try {
            view.setParentCompositionContext(recomposer)
            context.setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme { if (me.value) MeSettingsScreen(onBack = {}) else SettingsScreen(onBack = {}) }
                }
            }
            frames()
            assertFalse("Homepage must no longer expose editable profile information", all().any { hasText(it, "Existing User") })
            assertFalse("Homepage must no longer expose the avatar action", all().any {
                tr("头像") in it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
            })
            assertTrue("Settings search must remain on the homepage", all().any { it.config.contains(SemanticsProperties.EditableText) })
            fun row(text: String): SemanticsNode {
                var node = all().single { hasText(it, tr(text)) }
                while (!node.config.contains(SemanticsActions.OnClick)) node = node.parent!!
                return node
            }
            assertEquals("Me belongs directly below Appearance and preferences", row("外观与偏好").positionInRoot.y + row("外观与偏好").size.height,
                row("我").positionInRoot.y, 0.5f)
            click(tr("我"))
            assertEquals(MeSettingsActivity::class.java.name, shadowOf(context).nextStartedActivity.component!!.className)
            me.value = true; frames()
            val nameField = all().single { it.config.contains(SemanticsProperties.EditableText) }
            assertEquals("Name must be an inline input, not a clickable label", "Existing User",
                nameField.config[SemanticsProperties.EditableText].text)
            assertNotNull(ProfileStore.avatar)
            assertEquals("Avatar must use the 96dp preview", 96f, avatar().boundsInRoot.width, 0.5f)
            assertEquals("Avatar must be horizontally centered", 200f, avatar().boundsInRoot.center.x, 0.5f)
            assertTrue("Avatar opens its action sheet", avatar().config.contains(SemanticsActions.OnClick))
            assertFalse("Actions must not be displayed on the page", all().any { hasText(it, tr("相册选择")) })
            fun openAvatarActions() {
                assertTrue(avatar().config[SemanticsActions.OnClick].action!!.invoke()); frames()
            }
            fun awaitActionsClosed() {
                repeat(120) {
                    if (all().none { hasText(it, tr("相册选择")) }) return
                    frames(1)
                }
                fail("The dismissed action sheet must release its content after its exit animation")
            }
            openAvatarActions()
            for (label in listOf("相册选择", "文件选择", "删除头像")) {
                assertTrue("$label must be an explicit button", all().any { hasText(it, tr(label)) })

            }
            val actions = listOf("相册选择", "文件选择", "删除头像").map(::row)
            val sheetTitle = all().single { hasText(it, tr("头像")) }
            assertEquals("Keep the avatar sheet title close to its first action", 8f,
                actions.first().boundsInRoot.top - sheetTitle.boundsInRoot.bottom, 0.5f)
            for (action in actions) {
                assertEquals("Each action must fill its own sheet row", 360f, action.size.width.toFloat(), 0.5f)
                assertEquals("Action rows must share the same left edge", actions.first().positionInRoot.x,
                    action.positionInRoot.x, 0.5f)
            }
            for ((above, below) in actions.zipWithNext()) {
                assertTrue("Avatar actions must be vertically stacked without overlap",
                    below.positionInRoot.y >= above.positionInRoot.y + above.size.height)
            }
            assertTrue("The action sheet must be anchored at the screen bottom, not centered",
                actions.last().boundsInRoot.bottom >= 1160f)
            (org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.activity.ComponentDialog)
                .onBackPressedDispatcher.onBackPressed()
            frames()
            assertFalse("Back must dismiss the action sheet", all().any { hasText(it, tr("相册选择")) })
            openAvatarActions()
            click(tr("相册选择"))
            assertNotNull("Album button opens the photo picker", shadowOf(context).nextStartedActivity)
            awaitActionsClosed()
            nameField.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("  New   Name  "))
            frames()
            controller.pause()
            assertEquals("New Name", ProfileStore.name)
            controller.resume()
            openAvatarActions()
            click(tr("文件选择"))
            val document = shadowOf(context).nextStartedActivity
            assertEquals("DocumentsUI must open via ACTION_OPEN_DOCUMENT", android.content.Intent.ACTION_OPEN_DOCUMENT, document.action)
            assertArrayEquals(arrayOf("image/*"), document.getStringArrayExtra(android.content.Intent.EXTRA_MIME_TYPES))
            awaitActionsClosed()
            openAvatarActions(); click(tr("删除头像"))
            assertTrue(all().any { hasText(it, tr("确定要删除头像吗？")) })
            awaitActionsClosed()
            click(tr("取消")); assertNotNull(ProfileStore.avatar)
            openAvatarActions(); click(tr("删除头像"))
            click(tr("删除")); assertNull(ProfileStore.avatar)
            openAvatarActions()
            assertFalse("No avatar means no delete-avatar button", all().any { hasText(it, tr("删除头像")) })
            click(tr("文件选择")); shadowOf(context).nextStartedActivity
            awaitActionsClosed()
            ProfileStore.forgetForTest(); ProfileStore.ensureLoaded(context)
            assertEquals("New Name", ProfileStore.name)
            assertNull(ProfileStore.avatar)
            val field = all().single { it.config.contains(SemanticsProperties.EditableText) }
            field.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("")); frames()
            controller.pause(); controller.resume()
            me.value = false; frames()
            me.value = true; frames()
            val blankField = all().single { it.config.contains(SemanticsProperties.EditableText) }
            assertEquals("Blank names must not be replaced by a default in the input", "",
                blankField.config[SemanticsProperties.EditableText].text)
            val placeholders = all().filter { hasText(it, tr("用户名称")) }
            assertEquals("Only the placeholder remains; no separate name label", 1, placeholders.size)
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            placeholders.single().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
            assertEquals("Placeholder must use the field text metrics", FieldTextStyle.fontSize,
                layouts.single().layoutInput.style.fontSize)
            assertEquals(FieldTextStyle.lineHeight, layouts.single().layoutInput.style.lineHeight)
            assertEquals(androidx.compose.ui.text.style.BaselineShift(0.12f), layouts.single().layoutInput.style.baselineShift)
        } finally {
            view.disposeComposition(); recomposer.close(); runner.cancel()
            controller.pause().stop().destroy()
            ProfileStore.resetForTest(context)
        }
    }
}
