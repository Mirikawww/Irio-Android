package com.irio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.ui.components.ButtonStyle
import com.composables.ui.components.Icon
import com.composables.ui.components.Text
import com.composables.ui.theme.colors
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.onBackgroundColor
import com.composables.ui.theme.secondaryColor
import com.composables.ui.theme.largeShape
import com.composables.ui.theme.shapes
import com.composeunstyled.theme.Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AcknowledgementsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setIrioContent { AcknowledgementsScreen(onBack = { finish() }) }
    }
}

@Composable
internal fun AcknowledgementsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var expandedIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var linkError by remember { mutableStateOf(false) }
    var errorNonce by remember { mutableStateOf(0) }
    SettingsHazeScaffold(title = tr("鸣谢"), onBack = onBack) { topInset, bottomInset ->
        Box(Modifier.fillMaxSize().padding(bottom = bottomInset)) {
            AndroidComposeRecyclerList(
                items = acknowledgements, key = { it.id }, modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topInset + 16.dp, bottom = 16.dp),
                itemSpacing = 12.dp,
                maintainEndPosition = true,
            ) { credit ->
                AcknowledgementCard(credit = credit, expanded = credit.id in expandedIds,
                    onToggle = { expandedIds = if (credit.id in expandedIds) expandedIds - credit.id else expandedIds + credit.id },
                    onProject = { if (!context.openAboutLink(credit.projectUrl)) { linkError = true; errorNonce++ } })
            }
            IrioSnackbar(visible = linkError, message = tr("无法打开链接"), bottomInset = 0.dp,
                onDismiss = { linkError = false }, restartKey = errorNonce)
        }
    }
}

@Composable
internal fun AcknowledgementCard(credit: Acknowledgement, expanded: Boolean, onToggle: () -> Unit, onProject: () -> Unit) {
    val foreground = Theme[colors][onBackgroundColor]
    val muted = Theme[colors][mutedColor]
    val shape = Theme[shapes][largeShape]
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = irioSpring(),
        label = "acknowledgementChevron",
    )
    Column(Modifier.testTag("credit-${credit.id}").fillMaxWidth().clip(shape).background(Theme[colors][secondaryColor])) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onToggle)
                .semantics { stateDescription = tr(if (expanded) "已展开" else "已折叠") }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(credit.name, color = foreground, fontSize = 16.sp, lineHeight = 22.sp)
                credit.adaptation?.let { Text(tr(it), color = foreground,
                    fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium) }
                Text(tr(credit.purpose), color = muted, fontSize = 13.sp, lineHeight = 18.sp)
                Text(tr(credit.licenseLabel), color = muted, fontSize = 12.sp, lineHeight = 16.sp)
            }
            Icon(imageVector = IrioIcons.ChevronDown, contentDescription = null, tint = muted,
                modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = chevronRotation })
        }
        AnimatedVisibility(expanded, enter = expandVertically(irioSpring()), exit = shrinkVertically(irioSpring())) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                credit.modification?.let { Text(tr(it), color = foreground, fontSize = 13.sp, lineHeight = 18.sp) }
                ListRowButton(label = tr("项目地址"), style = ButtonStyle.Ghost, onClick = onProject,
                    supportingText = credit.projectUrl,
                    contentPadding = PaddingValues(0.dp), icon = IrioIcons.Braces)
                CreditLicenseText(credit)
            }
        }
    }
}

@Composable
private fun CreditLicenseText(credit: Acknowledgement) {
    val context = LocalContext.current
    val text by produceState<List<Pair<String, String>>?>(null, credit.id, context) {
        value = withContext(Dispatchers.IO) {
            credit.licenses.map { license -> license.label to runCatching {
                context.assets.open(license.assetPath).bufferedReader().use { it.readText() }
            }.getOrElse { tr("许可证暂时无法读取") } }
        }
    }
    if (text == null) {
        Text(tr("正在读取许可证"), color = Theme[colors][mutedColor], fontSize = 12.sp, lineHeight = 18.sp)
    } else {
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                text.orEmpty().forEach { (label, content) ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(tr(label), color = Theme[colors][onBackgroundColor], fontSize = 13.sp,
                            lineHeight = 18.sp, fontWeight = FontWeight.Medium)
                        Text(content, modifier = Modifier.testTag("credit-license-${credit.id}"),
                            color = Theme[colors][mutedColor], fontSize = 12.sp, lineHeight = 18.sp)
                    }
                }
            }
        }
    }
}
