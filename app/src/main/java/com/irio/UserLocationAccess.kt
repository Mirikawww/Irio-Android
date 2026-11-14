package com.irio

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.location.LocationRequest
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.time.Instant
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** No background permission, Google Play dependency, geocoding service or persistent location cache. */
internal object UserLocationAccess {
    suspend fun get(context: Context, cancellation: ChatRequestCancellation): ToolExecutionResult = coroutineScope {
        val operation = async {
            withContext(Dispatchers.Main.immediate) {
                if (!UserLocationPermissions.hasPermission(context)) {
                    val allowed = withTimeoutOrNull(120_000) { UserLocationPermissions.request() } == true
                    if (!allowed || !UserLocationPermissions.hasPermission(context)) return@withContext failure(
                        "permission_denied", tr("定位权限未授予，请在系统应用设置中允许定位后重试。"))
                }
                val manager = context.getSystemService(LocationManager::class.java)
                    ?: return@withContext failure("unavailable", tr("定位当前不可用。"))
                if (!manager.isLocationEnabled) return@withContext failure("location_disabled", tr("系统定位已关闭，请开启后重试。"))
                val precise = UserLocationPermissions.hasPrecisePermission(context)
                val providers = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER,
                    LocationManager.GPS_PROVIDER).filter { manager.hasProvider(it) && manager.isProviderEnabled(it) }
                if (providers.isEmpty()) return@withContext failure("unavailable", tr("定位当前不可用。"))
                try {
                    val location = withTimeoutOrNull(20_000) { current(manager, providers) }
                        ?: return@withContext failure("location_unavailable", tr("未能获取当前位置，请检查定位服务或稍后重试。"))
                    // Permission can be revoked while waiting for a fix.
                    if (!UserLocationPermissions.hasPermission(context)) return@withContext failure(
                        "permission_denied", tr("定位权限未授予，请在系统应用设置中允许定位后重试。"))
                    ToolExecutionResult(JSONObject().put("latitude", location.latitude).put("longitude", location.longitude)
                        .put("accuracyMeters", if (location.hasAccuracy()) location.accuracy else JSONObject.NULL)
                        .put("timestamp", Instant.ofEpochMilli(location.time).toString())
                        .put("permissionPrecision", if (precise && UserLocationPermissions.hasPrecisePermission(context)) "precise" else "approximate")
                        .put("coordinateSystem", "WGS84").toString())
                } catch (denied: SecurityException) {
                    failure("permission_denied", tr("定位权限未授予，请在系统应用设置中允许定位后重试。"))
                }
            }
        }
        val stopped = launch {
            cancellation.waitForRetry(Long.MAX_VALUE)
            operation.cancel()
        }
        try { operation.await() } finally { stopped.cancel() }
    }

    private fun failure(code: String, message: String) = ToolExecutionResult(
        JSONObject().put("error", code).put("message", message).toString(), outcome = ToolOutcome.FAILED)

    @SuppressLint("MissingPermission")
    private suspend fun current(manager: LocationManager, providers: List<String>): Location? =
        suspendCancellableCoroutine { continuation ->
            val signals = providers.map { CancellationSignal() }
            var remaining = providers.size
            fun complete(location: Location?) {
                if (!continuation.isActive) return
                remaining--
                val ageNanos = location?.let { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos }
                val valid = location != null && location.latitude.isFinite() && location.longitude.isFinite() &&
                    location.latitude in -90.0..90.0 && location.longitude in -180.0..180.0 &&
                    ageNanos != null && ageNanos in 0..120_000_000_000L
                if (valid || remaining == 0) {
                    continuation.resume(if (valid) location else null)
                    signals.forEach { it.cancel() }
                }
            }
            continuation.invokeOnCancellation { signals.forEach { it.cancel() } }
            providers.forEachIndexed { index, provider ->
                if (continuation.isActive) {
                    try {
                        manager.getCurrentLocation(provider, LocationRequest.Builder(0L)
                            .setDurationMillis(20_000L).setMaxUpdates(1).build(),
                            signals[index], { command -> Handler(Looper.getMainLooper()).post(command) }) { complete(it) }
                    } catch (denied: SecurityException) {
                        if (continuation.isActive) {
                            signals.forEach { it.cancel() }
                            continuation.resumeWith(Result.failure(denied))
                        }
                    } catch (unavailable: IllegalArgumentException) { complete(null) }
                }
            }
        }
}
