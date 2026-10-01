package com.irio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.ui.components.ButtonStyle
import com.composables.ui.components.Icon
import com.composables.ui.components.IconButton
import com.composables.ui.components.Text
import com.composables.ui.components.TextField
import com.composables.ui.theme.backgroundColor
import com.composables.ui.theme.colors
import com.composables.ui.theme.fieldShape
import com.composables.ui.theme.onBackgroundColor
import com.composables.ui.theme.shapes
import com.composeunstyled.theme.Theme

class SystemPromptSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setIrioContent { SystemPromptSettingsScreen(onBack = { finish() }) }
    }
}

@Composable
private fun SystemPromptSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val state = rememberTextFieldState(SystemPromptStore.load(context))
    val background = Theme[colors][backgroundColor]
    val content = Theme[colors][onBackgroundColor]
    Scaffold(
        containerColor = background,
        contentWindowInsets = irioSystemUiInsets(),
        topBar = {
            IrioToolbar(
                title = { Text(tr("系统提示词")) },
                backgroundColor = background,
                contentColor = content,
                leading = {
                    IconButton(onClick = onBack, style = ButtonStyle.Ghost) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = tr("返回"),
                            tint = content,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextField(
                state = state,
                modifier = Modifier.fillMaxWidth().then(rememberInputFieldBorder()),
                accessibilityLabel = tr("系统提示词"),
                placeholder = { Text(tr("以系统身份注入每次对话的指令")) },
                textStyle = FieldTextStyle,
                lineHeight = 20.sp,
                fontSize = 16.sp,
                shape = Theme[shapes][fieldShape],
                contentPadding = PaddingValues(12.dp),
                lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 5, maxHeightInLines = 12),
                inputTransformation = InputTransformation.maxLength(SystemPromptStore.MAX_CHARS),
            )
        }
    }
    DisposableEffect(Unit) {
        onDispose { SystemPromptStore.save(context, state.text.toString()) }
    }
}
