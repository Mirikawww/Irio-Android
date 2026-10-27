package com.irio

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ImageUploadSettingsTest {
    private fun reload(context: Context) {
        ThemeStore::class.java.getDeclaredField("loaded").apply { isAccessible = true }.setBoolean(ThemeStore, false)
        ThemeStore.ensureLoaded(context)
    }
    @Test fun defaultsOnAtNinetyAndPersistsClampedQualityWithoutResettingItWhenDisabled() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("appearance_settings", Context.MODE_PRIVATE)
        val oldEnabled = if (prefs.contains("image_upload_compression")) prefs.getBoolean("image_upload_compression", true) else null
        val oldQuality = if (prefs.contains("image_upload_quality")) prefs.getInt("image_upload_quality", 90) else null
        val oldFormat = prefs.getString("image_upload_format", null)
        try {
            prefs.edit().remove("image_upload_compression").remove("image_upload_quality").remove("image_upload_format").commit()
            reload(context)
            assertEquals(ImageUploadOptions(), ThemeStore.imageUploadOptions())
            assertEquals(ImageUploadFormat.WEBP, ThemeStore.imageUploadFormat)
            assertEquals(ImageUploadFormat.WEBP, ImageUploadFormat.fromStored("unknown"))
            for (format in ImageUploadFormat.entries) {
                ThemeStore.saveImageUploadFormat(context, format); reload(context)
                assertEquals(format, ThemeStore.imageUploadFormat)
                assertEquals(format, ImageUploadFormat.fromStored(prefs.getString("image_upload_format", null)))
            }
            for ((input, expected) in listOf(40 to 40, 90 to 90, 100 to 100, 0 to 40, 101 to 100)) {
                ThemeStore.saveImageUploadQuality(context, input)
                reload(context)
                assertEquals(expected, ThemeStore.imageUploadQuality)
                assertEquals(expected, prefs.getInt("image_upload_quality", -1))
            }
            ThemeStore.saveImageUploadQuality(context, 82)
            ThemeStore.saveImageUploadCompression(context, false)
            reload(context)
            assertFalse(ThemeStore.imageUploadCompression)
            assertEquals(82, ThemeStore.imageUploadQuality)
            ThemeStore.saveImageUploadCompression(context, true)
            assertEquals(82, ThemeStore.imageUploadQuality)
            prefs.edit().putInt("image_upload_quality", -1).commit(); reload(context)
            assertEquals(40, ThemeStore.imageUploadQuality)
            prefs.edit().putInt("image_upload_quality", 101).commit(); reload(context)
            assertEquals(100, ThemeStore.imageUploadQuality)
        } finally {
            prefs.edit().apply {
                if (oldEnabled == null) remove("image_upload_compression") else putBoolean("image_upload_compression", oldEnabled)
                if (oldQuality == null) remove("image_upload_quality") else putInt("image_upload_quality", oldQuality)
                if (oldFormat == null) remove("image_upload_format") else putString("image_upload_format", oldFormat)
            }.commit()
            reload(context)
        }
    }

    @Test fun settingAndQualityAreSearchableAndLocalized() {
        assertEquals("Image compression and conversion", translate(AppLanguage.EN, "图片压缩与转换"))
        assertEquals("appearance.imageCompression", searchSettings("图片压缩与转换") { it }.single().id)
        assertEquals("appearance.imageCompression", searchSettings("Image compression and conversion") { translate(AppLanguage.EN, it) }.single().id)
        assertEquals("appearance.imageCompression", searchSettings("图片压缩质量") { it }.single().anchor)
    }
}
