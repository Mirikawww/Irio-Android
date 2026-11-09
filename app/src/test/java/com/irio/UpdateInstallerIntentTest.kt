package com.irio

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpdateInstallerIntentTest {
    @Test fun unknownSourcePermissionDefersInstallationAndReturningCanLaunchTheInstaller() {
        val context = RuntimeEnvironment.getApplication()
        val pm = org.robolectric.Shadows.shadowOf(context.packageManager)
        val app = org.robolectric.Shadows.shadowOf(context)
        val file = java.io.File(context.cacheDir, "fixture.apk")
        try {
            pm.setCanRequestPackageInstalls(false)
            assertFalse(launchUpdateInstaller(context, file) { _, _ -> error("Installer must wait for permission") })
            val permission = app.nextStartedActivity
            assertEquals(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, permission.action)
            assertEquals("package:${context.packageName}", permission.dataString)
            pm.setCanRequestPackageInstalls(true)
            assertTrue(launchUpdateInstaller(context, file) { owner, apk ->
                updateInstallerIntent(owner, apk) { _, _ -> android.net.Uri.parse("content://${owner.packageName}.fileprovider/cache/fixture.apk") }
            })
            assertEquals("application/vnd.android.package-archive", app.nextStartedActivity.type)
        } finally { pm.setCanRequestPackageInstalls(false) }
    }

    @Test fun installUsesTheSystemApkViewerWithAScopedReadOnlyContentUri() {
        val context = RuntimeEnvironment.getApplication()
        val file = context.cacheDir.resolve("app-updates/fixture.apk").apply { parentFile!!.mkdirs(); writeText("fixture") }
        try {
            // FileProvider's Android '/' prefix check cannot match Windows backslash paths.
            // Isolate that platform URI boundary; exercise the real installer intent builder.
            val intent = updateInstallerIntent(context, file) { owner, apk ->
                assertEquals(context, owner)
                assertEquals(file, apk)
                android.net.Uri.parse("content://${owner.packageName}.fileprovider/cache/app-updates/fixture.apk")
            }
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("application/vnd.android.package-archive", intent.type)
            assertEquals("content", intent.data!!.scheme)
            assertEquals("${context.packageName}.fileprovider", intent.data!!.authority)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } finally { file.delete() }
    }
}
