package com.irio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import com.kyant.shapes.RoundedRectangle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.ui.components.Icon
import kotlinx.coroutines.launch
import com.composables.ui.components.Text
import com.composables.ui.theme.borderColor
import com.composables.ui.theme.colors
import com.composables.ui.theme.onPanelColor
import com.composables.ui.theme.secondaryColor
import com.composeunstyled.theme.Theme

/** 输入框上方的后台命令。没有在跑的命令时不占位，展开前只显示条数。 */
@Composable
internal fun LinuxBackgroundBar(onOpen: (Long) -> Unit) {
    var jobs by remember { mutableStateOf(LinuxBackgroundJobs.running()) }
    var expanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        val refresh = {
            scope.launch { jobs = LinuxBackgroundJobs.running() }
            Unit
        }
        jobs = LinuxBackgroundJobs.running()
        val remove = LinuxBackgroundJobs.subscribe(refresh)
        onDispose { remove() }
    }
    LaunchedEffect(jobs.isEmpty()) {
        if (jobs.isEmpty()) expanded = false
    }
    if (jobs.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .background(Theme[colors][secondaryColor], RoundedRectangle(10.dp))
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (expanded) IrioIcons.ChevronDown else IrioIcons.ChevronRight,
                contentDescription = if (expanded) tr("收起") else tr("展开"),
                modifier = Modifier.size(18.dp),
                tint = Theme[colors][onPanelColor],
            )
            Text(
                tr("%s 个后台命令").format(jobs.size),
                modifier = Modifier.padding(start = 4.dp),
                color = Theme[colors][onPanelColor],
                fontSize = 13.sp,
            )
        }
        if (expanded) {
            Column(modifier = Modifier.fillMaxWidth()) {
                jobs.forEachIndexed { index, job ->
                    if (index > 0) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .height(1.dp)
                                .background(Theme[colors][borderColor]),
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            job.command,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onOpen(job.id) },
                            color = Theme[colors][onPanelColor],
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(
                            imageVector = IrioIcons.Square,
                            contentDescription = tr("停止"),
                            modifier = Modifier
                                .clickable { LinuxBackgroundJobs.stop(job.id) }
                                .padding(4.dp)
                                .size(22.dp),
                            tint = Theme[colors][onPanelColor],
                        )
                    }
                }
            }
        }
    }
}
