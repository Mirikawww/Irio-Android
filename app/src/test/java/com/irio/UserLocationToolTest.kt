package com.irio

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment
import android.Manifest
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UserLocationToolTest {
    @Test fun locationTimeoutReturnsFailureAndNeverCoordinates() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        val manager = app.getSystemService(LocationManager::class.java)
        shadowOf(manager).setLocationEnabled(true)
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        try {
            val result = async { UserLocationAccess.get(app, ChatRequestCancellation()) }
            testScheduler.runCurrent()
            assertFalse(result.isCompleted)
            testScheduler.advanceTimeBy(20_001)
            testScheduler.runCurrent()
            val response = result.await()
            assertEquals(ToolOutcome.FAILED, response.outcome)
            assertEquals("location_unavailable", JSONObject(response.text).getString("error"))
            assertFalse(JSONObject(response.text).has("latitude"))
        } finally {
            shadowOf(app).denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
            Dispatchers.resetMain()
        }
    }

    @Test fun grantedPermissionReturnsOneShotCoordinatesAndAccuracy() = verifyCoordinates(askPermission = false)
    @Test fun missingPermissionRequestsAtCallTimeAndAcceptsApproximateGrant() = verifyCoordinates(askPermission = true)

    private fun verifyCoordinates(askPermission: Boolean) = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        var requests = 0
        lateinit var session: UserLocationPermissionSession
        session = UserLocationPermissionSession({ UserLocationPermissions.hasPermission(app) }, { true }, {
            requests++
            shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
            session.complete()
        })
        UserLocationPermissions.attach(session)
        if (!askPermission) shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        try {
        val manager = app.getSystemService(LocationManager::class.java)
        shadowOf(manager).setLocationEnabled(true)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
        val updates = mutableListOf<ChatStreamUpdate>()
        val result = async(kotlinx.coroutines.Dispatchers.Default) {
            ToolRegistry.default().execute(ChatToolCall("location", "get_location", "{}"),
                ToolExecutionContext(provider = ProviderConfig("location-fixture", "Fixture", "http://localhost", ""), model = "fixture", appContext = app,
                    onUpdate = { updates += it }))
        }
        repeat(100) {
            shadowOf(Looper.getMainLooper()).idle()
            shadowOf(manager).simulateLocation(Location(LocationManager.GPS_PROVIDER).apply {
                latitude = 31.2; longitude = 121.5; accuracy = 25f
                time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            })
            if (!result.isCompleted) Thread.sleep(10)
        }
        val response = result.await()
        assertEquals(response.text, ToolOutcome.SUCCESS, response.outcome)
        val json = JSONObject(response.text)
        assertEquals(31.2, json.getDouble("latitude"), 0.00001)
        assertEquals(121.5, json.getDouble("longitude"), 0.00001)
        assertEquals(25.0, json.getDouble("accuracyMeters"), 0.001)
        assertEquals(if (askPermission) "approximate" else "precise", json.getString("permissionPrecision"))
        assertEquals(if (askPermission) 1 else 0, requests)
        assertTrue(json.getString("timestamp").isNotBlank())
        assertEquals(listOf(true, false), updates.mapNotNull { it.toolActive })
        } finally {
            UserLocationPermissions.detach(session)
            shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    @Test fun deniedPermissionNeverReturnsCoordinates() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val result = async(kotlinx.coroutines.Dispatchers.Default) { UserLocationAccess.get(app, ChatRequestCancellation()) }
        while (!result.isCompleted) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        val response = result.await()
        assertEquals(ToolOutcome.FAILED, response.outcome)
        assertEquals("permission_denied", JSONObject(response.text).getString("error"))
        assertFalse(JSONObject(response.text).has("latitude"))
    }

    @Test fun disabledSystemLocationReturnsActionableFailure() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        val manager = app.getSystemService(LocationManager::class.java)
        shadowOf(manager).setLocationEnabled(false)
        try {
            val result = async(kotlinx.coroutines.Dispatchers.Default) { UserLocationAccess.get(app, ChatRequestCancellation()) }
            while (!result.isCompleted) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
            val response = result.await()
            assertEquals(ToolOutcome.FAILED, response.outcome)
            assertEquals("location_disabled", JSONObject(response.text).getString("error"))
            assertFalse(JSONObject(response.text).has("latitude"))
        } finally { shadowOf(app).denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION) }
    }

    @Test fun cancellationStopsPermissionWaitWithoutAcquiringLocation() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        var requested = false
        val session = UserLocationPermissionSession({ false }, { true }, { requested = true })
        UserLocationPermissions.attach(session)
        val cancellation = ChatRequestCancellation()
        try {
            val result = async(kotlinx.coroutines.Dispatchers.Default) {
                runCatching { UserLocationAccess.get(app, cancellation) }.exceptionOrNull()
            }
            repeat(100) { shadowOf(Looper.getMainLooper()).idle(); if (!requested) Thread.sleep(5) }
            assertTrue(requested)
            cancellation.cancel()
            repeat(100) { shadowOf(Looper.getMainLooper()).idle(); if (!result.isCompleted) Thread.sleep(5) }
            assertTrue(result.await() is kotlinx.coroutines.CancellationException)
        } finally { UserLocationPermissions.detach(session) }
    }

    @Test fun locationIsAvailableWithoutPriorPermissionAndRespectsToolPreferences() {
        val app = RuntimeEnvironment.getApplication()
        ShadowEnvironment.addExternalDir(app.cacheDir.resolve("location-external").absolutePath)
        val original = ToolPreferencesStore.load(app).isEnabled("get_location")
        try {
            ToolPreferencesStore.saveEnabled(app, "get_location", true)
            val request = chatToolRegistry(app, false, "")
            for (registry in listOf(ToolRegistry.default(), request, request.withoutSubagents())) {
                assertTrue("Model must be able to request location before permission exists", registry.handles("get_location"))
                for (protocol in ProviderProtocol.entries) {
                    val schema = registry.definitions(protocol).single { (it.optJSONObject("function") ?: it).optString("name") == "get_location" }
                    val function = schema.optJSONObject("function") ?: schema
                    assertTrue(function.getString("description").contains("permission"))
                }
            }
            val entry = ToolCatalog.entries(ToolFeatureState()).single { it.name == "get_location" }
            assertEquals(tr("获取用户位置"), entry.title)
            assertNull(entry.blockedReason)
            assertTrue(entry.canDisable)
            ToolPreferencesStore.saveEnabled(app, "get_location", false)
            assertFalse(chatToolRegistry(app, false, "").handles("get_location"))
        } finally {
            ToolPreferencesStore.saveEnabled(app, "get_location", original)
        }
    }
}
