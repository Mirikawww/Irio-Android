package com.irio

import android.content.ClipData
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.ClipMetadata
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class LongTextPasteTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun strictlyExceedsThresholdAndCountsUnicodeCharacters() {
        assertFalse(LongTextPaste.shouldAttach("字".repeat(1500), true, 1500))
        assertTrue(LongTextPaste.shouldAttach("字".repeat(1501), true, 1500))
        assertFalse(LongTextPaste.shouldAttach("a".repeat(1501), false, 1500))
        assertFalse(LongTextPaste.shouldAttach("😀".repeat(100), true, 100))
        assertTrue(LongTextPaste.shouldAttach("😀".repeat(101), true, 100))
        assertFalse(LongTextPaste.shouldAttach("", true, 100))
    }

    @Test fun thresholdIsClampedAndSnappedToHundreds() {
        assertEquals(1500, LongTextPaste.DEFAULT_THRESHOLD)
        assertEquals(100, LongTextPaste.normalizeThreshold(Int.MIN_VALUE))
        assertEquals(10000, LongTextPaste.normalizeThreshold(Int.MAX_VALUE))
        assertEquals(1500, LongTextPaste.normalizeThreshold(1549))
        assertEquals(1600, LongTextPaste.normalizeThreshold(1550))
        (100..10000 step 100).forEach { assertEquals(it, LongTextPaste.normalizeThreshold(it)) }
    }

    private fun content(text: String, source: TransferableContent.Source = TransferableContent.Source.Clipboard): TransferableContent {
        val clip = ClipData.newPlainText("test", text)
        return content(clip, source)
    }

    // Foundation owns construction of content events. Use reflection only for these
    // isolated listener fixtures; ComposerPasteTest exercises the real public paste action.
    private fun content(clip: ClipData, source: TransferableContent.Source): TransferableContent {
        val sourceValue = source.javaClass.getDeclaredMethod("unbox-impl").invoke(source)
        val constructor = TransferableContent::class.java.declaredConstructors.single { it.parameterCount == 5 }
        return constructor.newInstance(ClipEntry(clip), ClipMetadata(clip.description), sourceValue, null, null) as TransferableContent
    }

    @Test fun receiverUsesLatestSettingsAndOnlyConsumesClipboardPastes() {
        var enabled = false
        var threshold = 1500
        val received = mutableListOf<String>()
        val receiver = LongTextPasteReceiver({ enabled }, { threshold }) { received += it; true }
        val long = content("x".repeat(1501))
        assertSame(long, receiver.onReceive(long))
        enabled = true
        assertNull(receiver.onReceive(long))
        assertEquals(listOf("x".repeat(1501)), received)
        threshold = 2000
        assertSame(long, receiver.onReceive(long))
        val drag = content("x".repeat(3000), TransferableContent.Source.DragAndDrop)
        assertSame(drag, receiver.onReceive(drag))
    }

    @Test fun rejectedAttachmentFallsBackToOrdinaryPaste() {
        val original = content("a".repeat(1600))
        val receiver = LongTextPasteReceiver({ true }, { 1500 }) { false }
        assertSame(original, receiver.onReceive(original))
        val exactThreshold = content("a".repeat(1500))
        assertSame(exactThreshold, receiver.onReceive(exactThreshold))
    }

    @Test fun mixedClipboardKeepsNonTextItemsAndJoinsTextWithoutTrimming() {
        val clip = ClipData.newPlainText("test", " a\n".repeat(400))
        clip.addItem(ClipData.Item(" b\n".repeat(400)))
        clip.addItem(ClipData.Item(Uri.parse("content://test/image")))
        var pasted: String? = null
        val receiver = LongTextPasteReceiver({ true }, { 1500 }) { pasted = it; true }
        val remaining = receiver.onReceive(content(clip, TransferableContent.Source.Clipboard))
        assertEquals(" a\n".repeat(400) + "\n" + " b\n".repeat(400), pasted)
        assertEquals(1, remaining!!.clipEntry.clipData.itemCount)
        assertEquals(Uri.parse("content://test/image"), remaining.clipEntry.clipData.getItemAt(0).uri)
    }

    @Test fun writesUniqueUtf8TextFilesUsingTheExistingAttachmentFormat() {
        val directory = File(temporaryFolder.root, "attachments")
        val text = "  中文😀\r\n\nending  "
        val first = LongTextPaste.attachment(directory)
        val second = LongTextPaste.attachment(directory)
        assertNotEquals(first.filePath, second.filePath)
        assertTrue(first.name.endsWith(".txt"))
        assertEquals("text/plain", first.mimeType)
        LongTextPaste.write(first, text)
        assertArrayEquals(text.toByteArray(Charsets.UTF_8), first.bytes())
        assertEquals(1f, first.slotCost)
    }

    @Test fun failedWritesPreserveDraftTextQuoteEditingAndOtherAttachments() {
        val attachment = LongTextPaste.attachment(temporaryFolder.root)
        val other = LongTextPaste.attachment(temporaryFolder.root)
        val draft = ComposerDraft("existing ", listOf(other, attachment), 42L, "quote")
        val restored = restoreFailedTextPaste(draft, attachment, "pasted")
        assertEquals("existing pasted", restored.text)
        assertEquals(listOf(other), restored.attachments)
        assertEquals(42L, restored.editingMessageId)
        assertEquals("quote", restored.quote)
        assertSame(restored, restoreFailedTextPaste(restored, attachment, "do not restore a cancelled paste"))
        val blockedDirectory = File(temporaryFolder.root, "blocked").apply { writeText("not a directory") }
        val failedAttachment = LongTextPaste.attachment(blockedDirectory)
        assertThrows(java.io.IOException::class.java) { LongTextPaste.write(failedAttachment, "original") }
        assertFalse(File(failedAttachment.filePath).exists())
    }

    @Test fun settingsPersistIndependentlyAndUseDefaults() {
        val context = RuntimeEnvironment.getApplication()
        ThemeStore.ensureLoaded(context)
        assertFalse(ThemeStore.longTextPaste)
        assertEquals(1500, ThemeStore.longTextPasteThreshold)
        val prefs = context.getSharedPreferences("appearance_settings", Context.MODE_PRIVATE)
        try {
            ThemeStore.saveLongTextPaste(context, true)
            ThemeStore.saveLongTextPasteThreshold(context, 1641)
            assertTrue(ThemeStore.longTextPaste)
            assertTrue(prefs.getBoolean("long_text_paste", false))
            assertEquals(1600, prefs.getInt("long_text_paste_threshold", 0))
            ThemeStore.saveLongTextPaste(context, false)
            assertEquals(1600, ThemeStore.longTextPasteThreshold)
        } finally {
            ThemeStore.saveLongTextPaste(context, false)
            ThemeStore.saveLongTextPasteThreshold(context, 1500)
        }
    }

    @Test fun labelsAreLocalized() {
        assertEquals("Paste long text as file", translate(AppLanguage.EN, "粘贴长文本为文件"))
        assertEquals("%d characters", translate(AppLanguage.EN, "%d 个字符"))
    }
}
