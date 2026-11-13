package com.irio

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.irio.IrioSwitch as Switch
import com.composables.ui.components.Text
import com.composables.ui.theme.colors
import com.composables.ui.theme.onBackgroundColor
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.secondaryColor
import com.composables.ui.theme.largeShape
import com.composables.ui.theme.shapes
import com.composeunstyled.theme.Theme

@Composable
internal fun HapticSettingsCard() {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(HapticPreferences.load(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) settings = HapticPreferences.load(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    SettingsGroup(tr("触感")) {
        Row(
            Modifier.fillMaxWidth()
                .clip(Theme[shapes][largeShape])
                .background(Theme[colors][secondaryColor], Theme[shapes][largeShape])
                .settingsSearchAnchor("laboratory.haptics", horizontalOutset = 0.dp,
                    edge = SettingsSearchEdge.All, verticalOutset = 0.dp, cornerRadius = 6.dp)
                .testTag("haptics-navigation")
                .clickable { context.startActivity(Intent(context, HapticSettingsActivity::class.java)) }
                .settingsRow().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(tr("触感反馈"), modifier = Modifier.weight(1f), color = Theme[colors][onBackgroundColor])
            Box(Modifier.padding(horizontal = 12.dp)) {
                Box(Modifier.width(1.dp).height(24.dp)
                    .background(Theme[colors][mutedColor].copy(alpha = 0.4f))
                    .testTag("haptics-switch-divider"))
            }
            Switch(modifier = Modifier.semantics { contentDescription = tr("触感反馈") }, checked = settings.enabled, onCheckedChange = {
                HapticPreferences.saveEnabled(context, it)
                settings = HapticPreferences.load(context)
            })
        }
    }
}
