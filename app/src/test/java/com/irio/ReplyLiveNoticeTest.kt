package com.irio

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import androidx.activity.ComponentActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ReplyLiveNoticeTest {
    @Test fun pendingRequestSurvivesForegroundRoundTripWithoutAnyNewTokens() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = shadowOf(app.getSystemService(NotificationManager::class.java))
        val id = "pending-round-trip"
        ReplyLiveNotice.publish(app, id, "Fixture", tr("请求中"), false, requestStarted = true)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            assertTrue(ReplyLiveNotice.foreground())
            assertNull("Foreground hides the notification", manager.getNotification(id.hashCode()))
            activity.pause().stop()
            assertFalse(ReplyLiveNotice.foreground())
            val restored = manager.getNotification(id.hashCode())
            assertNotNull("Returning to background must restore a pending request without a new stream frame", restored)
            assertEquals("请求中", restored.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        } finally {
            activity.destroy()
            ReplyLiveNotice.dismiss(app, id)
        }
    }

    @Test fun onlyANewRequestRearmsADismissedNotificationNotAnotherToolRound() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = shadowOf(app.getSystemService(NotificationManager::class.java))
        val id = "dismissed-request"
        try {
            ReplyLiveNotice.publish(app, id, "Fixture", tr("请求中"), false, requestStarted = true)
            ReplyLiveNotice.onUserDismissed(id)
            ReplyNotifications.cancel(app, id)
            ReplyLiveNotice.publish(app, id, "Fixture", tr("请求中"), false)
            assertNull("A new model tool round must respect the dismissal", manager.getNotification(id.hashCode()))
            ReplyLiveNotice.publish(app, id, "Fixture", tr("请求中"), false, requestStarted = true)
            assertNotNull("A later user request starts a fresh notification", manager.getNotification(id.hashCode()))
        } finally { ReplyLiveNotice.dismiss(app, id) }
    }
}
