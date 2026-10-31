package com.irio

import android.content.Context
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VideoGenerationStoreTest {
    @Test fun preferencesRoundTripAndStayIsolatedByProviderAndModel() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("video_generation_settings", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val selected = VideoGenerationOptions("2K", 15, "9:16")
        VideoGenerationStore.save(context, "provider-a", "MiniMax-H3", selected)
        assertEquals(selected, VideoGenerationStore.load(context, "provider-a", "MiniMax-H3"))
        assertEquals(VideoGenerationOptions(), VideoGenerationStore.load(context, "provider-b", "MiniMax-H3"))
        assertEquals(VideoGenerationOptions(), VideoGenerationStore.load(context, "provider-a", "MiniMax-H3-Lite"))
        VideoGenerationStore.save(context, "a", "bc", VideoGenerationOptions(duration = 6))
        assertEquals(VideoGenerationOptions(), VideoGenerationStore.load(context, "ab", "c"))
    }

    @Test fun malformedStoredJsonAndModelInvalidValuesNormalizeSafely() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("video_generation_settings", Context.MODE_PRIVATE)
        preferences.edit().clear().putString("1:pMiniMax-H3", "broken-json").commit()
        assertEquals(VideoGenerationOptions(), VideoGenerationStore.load(context, "p", "MiniMax-H3"))
        VideoGenerationStore.save(context, "p", "MiniMax-H3-Max", VideoGenerationOptions("2K", 4, "invalid"))
        assertEquals(VideoGenerationOptions(), VideoGenerationStore.load(context, "p", "MiniMax-H3-Max"))
    }
}
