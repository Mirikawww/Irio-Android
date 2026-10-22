package com.irio

import android.media.MediaPlayer
import android.os.Looper
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class PronunciationPlayerTest {
    @Test fun shortWordAudioUsesItsOwnMediaPlayerAndNeverActivatesTtsMiniPlayer() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val context = RuntimeEnvironment.getApplication()
        val recording = fixturePronunciation()
        val directory = File(context.cacheDir, "pronunciations").apply { mkdirs() }
        val audio = File(directory, "${recording.id}.audio").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(audio.absolutePath), ShadowMediaPlayer.MediaInfo(700, 0))
        try {
            PronunciationPlayer.play(context, recording)
            val job = PronunciationPlayer::class.java.getDeclaredField("job").apply { isAccessible = true }.get(PronunciationPlayer) as Job
            job.join()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            val field = PronunciationPlayer::class.java.getDeclaredField("player").apply { isAccessible = true }
            val media = field.get(PronunciationPlayer) as? MediaPlayer
            assertNotNull("A dedicated word-audio MediaPlayer must exist", media)
            assertTrue(media!!.isPlaying)
            assertFalse("LL playback must not enable the TTS Mini Player", SpeechPlayer.playback.active)
            assertNull(SpeechPlayer.playback.messageId)
            PronunciationPlayer.stop()
            assertNull(field.get(PronunciationPlayer))
            assertEquals(ShadowMediaPlayer.State.END, Shadows.shadowOf(media).state)
            assertFalse(SpeechPlayer.playback.active)
        } finally {
            PronunciationPlayer.stop()
            audio.delete()
            Dispatchers.resetMain()
        }
    }
}
