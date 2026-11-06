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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.composables.ui.components.Text
import com.composables.ui.components.TextField
import com.composables.ui.theme.colors
import com.composables.ui.theme.fieldShape
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.onBackgroundColor
import com.composables.ui.theme.shapes
import com.composeunstyled.theme.Theme

class PersonalizationSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setIrioContent { PersonalizationSettingsScreen(onBack = { finish() }) }
    }
}

@Composable
internal fun PersonalizationSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prompt = rememberTextFieldState(SystemPromptStore.load(context))
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, prompt) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                SystemPromptStore.save(context, prompt.text.toString())
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            SystemPromptStore.save(context, prompt.text.toString())
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DismissComposerFocusWhenImeHides()
    SettingsHazeScaffold(
        title = tr("个性化"),
        onBack = onBack,
    ) { topInset, bottomInset ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = bottomInset)
                .verticalScroll(rememberScrollState())
                .padding(top = topInset)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(tr("系统提示词"), color = Theme[colors][onBackgroundColor])
            TextField(
                state = prompt,
                modifier = Modifier.fillMaxWidth()
                    .settingsSearchAnchor("personalization.prompt", horizontalOutset = 0.dp,
                        edge = SettingsSearchEdge.All, verticalOutset = 0.dp)
                    .then(rememberInputFieldBorder()),
                accessibilityLabel = tr("系统提示词"),
                placeholder = { Text(tr("以系统身份注入每次对话的指令"), color = Theme[colors][mutedColor]) },
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
}
