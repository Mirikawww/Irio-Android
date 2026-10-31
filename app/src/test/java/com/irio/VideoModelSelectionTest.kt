package com.irio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class VideoModelSelectionTest {
    @Test fun onlyExactH3VariantsAreVideoModelsAndNeverImages() {
        val context = RuntimeEnvironment.getApplication()
        val models = listOf("MiniMax-H3", "MiniMax-H3-Highspeed", "MiniMax-H3-Lite", "MiniMax-H3-Max")
        models.forEach { model ->
            listOf(model, model.lowercase(), "minimax/$model", "gateway/minimax/$model").forEach { id ->
                assertTrue(id, ModelCatalog.isVideoModel(context, id))
                assertFalse(id, ModelCatalog.isImageModel(context, id))
            }
        }
        listOf("", "MiniMax-H3-Unknown", "MiniMax-H30", "MiniMax-H3-Max-extra", "MiniMax-M3",
            "image-01", "gpt-image-2", "gpt-5", "MiniMax-H3 ", "prefix-MiniMax-H3").forEach { id ->
            assertFalse(id, ModelCatalog.isVideoModel(context, id))
        }
        assertTrue("Image detection must still work", ModelCatalog.isImageModel(context, "gpt-image-2"))
    }

    @Test fun videoDefaultModelIsIndependentFromImageAndChatDefaults() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("tool_settings", 0)
        prefs.edit().clear().commit()
        try {
            assertEquals("", ToolStore.loadVideoGenModel(context))
            ToolStore.saveImageGenModel(context, "gpt-image-2")
            ToolStore.saveTitleModel(context, "title-chat")
            ToolStore.saveSubagentModel(context, "subagent-chat")
            ToolStore.saveCompressionModel(context, "compression-chat")
            ToolStore.saveVideoGenModel(context, "minimax/MiniMax-H3")
            assertEquals("minimax/MiniMax-H3", ToolStore.loadVideoGenModel(context))
            assertEquals("minimax/MiniMax-H3", prefs.getString("video_gen_model", null))
            assertEquals("gpt-image-2", ToolStore.loadImageGenModel(context))
            assertEquals("title-chat", ToolStore.loadTitleModel(context))
            assertEquals("subagent-chat", ToolStore.loadSubagentModel(context))
            assertEquals("compression-chat", ToolStore.loadCompressionModel(context))
            ToolStore.saveVideoGenModel(context, "")
            assertEquals("", ToolStore.loadVideoGenModel(context))
            assertEquals("gpt-image-2", ToolStore.loadImageGenModel(context))
        } finally {
            prefs.edit().clear().commit()
        }
    }

    @Test fun videoModelSearchHasTheModelsDestinationAndAnchorInBothLanguages() {
        for ((language, query) in listOf(AppLanguage.ZH_CN to "视频生成模型", AppLanguage.EN to "Video model")) {
            val hit = searchSettings(query, label = { translate(language, it) }).single { it.id == "model.video" }
            assertEquals(query, hit.title)
            assertEquals("model.video", hit.anchor)
            assertEquals("com.irio.ModelSettingsActivity", hit.destination.name)
        }
        assertEquals("Video model", translate(AppLanguage.EN, "选择视频生成模型"))
        assertEquals("Duration", translate(AppLanguage.EN, "视频时长"))
    }
}
