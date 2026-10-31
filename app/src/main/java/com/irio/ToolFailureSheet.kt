package com.irio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.ui.components.BottomSheetState
import com.composables.ui.components.Text
import com.composables.ui.theme.colors
import com.composables.ui.theme.onPanelColor
import com.composables.ui.theme.panelColor
import com.composeunstyled.theme.Theme
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun ToolFailureSheet(event: ProcessEvent, state: BottomSheetState, onDismiss: () -> Unit) {
    val terminal = event.toolName == LinuxShellTool.NAME
    val foreground = if (terminal) Color(0xFFE8E8E8) else Theme[colors][onPanelColor]
    val background = if (terminal) Color.Black else Theme[colors][panelColor]
    ScaledBottomSheet(
        state = state,
        onDismissRequest = onDismiss,
        backgroundColor = background,
        contentColor = foreground,
        contentPadding = PaddingValues(start = 16.dp, top = 32.dp, end = 16.dp, bottom = 16.dp),
        scrollableBody = true,
    ) {
        val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
        val arguments = remember(event.toolArguments) {
            runCatching { JSONObject(event.toolArguments).toString(2) }.getOrElse {
                runCatching { JSONArray(event.toolArguments).toString(2) }.getOrDefault(event.toolArguments)
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(min = windowHeight * 0.32f, max = windowHeight * 0.85f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = LocalBottomSheetBottomPadding.current),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(tr("错误信息"), color = foreground, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
            SelectionContainer {
                Text(event.content.ifBlank { tr("工具执行失败") }, color = foreground, fontSize = 16.sp,
                    lineHeight = 22.sp, fontFamily = if (terminal) FontFamily.Monospace else FontFamily.Default)
            }
            Text(tr("输入参数"), color = foreground, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
            SelectionContainer {
                Text(arguments.ifBlank { tr("未记录输入参数") }, color = foreground, fontSize = 14.sp,
                    lineHeight = 20.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
