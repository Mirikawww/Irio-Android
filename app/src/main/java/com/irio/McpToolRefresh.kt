package com.irio

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

internal suspend fun refreshGitHubMcpTools(
    store: GitHubMcpStore,
    cancellation: ChatRequestCancellation,
    discover: (String, ChatRequestCancellation) -> List<McpTool> = { token, cancel ->
        GitHubMcpClient(token, cancellation = cancel).listTools()
    },
): GitHubMcpInstallation {
    val current = checkNotNull(store.refreshIfNeeded(allowDisabled = true))
    val tools = discover(current.credential.accessToken, cancellation)
    coroutineContext.ensureActive()
    check(!cancellation.isCancelled())
    return checkNotNull(store.updateTools(current.credential.accessToken, tools))
}

internal suspend fun refreshTodoistMcpTools(
    store: TodoistMcpStore,
    cancellation: ChatRequestCancellation,
    discover: (String, ChatRequestCancellation) -> List<McpTool> = { token, cancel ->
        TodoistMcpClient(token, cancellation = cancel).listTools()
    },
): TodoistMcpInstallation {
    val current = checkNotNull(store.refreshIfNeeded())
    val tools = discover(current.credential.accessToken, cancellation)
    coroutineContext.ensureActive()
    check(!cancellation.isCancelled())
    return checkNotNull(store.updateTools(current.credential.accessToken, tools))
}

internal suspend fun refreshIrioDocsMcpTools(
    context: Context,
    cancellation: ChatRequestCancellation,
    discover: (ChatRequestCancellation) -> List<McpTool> = { cancel ->
        IrioDocsMcpClient(cancellation = cancel).listTools()
    },
): Int {
    check(IrioDocsMcpStore.loadInstalled(context))
    val tools = discover(cancellation)
    coroutineContext.ensureActive()
    check(!cancellation.isCancelled())
    IrioDocsMcpStore.saveTools(context, tools)
    return IrioDocsMcpStore.tools(context).size
}

@Composable
internal fun McpRefreshToolsButton(refreshing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    GitHubButton(
        label = tr(if (refreshing) "刷新工具中" else "刷新工具"),
        modifier = modifier.padding(horizontal = 24.dp).fillMaxWidth().testTag("mcp-refresh-tools"),
        enabled = !refreshing,
        secondary = true,
        onClick = onClick,
    )
}
