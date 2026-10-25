package com.irio

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal sealed interface StartupUpdateNotice {
    data class Checked(val result: UpdateCheckResult.Available) : StartupUpdateNotice
    data class Failed(val messageKey: String) : StartupUpdateNotice
}

/** Process-scoped gate and pending result, independent of Activity recreation and recomposition. */
internal class StartupUpdateCheck(private val scope: CoroutineScope) {
    private val started = AtomicBoolean(false)
    private val pending = MutableStateFlow<StartupUpdateNotice?>(null)
    val notice = pending.asStateFlow()

    fun start(check: suspend () -> UpdateCheckResult): Boolean {
        if (!started.compareAndSet(false, true)) return false
        scope.launch {
            pending.value = try {
                when (val result = check()) {
                    is UpdateCheckResult.Available -> StartupUpdateNotice.Checked(result)
                    UpdateCheckResult.UpToDate, UpdateCheckResult.NoReleases -> null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: UpdateCheckException) {
                StartupUpdateNotice.Failed(e.messageKey)
            } catch (_: IOException) {
                StartupUpdateNotice.Failed("检查更新失败，请检查网络后重试")
            }
        }
        return true
    }

    fun dismiss(notice: StartupUpdateNotice) {
        pending.compareAndSet(notice, null)
    }
}

internal object StartupUpdates {
    val check = StartupUpdateCheck(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))

    fun start(context: Context) {
        val app = context.applicationContext
        check.start {
            val version = app.packageManager.getPackageInfo(app.packageName,
                PackageManager.PackageInfoFlags.of(0)).versionName.orEmpty()
            GitHubUpdateClient().check(version, UpdateChannel.load(app))
        }
    }
}

internal fun startupUpdateMessage(notice: StartupUpdateNotice, label: (String) -> String = ::tr): String =
    when (notice) {
        is StartupUpdateNotice.Failed -> label(notice.messageKey)
        is StartupUpdateNotice.Checked -> label("发现新版本：%s").format(notice.result.release.tag)
    }

/** One shared snackbar: existing title notices take priority; startup results wait their turn. */
@Composable
internal fun BoxScope.ChatNoticeSnackbar(
    titleNotice: String?,
    titleNoticeNonce: Int,
    onDismissTitle: () -> Unit,
    bottomInset: Dp,
    startupCheck: StartupUpdateCheck = StartupUpdates.check,
) {
    val startupNotice by startupCheck.notice.collectAsState()
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    var viewingUpdate by remember { mutableStateOf<GitHubUpdate?>(null) }
    var linkError by remember { mutableStateOf(false) }
    val showStartup = startupNotice != null && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val update = (startupNotice as? StartupUpdateNotice.Checked)?.result?.release
    val canViewUpdate = titleNotice == null && !linkError && update != null
    IrioSnackbar(
        visible = viewingUpdate == null && (titleNotice != null || showStartup ||
            (linkError && lifecycleState.isAtLeast(Lifecycle.State.RESUMED))),
        message = titleNotice ?: if (linkError) tr("无法打开链接") else
            startupNotice?.let { startupUpdateMessage(it) }.orEmpty(),
        bottomInset = bottomInset,
        onDismiss = {
            if (titleNotice != null) onDismissTitle()
            else if (linkError) linkError = false
            else startupNotice?.let(startupCheck::dismiss)
        },
        restartKey = titleNoticeNonce,
        actionLabel = if (canViewUpdate) tr("查看") else null,
        onAction = if (canViewUpdate) ({
            viewingUpdate = update
            startupNotice?.let(startupCheck::dismiss)
        }) else null,
    )
    GitHubUpdateDialog(
        release = viewingUpdate,
        onDismiss = { viewingUpdate = null },
        onLinkError = { linkError = true },
    )
}
