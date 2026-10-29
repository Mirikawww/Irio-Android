package com.irio

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.ui.components.*
import com.composables.ui.theme.colors
import com.composables.ui.theme.largeShape
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.onPanelColor
import com.composables.ui.theme.secondaryColor
import com.composables.ui.theme.shapes
import com.composeunstyled.LocalContentColor
import com.composeunstyled.theme.Theme

@Composable
internal fun IrioDocsMcpLogo(size: Dp, modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.irio_docs_logo), contentDescription = null, modifier = modifier.size(size))
}

@Composable
internal fun IrioDocsMcpSettingsCard(installed: Boolean, loaded: Boolean, onManage: () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag("irio-docs-mcp")
        .background(Theme[colors][secondaryColor], Theme[shapes][largeShape])
        .settingsSearchAnchor("mcp.irio_docs", horizontalOutset = 0.dp, edge = SettingsSearchEdge.All, verticalOutset = 0.dp)
        .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IrioDocsMcpLogo(48.dp)
        Column(Modifier.weight(1f)) {
            Text("Irio Docs", color = Theme[colors][onPanelColor], fontWeight = FontWeight.SemiBold)
            Text(tr("查询官方文档与更新日志"), fontSize = 13.sp, lineHeight = 18.sp, color = Theme[colors][mutedColor])
        }
        GitHubButton(if (installed) tr("管理") else tr("安装"), enabled = loaded, compact = true, onClick = onManage)
    }
}

@Composable
internal fun IrioDocsMcpManagement(
    installed: Boolean,
    enabled: Boolean,
    toolCount: Int,
    onWebsite: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onInstall: () -> Unit,
    onRemove: () -> Unit,
) {
    val content = Theme[colors][onPanelColor]
    Text("Irio Docs", Modifier.fillMaxWidth(), color = content, fontSize = 24.sp,
        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    Text(tr("连接 Irio Docs MCP，在对话中搜索官方文档、读取说明和查询版本更新。仅访问公开资料，无需登录。"),
        modifier = Modifier.padding(horizontal = 24.dp), color = content, fontSize = 16.sp, lineHeight = 24.sp)
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        Text(tr("信息"), color = content, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        GitHubInfoRow(tr("开发者"), "Pirikawww")
        GitHubInfoDivider()
        GitHubInfoRow(tr("官网"), "irio.mirikawww.workers.dev", onClick = onWebsite)
        if (installed) {
            GitHubInfoDivider()
            GitHubInfoRow(tr("工具数量"), toolCount.toString())
        }
    }
    if (installed) {
        Row(Modifier.padding(horizontal = 24.dp).fillMaxWidth()
            .background(Theme[colors][secondaryColor], Theme[shapes][largeShape])
            .padding(horizontal = 16.dp, vertical = 8.dp).heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(tr("在对话中启用"), color = content, modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = onEnabledChange,
                modifier = Modifier.testTag("irio-docs-mcp-enabled"))
        }
        Button(onClick = onRemove, style = ButtonStyle.Destructive, shape = ContinuousCapsule,
            modifier = Modifier.padding(horizontal = 24.dp).fillMaxWidth().heightIn(min = 48.dp)) {
            Text(tr("移除集成"), color = LocalContentColor.current)
        }
    } else {
        GitHubButton(tr("安装"), modifier = Modifier.padding(horizontal = 24.dp).fillMaxWidth().testTag("irio-docs-mcp-install"), onClick = onInstall)
    }
}
