package com.irio

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.core.content.ContextCompat

@Composable
internal fun ChatScreenLaunchers(state: ChatScreenState) {
    val context = state.context
    val requestRecordAudio =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) state.startSttRecording()
            else state.showComposerError(tr("需要麦克风权限才能使用 STT"))
        }

    val pickPhotos =
        rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) {
            uris ->
            state.addAttachments(uris)
        }
    val pickFiles =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            state.addAttachments(uris)
        }
    val requestSpeechNotification =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) SpeechNotifications.sync(context, SpeechPlayer.playback)
        }
    val requestReplyNotification =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    state.ensureSpeechNotification = {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            requestSpeechNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    state.requestRecordAudio = { requestRecordAudio.launch(Manifest.permission.RECORD_AUDIO) }
    state.requestReplyNotification = {
        requestReplyNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    state.pickPhotos = {
        pickPhotos.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
        )
    }
    state.pickFiles = { pickFiles.launch(arrayOf("*/*")) }
}
