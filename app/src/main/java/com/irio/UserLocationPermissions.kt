package com.irio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Main-thread session: concurrent calls share a single native permission dialog. */
internal class UserLocationPermissionSession(
    private val granted: () -> Boolean,
    private val foreground: () -> Boolean,
    private val launch: () -> Unit,
) {
    private var pending: CompletableDeferred<Boolean>? = null
    private var closed = false

    val isForeground: Boolean get() = !closed && foreground()
    val hasPendingRequest: Boolean get() = pending != null

    suspend fun request(): Boolean {
        if (closed) return false
        if (granted()) return true
        val request = pending ?: run {
            if (!foreground()) return false
            CompletableDeferred<Boolean>().also {
                pending = it
                try { launch() } catch (error: RuntimeException) {
                    pending = null
                    it.complete(false)
                }
            }
        }
        // A cancelled caller must not cancel another caller or relaunch a still-open dialog.
        return request.await()
    }

    fun complete() {
        pending?.complete(granted())
        pending = null
    }

    fun close() {
        closed = true
        pending?.complete(false)
        pending = null
    }
}

internal object UserLocationPermissions {
    private val sessions = linkedSetOf<UserLocationPermissionSession>()

    fun hasPermission(context: Context): Boolean = hasPrecisePermission(context) ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasPrecisePermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun attach(next: UserLocationPermissionSession) { sessions.add(next) }
    fun detach(previous: UserLocationPermissionSession) {
        sessions.remove(previous)
        previous.close()
    }

    suspend fun request(): Boolean = withContext(Dispatchers.Main.immediate) {
        // Reuse an open native dialog, otherwise choose a resumed host. Keep stopped hosts so
        // returning from the subagent Activity restores the original chat without remounting it.
        val session = sessions.lastOrNull { it.hasPendingRequest }
            ?: sessions.lastOrNull { it.isForeground }
        session?.request() ?: false
    }
}

/** Register the launcher at the chat root, but never request permission until a tool calls it. */
@Composable
internal fun UserLocationPermissionHost() {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val holder = remember { arrayOfNulls<UserLocationPermissionSession>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        holder[0]?.complete()
    }
    DisposableEffect(context, owner, launcher) {
        val session = UserLocationPermissionSession(
            granted = { UserLocationPermissions.hasPermission(context) },
            foreground = { owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) },
            launch = { launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) },
        )
        holder[0] = session
        UserLocationPermissions.attach(session)
        onDispose {
            holder[0] = null
            UserLocationPermissions.detach(session)
        }
    }
}
