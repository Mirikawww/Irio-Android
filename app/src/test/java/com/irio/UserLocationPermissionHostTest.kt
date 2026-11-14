package com.irio

import android.Manifest
import android.content.pm.PackageManager
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class UserLocationPermissionHostTest {
    @Test fun realHostLaunchesSystemPermissionsOnDemandAndResumesWithApproximateGrant() = runTest {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val app = activity.application
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val view = ComposeView(activity)
        var time = 0L
        fun frames() = repeat(5) {
            shadowOf(Looper.getMainLooper()).idle()
            Snapshot.sendApplyNotifications(); testScheduler.runCurrent()
            clock.sendFrame(time.also { time += 16_000_000L }); testScheduler.runCurrent()
            view.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 400, 900)
        }
        try {
            view.setParentCompositionContext(recomposer)
            activity.setContentView(view)
            view.setContent { UserLocationPermissionHost() }
            frames()
            assertNull(shadowOf(activity).lastRequestedPermission)
            val request = async { UserLocationPermissions.request() }
            frames()
            val nativeRequest = requireNotNull(shadowOf(activity).lastRequestedPermission)
            assertEquals(setOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                nativeRequest.requestedPermissions.toSet())
            assertFalse(request.isCompleted)
            shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
            activity.onRequestPermissionsResult(nativeRequest.requestCode, nativeRequest.requestedPermissions,
                intArrayOf(PackageManager.PERMISSION_DENIED, PackageManager.PERMISSION_GRANTED))
            frames()
            assertTrue(request.await())
            assertFalse(UserLocationPermissions.hasPrecisePermission(app))
            assertTrue(UserLocationPermissions.hasPermission(app))
        } finally {
            view.disposeComposition()
            recomposer.cancel(); runner.cancel()
            controller.pause().stop().destroy()
            shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }
}
