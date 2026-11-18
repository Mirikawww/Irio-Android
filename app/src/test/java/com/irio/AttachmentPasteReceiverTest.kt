package com.irio

import android.content.ClipData
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.ClipMetadata
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalFoundationApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AttachmentPasteReceiverTest {
    private fun event(clip: ClipData, source: TransferableContent.Source): TransferableContent {
        val value = source.javaClass.getDeclaredMethod("unbox-impl").invoke(source)
        return TransferableContent::class.java.declaredConstructors.single { it.parameterCount == 5 }
            .newInstance(ClipEntry(clip), ClipMetadata(clip.description), value, null, null) as TransferableContent
    }

    @Test fun consumesOnlySharedAttachmentsInOrderAndLeavesMixedTextIntact() {
        val image = Uri.parse("content://paste.fixture/image.png")
        val file = Uri.parse("content://paste.fixture/document.pdf")
        val clip = ClipData.newPlainText("mixed", "caption").apply {
            addItem(ClipData.Item(image)); addItem(ClipData.Item(file))
        }
        val received = mutableListOf<Uri>()
        val receiver = AttachmentPasteReceiver { received += it; true }
        val remaining = receiver.onReceive(event(clip, TransferableContent.Source.Clipboard))!!
        assertEquals(listOf(image, file), received)
        assertEquals(1, remaining.clipEntry.clipData.itemCount)
        assertEquals("caption", remaining.clipEntry.clipData.getItemAt(0).text)
        assertEquals(3, clip.itemCount)
    }

    @Test fun keyboardContentWorksAndRejectedOrUnsupportedItemsAreNotConsumed() {
        val clip = ClipData.newRawUri("image", Uri.parse("content://paste.fixture/image"))
        val keyboard = event(clip, TransferableContent.Source.Keyboard)
        assertNull(AttachmentPasteReceiver { true }.onReceive(keyboard))
        assertSame(keyboard, AttachmentPasteReceiver { false }.onReceive(keyboard))
        val drag = event(clip, TransferableContent.Source.DragAndDrop)
        assertSame(drag, AttachmentPasteReceiver { error("Unexpected drag") }.onReceive(drag))
        for (clipData in listOf(ClipData.newPlainText("URL", "https://example.test/image.png"),
            ClipData.newRawUri("URL", Uri.parse("https://example.test/image.png")),
            ClipData.newRawUri("path", Uri.parse("file:///private/file.txt")))) {
            val content = event(clipData, TransferableContent.Source.Clipboard)
            assertSame(content, AttachmentPasteReceiver { error("Not a shared attachment") }.onReceive(content))
        }
    }
}
