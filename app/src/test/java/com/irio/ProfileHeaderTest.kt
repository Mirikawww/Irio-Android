package com.irio

import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
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
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, shadows = [SettingsImeFocusTest.NoCredentialWrites::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfileHeaderTest {
    @Test fun savedNameReloadsAndBlankBecomesTheDefault() = runTest {
        val context = RuntimeEnvironment.getApplication()
        ProfileStore.resetForTest(context)
        context.getSharedPreferences("profile_settings", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("display_name", "  Mira   Lin ")
            .apply()
        ProfileStore.ensureLoaded(context)
        assertEquals("Mira Lin", ProfileStore.name)
        ProfileStore.saveName(context, "   ")
        assertEquals(PROFILE_DEFAULT_NAME, ProfileStore.name)
        assertEquals(
            PROFILE_DEFAULT_NAME,
            context.getSharedPreferences("profile_settings", android.content.Context.MODE_PRIVATE)
                .getString("display_name", null),
        )
    }

    @Test fun avatarFileRoundTripsAndCropKeepsTheChosenCorner() = runTest {
        val context = RuntimeEnvironment.getApplication()
        ProfileStore.resetForTest(context)
        val source = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.BLUE)
        for (y in 0..1) for (x in 0..1) source.setPixel(x, y, Color.RED)
        val cropped = cropAvatarBitmap(source, AvatarCropWindow(0f, 0f, 2f), output = 2)
        assertNotNull(cropped)
        assertEquals(Color.RED, cropped!!.getPixel(0, 0))
        assertEquals(Color.RED, cropped.getPixel(1, 1))
        val wide = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888)
        val turned = applyExifOrientation(wide, ExifInterface.ORIENTATION_ROTATE_90)
        assertEquals(1, turned.width)
        assertEquals(2, turned.height)
        ProfileStore.saveAvatar(context, cropped)
        assertTrue(File(context.filesDir, "profile/avatar.png").isFile)
        assertNotNull(ProfileStore.avatar)
        ProfileStore.forgetForTest()
        ProfileStore.ensureLoaded(context)
        assertNotNull(ProfileStore.avatar)
        ProfileStore.clearAvatar(context)
        assertNull(ProfileStore.avatar)
        assertFalse(File(context.filesDir, "profile/avatar.png").exists())
        ProfileStore.forgetForTest()
        ProfileStore.ensureLoaded(context)
        assertNull(ProfileStore.avatar)
    }

    @Test fun settingsHeaderShowsTheDefaultNameAndAvatar() = runTest {
        val context = RuntimeEnvironment.getApplication()
        ThemeStore.saveLanguage(context, AppLanguage.ZH_CN)
        ProfileStore.resetForTest(context)
        ProfileStore.ensureLoaded(context)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val host = ComposeView(controller.get())
        try {
            host.setParentCompositionContext(recomposer)
            controller.get().setContentView(host)
            host.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AppTheme { SettingsProfileHeader(onPickAvatar = {}, onEditName = {}, searchField = {}) }
                }
            }
            repeat(8) { index ->
                Snapshot.sendApplyNotifications()
                runCurrent()
                clock.sendFrame((index + 1L) * 16_000_000L)
                runCurrent()
                shadowOf(Looper.getMainLooper()).idle()
                host.measure(
                    View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                )
                host.layout(0, 0, 360, 800)
            }
            fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
            fun nodes(view: View): List<SemanticsNode> {
                val own = if (view is ViewRootForTest) descend(view.semanticsOwner.unmergedRootSemanticsNode) else emptyList()
                val children = if (view is ViewGroup) (0 until view.childCount).flatMap { nodes(view.getChildAt(it)) } else emptyList()
                return own + children
            }
            val tree = nodes(host)
            assertTrue(tree.any { node ->
                node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == "用户" }
            })
            assertTrue(tree.any { node ->
                "头像" in node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
            })
            assertFalse(tree.any { node ->
                node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == "搜索" }
            })
        } finally {
            host.disposeComposition()
            recomposer.close()
            runner.cancel()
            controller.pause().stop().destroy()
        }
    }
}
