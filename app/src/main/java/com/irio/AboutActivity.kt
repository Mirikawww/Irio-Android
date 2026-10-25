package com.irio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.composables.ui.components.Text
import com.composables.ui.components.ButtonStyle
import com.composables.ui.theme.colors
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.onBackgroundColor
import com.composables.ui.theme.secondaryColor
import com.composables.ui.theme.largeShape
import com.composables.ui.theme.shapes
import com.composeunstyled.theme.Theme

internal const val IRIO_PROJECT_URL = "https://github.com/Mirikawww/Irio-Android"
internal const val IRIO_DOCS_URL = "https://irio.mirikawww.workers.dev"

class AboutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setIrioContent {
            AboutScreen(onBack = { finish() }, onAcknowledgements = {
                startActivity(Intent(this, AcknowledgementsActivity::class.java))
            })
        }
    }
}

internal fun Context.openAboutLink(url: String): Boolean = runCatching {
    require(Uri.parse(url).scheme == "https")
    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        if (this@openAboutLink !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    })
}.isSuccess

@Composable
internal fun AboutScreen(onBack: () -> Unit, onAcknowledgements: () -> Unit) {
    val context = LocalContext.current
    val softwareInfo = remember(context) { androidSoftwareInfo(context) }
    val iconSize = with(LocalDensity.current) { 80.dp.roundToPx() }
    val appIcon = remember(context, iconSize) {
        context.packageManager.getApplicationIcon(context.applicationInfo)
            .toBitmap(iconSize, iconSize).asImageBitmap()
    }
    var linkError by remember { mutableStateOf(false) }
    var errorNonce by remember { mutableStateOf(0) }
    fun openLink(url: String) {
        if (!context.openAboutLink(url)) { linkError = true; errorNonce++ }
    }
    SettingsHazeScaffold(title = tr("关于"), onBack = onBack) { topInset, bottomInset ->
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().padding(bottom = bottomInset)
                    .verticalScroll(rememberScrollState()).padding(top = topInset)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    Modifier.testTag("about-identity").fillMaxWidth().padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(appIcon, contentDescription = null, modifier = Modifier.size(80.dp))
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.app_name), fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold,
                        color = Theme[colors][onBackgroundColor])
                    Spacer(Modifier.height(8.dp))
                    SelectionContainer {
                        Text(softwareInfo, modifier = Modifier.testTag("about-version").fillMaxWidth()
                            .settingsSearchAnchor("about.version"), color = Theme[colors][mutedColor],
                            fontSize = 12.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
                    }
                }
                ListRowButton(label = tr("鸣谢"), icon = IrioIcons.Sparkles,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    searchId = "about.acknowledgements", onClick = onAcknowledgements)
                Column(
                    Modifier.testTag("about-resources").fillMaxWidth()
                        .clip(Theme[shapes][largeShape]).background(Theme[colors][secondaryColor]),
                ) {
                    ListRowButton(label = tr("项目地址"), iconPainter = painterResource(R.drawable.ic_github),
                        supportingText = IRIO_PROJECT_URL, style = ButtonStyle.Ghost,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        searchId = "about.project", onClick = { openLink(IRIO_PROJECT_URL) })
                    ListRowButton(label = tr("文档与更新日志"), icon = IrioIcons.Menu,
                        supportingText = IRIO_DOCS_URL, style = ButtonStyle.Ghost,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        searchId = "about.docs", onClick = { openLink(IRIO_DOCS_URL) })
                }
            }
            IrioSnackbar(visible = linkError, message = tr("无法打开链接"), bottomInset = bottomInset,
                onDismiss = { linkError = false }, restartKey = errorNonce)
        }
    }
}
