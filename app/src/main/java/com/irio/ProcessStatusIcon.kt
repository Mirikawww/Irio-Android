package com.irio

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.composables.ui.components.Icon
import com.composables.ui.theme.colors
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.onBackgroundColor
import com.composeunstyled.theme.Theme

@Composable
internal fun ProcessStatusLine(
    event: ProcessEvent,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        ProcessStatusIcon(event)
        Spacer(Modifier.width(6.dp))
        Row(
            Modifier.weight(1f, fill = false),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            content = content,
        )
    }
}

@Composable
private fun ProcessStatusIcon(event: ProcessEvent) {
    // Read the resolved theme in recycled transcript rows as well as normal compositions.
    LocalResolvedTheme.current
    val name = event.toolName
    val logo = when {
        name.startsWith(GitHubMcpToolProvider.PREFIX) -> R.drawable.ic_github
        name.startsWith(TodoistMcpToolProvider.PREFIX) -> R.drawable.ic_todoist
        name.startsWith(IrioDocsMcpToolProvider.PREFIX) -> R.drawable.irio_docs_logo
        else -> null
    }
    if (logo != null) {
        val brand = when (logo) {
            R.drawable.ic_github -> "github"
            R.drawable.ic_todoist -> "todoist"
            else -> "irio-docs"
        }
        Image(
            painter = painterResource(logo),
            contentDescription = null,
            modifier = Modifier.size(16.dp).testTag("process-status-icon:$brand"),
            colorFilter = if (logo == R.drawable.ic_github)
                ColorFilter.tint(Theme[colors][onBackgroundColor]) else null,
        )
    } else {
        val vector = processStatusVector(event)
        Icon(
            imageVector = vector,
            contentDescription = null,
            modifier = Modifier.size(16.dp).testTag("process-status-icon:${vector.name.removePrefix("lucide:")}"),
            tint = Theme[colors][mutedColor],
        )
    }
}

internal fun processStatusVector(event: ProcessEvent): ImageVector = when {
    event.kind == ProcessEventKind.THINKING -> IrioIcons.Brain
    event.kind == ProcessEventKind.SEARCH -> IrioIcons.Search
    else -> when (event.toolName) {
        WebSearchTool.NAME, CommonsImageTools.SEARCH -> IrioIcons.Search
        WebFetchTool.NAME -> IrioIcons.Globe
        ImageGenTool.NAME, CommonsImageTools.SHOW, CommonsImageTools.VIEW -> IrioIcons.Image
        VideoGenTool.NAME -> IrioIcons.Play
        TimeTool.NAME, HistoryTool.NAME -> IrioIcons.Clock
        UserLocationTool.NAME -> IrioIcons.MapPin
        LinguaLibreTool.NAME -> IrioIcons.Speech
        ToolGuideSearchTool.NAME -> IrioIcons.BookOpen
        MemoryTool.GET, MemoryTool.UPDATE -> IrioIcons.Brain
        LinuxShellTool.NAME, LinuxBackgroundTool.NAME -> IrioIcons.Terminal
        ReactionTool.NAME -> IrioIcons.FaceSlightlySmilingPlus
        ColoredBubbleTool.NAME -> IrioIcons.Palette
        MermaidTool.NAME -> IrioIcons.Braces
        in SubagentTools.NAMES -> IrioIcons.Bot
        else -> IrioIcons.Wrench
    }
}
