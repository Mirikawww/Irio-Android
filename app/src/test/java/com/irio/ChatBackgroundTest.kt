package com.irio

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ChatBackgroundTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        ChatBackgroundStore.resetForTest(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        ChatBackgroundStore.resetForTest(RuntimeEnvironment.getApplication())
        Dispatchers.resetMain()
    }

    @Test
    fun windowsFillCoversEveryEdgeWithoutStretching() {
        val cases = listOf(
            floatArrayOf(400f, 100f, 200f, 200f),
            floatArrayOf(100f, 400f, 200f, 200f),
            floatArrayOf(1920f, 1080f, 1080f, 2400f),
            floatArrayOf(1080f, 2400f, 1920f, 1080f),
            floatArrayOf(100f, 100f, 100f, 100f),
        )
        cases.forEach { spec ->
            val srcWidth = spec[0]
            val srcHeight = spec[1]
            val dstWidth = spec[2]
            val dstHeight = spec[3]
            val fill = chatBackgroundFill(srcWidth, srcHeight, dstWidth, dstHeight)!!
            assertEquals(max(dstWidth / srcWidth, dstHeight / srcHeight), fill.scale, 0.001f)
            assertEquals(fill.scale, fill.drawnWidth / srcWidth, 0.001f)
            assertEquals(fill.scale, fill.drawnHeight / srcHeight, 0.001f)
            val left = max(0f, fill.offsetX)
            val top = max(0f, fill.offsetY)
            val right = min(dstWidth, fill.offsetX + fill.drawnWidth)
            val bottom = min(dstHeight, fill.offsetY + fill.drawnHeight)
            assertEquals(0f, left, 0.01f)
            assertEquals(0f, top, 0.01f)
            assertEquals(dstWidth, right, 0.01f)
            assertEquals(dstHeight, bottom, 0.01f)
            val factor = ChatBackgroundContentScale.computeScaleFactor(
                Size(srcWidth, srcHeight),
                Size(dstWidth, dstHeight),
            )
            assertEquals(fill.scale, factor.scaleX, 0.001f)
            assertEquals(fill.scale, factor.scaleY, 0.001f)
        }
    }

    @Test
    fun fitWouldLeaveBarsButFillDoesNot() {
        val src = Size(400f, 100f)
        val dst = Size(200f, 200f)
        val fill = chatBackgroundFill(src.width, src.height, dst.width, dst.height)!!
        val fit = ContentScale.Fit.computeScaleFactor(src, dst)
        assertTrue(fill.scale > fit.scaleX)
        assertTrue(fill.offsetX < 0f)
        assertEquals(0f, fill.offsetY, 0.001f)
        val portrait = chatBackgroundFill(100f, 400f, 200f, 200f)!!
        assertEquals(0f, portrait.offsetX, 0.001f)
        assertTrue(portrait.offsetY < 0f)
    }

    @Test
    fun zeroSizeIsNotAFill() {
        assertNull(chatBackgroundFill(0f, 100f, 200f, 200f))
        assertNull(chatBackgroundFill(100f, 100f, 0f, 200f))
    }

    @Test
    fun onlyImageMimesAreAccepted() {
        assertTrue(acceptsChatBackgroundMime(null))
        assertTrue(acceptsChatBackgroundMime("application/octet-stream"))
        assertTrue(acceptsChatBackgroundMime("image/jpeg"))
        assertTrue(acceptsChatBackgroundMime("image/png"))
        assertTrue(acceptsChatBackgroundMime("image/webp"))
        assertTrue(acceptsChatBackgroundMime("image/heic"))
        assertFalse(acceptsChatBackgroundMime("video/mp4"))
        assertFalse(acceptsChatBackgroundMime("application/pdf"))
        assertFalse(acceptsChatBackgroundMime("text/plain"))
    }

    @Test
    fun saveKeepsOnlyARealImageAndClearRemovesIt() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val text = File(context.cacheDir, "note.txt").apply { writeText("not an image") }
        assertFalse(ChatBackgroundStore.save(context, Uri.fromFile(text)))
        assertNull(ChatBackgroundStore.fileName)

        val first = writePng(context, "first.png", 8, 4)
        assertTrue(ChatBackgroundStore.save(context, Uri.fromFile(first)))
        val firstName = ChatBackgroundStore.fileName
        assertTrue(firstName!!.startsWith("background-"))
        val firstFile = File(ChatBackgroundStore.directory(context), firstName)
        assertTrue(firstFile.isFile)

        val second = writePng(context, "second.png", 4, 8)
        assertTrue(ChatBackgroundStore.save(context, Uri.fromFile(second)))
        val secondName = ChatBackgroundStore.fileName
        assertTrue(secondName != null && secondName != firstName)
        assertFalse(firstFile.exists())
        val kept = ChatBackgroundStore.directory(context).listFiles().orEmpty()
        assertEquals(1, kept.size)
        assertEquals(secondName, kept.single().name)

        ChatBackgroundStore.clear(context)
        assertNull(ChatBackgroundStore.fileName)
        assertFalse(ChatBackgroundStore.directory(context).exists())
    }

    private fun writePng(context: android.content.Context, name: String, width: Int, height: Int): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        bitmap.recycle()
        return File(context.cacheDir, name).apply { writeBytes(bytes) }
    }
}
