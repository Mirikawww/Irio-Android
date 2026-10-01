package com.irio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.ui.components.ButtonStyle
import com.composables.ui.components.Icon
import com.composables.ui.components.IconButton
import com.composables.ui.components.Text
import com.composables.ui.components.Switch
import com.composables.ui.components.TextField
import com.composables.ui.theme.backgroundColor
import com.composables.ui.theme.colors
import com.composables.ui.theme.fieldShape
import com.composables.ui.theme.largeShape
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.onBackgroundColor
import com.composables.ui.theme.onPanelColor
import com.composables.ui.theme.secondaryColor
import com.composables.ui.theme.shapes
import com.composeunstyled.theme.Theme

@Composable
fun MemorySettingsScreen(onBack: () -> Unit) {
    val background = Theme[colors][backgroundColor]
    val content = Theme[colors][onBackgroundColor]
    val muted = Theme[colors][mutedColor]
    var enabled by remember { mutableStateOf(MemoryStore.loadEnabled()) }
    val memory = rememberTextFieldState(MemoryStore.loadContent())
    val currentText by rememberUpdatedState(memory.text.toString())

    DisposableEffect(Unit) {
        onDispose { MemoryStore.saveContent(currentText) }
    }

    SettingsHazeScaffold(
        title = tr("记忆"),
        onBack = onBack,
    ) { topInset, bottomInset ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = bottomInset)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .padding(top = topInset)
                .padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Theme[colors][secondaryColor], Theme[shapes][largeShape])
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(tr("启用记忆"), modifier = Modifier.weight(1f), color = Theme[colors][onPanelColor])
                Switch(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        MemoryStore.saveEnabled(it)
                    },
                )
            }
            Text(
                tr("会作为长期用户信息注入之后的对话，不会自动从聊天里提取。"),
                color = muted,
                fontSize = 13.sp,
            )
            TextField(
                state = memory,
                modifier = Modifier.fillMaxWidth().then(rememberInputFieldBorder()),
                accessibilityLabel = tr("记忆内容"),
                placeholder = {
                    Text(
                        tr("要记住的内容"),
                        style = FieldTextStyle.copy(baselineShift = BaselineShift(0.12f)),
                    )
                },
                textStyle = FieldTextStyle,
                lineHeight = 20.sp,
                fontSize = 16.sp,
                shape = Theme[shapes][fieldShape],
                contentPadding = PaddingValues(12.dp),
                lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 6),
                inputTransformation = InputTransformation.maxLength(MemoryStore.MAX_CHARS),
            )
        }
    }
}
