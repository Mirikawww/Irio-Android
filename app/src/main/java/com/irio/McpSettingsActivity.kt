package com.irio

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.composables.ui.components.*
import com.composables.ui.theme.colors
import com.composables.ui.theme.backgroundColor
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.onPanelColor
import com.composables.ui.theme.secondaryColor
import com.composables.ui.theme.borderColor
import com.composables.ui.theme.largeShape
import com.composables.ui.theme.shapes
import com.composeunstyled.theme.Theme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class McpSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setIrioContent { McpSettingsScreen { finish() } }
    }
}

@Composable
private fun McpSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val store = remember { GitHubMcpStore.of(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val details = rememberBottomSheetState()
    val loginSheet = rememberBottomSheetState()
    val sheets = remember(details, loginSheet) { GitHubMcpSheetState(details, loginSheet) }
    var installation by remember { mutableStateOf<GitHubMcpInstallation?>(null) }
    var loaded by remember { mutableStateOf(false) }
    val login = sheets.login
    // Tokens must not enter savedInstanceState, clipboard, logs or backup files.
    val pat = remember { TextFieldState() }
    var deviceCode by remember { mutableStateOf<GitHubDeviceCode?>(null) }
    val error = sheets.loginError
    val detailsError = sheets.detailsError
    var busy by remember { mutableStateOf(false) }
    var accountLoading by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var cancellation by remember { mutableStateOf<ChatRequestCancellation?>(null) }
    var dismissJob by remember { mutableStateOf<Job?>(null) }
    val content = Theme[colors][onPanelColor]
    val muted = Theme[colors][mutedColor]

    LaunchedEffect(store) {
        installation = withContext(Dispatchers.IO) { store.load() }
        loaded = true
    }
    val accountCancellation = remember(details.targetDetent, installation?.credential?.accessToken) { ChatRequestCancellation() }
    DisposableEffect(accountCancellation) { onDispose { accountCancellation.cancel() } }
    LaunchedEffect(accountCancellation) {
        val current = installation ?: return@LaunchedEffect
        if (details.targetDetent == BottomSheetDetent.Hidden || current.account != null) return@LaunchedEffect
        accountLoading = true
        try {
            val account = withContext(Dispatchers.IO) {
                GitHubAccountClient(current.credential.accessToken, cancellation = accountCancellation).load()
            }
            val updated = withContext(Dispatchers.IO) { store.updateAccount(current.credential.accessToken, account) }
            val latest = installation
            if (updated != null && latest?.credential?.accessToken == current.credential.accessToken) {
                installation = GitHubMcpInstallation(latest.credential, latest.auth, latest.tools, latest.enabled, account)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (!accountCancellation.isCancelled()) sheets.detailsError = tr("无法读取 GitHub 登录账号")
        } finally {
            accountCancellation.cancel()
            accountLoading = false
        }
    }
    fun cancelLoginWork() {
        cancellation?.cancel()
        job?.cancel()
        job = null
        cancellation = null
    }
    fun resetLoginContent() {
        busy = false
        deviceCode = null
        sheets.loginError = null
        pat.edit { replace(0, length, "") }
    }
    fun clearLogin() {
        if (dismissJob?.isActive == true) return
        cancelLoginWork()
        dismissJob = scope.launch {
            sheets.closeLogin()
            // Don't resize the body during the exit animation.
            resetLoginContent()
        }
    }
    DisposableEffect(Unit) {
        onDispose { cancellation?.cancel(); job?.cancel(); dismissJob?.cancel() }
    }
    LaunchedEffect(loginSheet.targetDetent) {
        if (loginSheet.targetDetent == BottomSheetDetent.Hidden && login != null) clearLogin()
    }
    fun openLogin(kind: GitHubLogin) {
        dismissJob?.cancel()
        dismissJob = null
        cancelLoginWork()
        resetLoginContent()
        sheets.openLogin(kind)
    }
    suspend fun install(credential: GitHubCredential, kind: GitHubLogin, cancel: ChatRequestCancellation) {
        val saved = withContext(Dispatchers.IO) {
            val tools = GitHubMcpClient(credential.accessToken, cancellation = cancel).listTools()
            val account = GitHubAccountClient(credential.accessToken, cancellation = cancel).load()
            coroutineContext.ensureActive()
            check(!cancel.isCancelled())
            store.save(GitHubMcpInstallation(credential, kind.name, tools, account = account))
            store.load()?.also { check(it.tools.size == tools.size && it.credential.accessToken == credential.accessToken) }
                ?: error(tr("GitHub 安装保存失败，请重试"))
        }
        installation = saved
        job = null
        cancellation = null
        clearLogin()
    }
    fun startLogin() {
        val kind = login ?: return
        if (busy) return
        sheets.loginError = null
        busy = true
        val auth = GitHubDeviceAuth()
        val cancel = auth.cancellation
        cancellation = cancel
        job = scope.launch {
            try {
                val credential = if (kind == GitHubLogin.PAT) {
                    val value = pat.text.toString().trim()
                    require(value.isNotEmpty() && value.all { it.code in 33..126 })
                    GitHubCredential(value)
                } else {
                    val code = auth.requestCode()
                    deviceCode = code
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("GitHub", code.userCode)))
                    auth.awaitToken(code)
                }
                install(credential, kind, cancel)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (cancellation === cancel) {
                    sheets.loginError = tr("GitHub 登录或连接失败，请检查网络、令牌权限和 Device Flow 设置后重试")
                }
            } finally {
                if (cancellation === cancel) busy = false
            }
        }
    }
    BackHandler(loginSheet.targetDetent != BottomSheetDetent.Hidden || details.targetDetent != BottomSheetDetent.Hidden) {
        if (loginSheet.targetDetent != BottomSheetDetent.Hidden) clearLogin()
        else details.targetDetent = BottomSheetDetent.Hidden
    }
    SettingsHazeScaffold(title = "MCP", onBack = onBack, titleTrailing = { ExperimentalBadge() }) { top, bottom ->
        Column(Modifier.fillMaxSize().padding(bottom = bottom).verticalScroll(rememberScrollState())
            .padding(top = top).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth().background(Theme[colors][secondaryColor], Theme[shapes][largeShape])
                .padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GitHubLogo(48.dp)
                Column(Modifier.weight(1f)) {
                    Text("GitHub", color = content, fontWeight = FontWeight.SemiBold)
                    Text(tr("在对话中管理代码与协作"), fontSize = 13.sp, color = muted)
                }
                GitHubButton(if (installation == null) tr("安装") else tr("管理"), enabled = loaded, compact = true,
                    onClick = { details.targetDetent = BottomSheetDetent.FullyExpanded })
            }
        }
    }
    BottomSheet(state = details, onDismissRequest = { details.targetDetent = BottomSheetDetent.Hidden },
        contentPadding = PaddingValues(top = 32.dp, bottom = 16.dp)) {
        SheetBody {
            GitHubLogo(80.dp, Modifier.align(Alignment.CenterHorizontally))
            Text("GitHub", Modifier.fillMaxWidth(), color = content, fontSize = 24.sp,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(tr("连接 GitHub 官方 MCP，在对话中查阅仓库和代码、管理 Issue 与 Pull Request、查看 Actions 与发布信息。可用工具和操作范围取决于你的账户权限。"),
                modifier = Modifier.padding(horizontal = 24.dp), color = content, fontSize = 16.sp, lineHeight = 24.sp)
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                Text(tr("信息"), color = content,
                    fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                GitHubInfoRow(tr("开发者"), "GitHub, Inc.")
                GitHubInfoDivider()
                GitHubInfoRow(tr("官网"), "github.com", onClick = {
                    if (!openGitHubPage(context, "https://github.com")) sheets.detailsError = tr("未找到可打开网页的浏览器")
                })
                installation?.let { current ->
                    GitHubInfoDivider()
                    GitHubInfoRow(tr("授权方式"), current.auth)
                    GitHubInfoDivider()
                    GitHubAccountRow(current.account, accountLoading)
                    GitHubInfoDivider()
                    GitHubInfoRow(tr("工具数量"), current.tools.size.toString())
                }
            }
            installation?.let { current ->
                Row(Modifier.padding(horizontal = 24.dp).fillMaxWidth()
                    .background(Theme[colors][secondaryColor], Theme[shapes][largeShape])
                    .padding(horizontal = 16.dp, vertical = 8.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("在对话中启用"), color = content, modifier = Modifier.weight(1f))
                    Switch(checked = current.enabled, onCheckedChange = { enabled ->
                        scope.launch {
                            try {
                                installation = withContext(Dispatchers.IO) { store.setEnabled(enabled); store.load() }
                            } catch (_: Exception) { sheets.detailsError = tr("GitHub 安装保存失败，请重试") }
                        }
                    })
                }
            }
            if (installation == null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GitHubButton(tr("安装"), "PAT", Modifier.weight(1f), onClick = { openLogin(GitHubLogin.PAT) })
                    GitHubButton(tr("安装"), "OAuth", Modifier.weight(1f), onClick = { openLogin(GitHubLogin.OAuth) })
                }
            }
            if (installation != null) {
                Button(onClick = { scope.launch {
                    try {
                        withContext(Dispatchers.IO) { store.remove(); check(store.load() == null) }
                        details.hide()
                        installation = null
                    } catch (_: Exception) { sheets.detailsError = tr("无法移除 GitHub 集成，请重试") }
                } }, style = ButtonStyle.Destructive, shape = CircleShape,
                    modifier = Modifier.padding(horizontal = 24.dp).fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(tr("移除集成"))
                }
            }
            if (detailsError != null) {
                Text(detailsError, Modifier.padding(horizontal = 24.dp), color = content)
            }
        }
    }
    if (login != null) BottomSheet(state = loginSheet, onDismissRequest = { clearLogin() },
        contentPadding = PaddingValues(start = 24.dp, top = 32.dp, end = 24.dp, bottom = 16.dp)) {
        SheetBody {
            GitHubLogo(64.dp, Modifier.align(Alignment.CenterHorizontally))
            Text(if (login == GitHubLogin.PAT) tr("使用 PAT 登录") else tr("使用 OAuth 登录"),
                Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                color = content, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            if (login == GitHubLogin.PAT) {
                DismissComposerFocusWhenImeHides()
                Column(Modifier.fillMaxWidth()) {
                    Text(tr("必要权限"), color = content, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    GitHubInfoRow("Contents", tr("读写"))
                    GitHubInfoDivider()
                    GitHubInfoRow("Issues", tr("读写"))
                    GitHubInfoDivider()
                    GitHubInfoRow("Pull requests", tr("读写"))
                    GitHubInfoDivider()
                    GitHubInfoRow("Actions", tr("只读"))
                    GitHubInfoDivider()
                    GitHubInfoRow("Email addresses", tr("只读"))
                    GitHubInfoDivider()
                    GitHubInfoRow(tr("经典令牌"), "repo、read:org、user:email")
                }
                ProviderField(label = "Personal Access Token", state = pat, keyboardType = KeyboardType.Password,
                    mask = true, readOnly = busy, trailingSpace = false)
                GitHubButton(tr("在 GitHub 创建令牌"), modifier = Modifier.fillMaxWidth(), secondary = true, onClick = {
                    if (!openGitHubPage(context, "https://github.com/settings/personal-access-tokens")) sheets.loginError = tr("未找到可打开网页的浏览器")
                })
            } else {
                deviceCode?.let { code ->
                    Text(code.userCode, Modifier.fillMaxWidth().clickable {
                        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("GitHub", code.userCode))) }
                    },
                        color = content, fontWeight = FontWeight.Bold, fontSize = 28.sp, textAlign = TextAlign.Center)
                    GitHubButton(tr("打开 GitHub 授权"), modifier = Modifier.fillMaxWidth(),
                        onClick = { if (!openGitHubPage(context, code.verificationUri)) sheets.loginError = tr("未找到可打开网页的浏览器") })
                }
            }
            error?.let { Text(it, color = content, fontSize = 14.sp) }
            if (deviceCode == null || !busy) {
                GitHubButton(if (busy) tr("验证连接中") else if (login == GitHubLogin.PAT) tr("登录并安装") else tr("获取授权码"),
                    modifier = Modifier.fillMaxWidth(), enabled = !busy && (login != GitHubLogin.PAT || pat.text.isNotBlank()),
                    onClick = { deviceCode = null; startLogin() })
            }
            GitHubButton(tr("取消"), modifier = Modifier.fillMaxWidth(), secondary = true, onClick = { clearLogin() })
        }
    }
}

@Composable
private fun SheetBody(content: @Composable ColumnScope.() -> Unit) {
    val maxHeight = with(LocalDensity.current) { (LocalWindowInfo.current.containerSize.height * 0.85f).toDp() }
    Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).imePadding().navigationBarsPadding()
        .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
}

@Composable
private fun GitHubLogo(size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val dark = Theme[colors][backgroundColor].luminance() < 0.5f
    Image(painterResource(R.drawable.ic_github), contentDescription = null,
        modifier = modifier.size(size).background(Theme[colors][secondaryColor], Theme[shapes][largeShape]).padding(size * 0.2f),
        colorFilter = ColorFilter.tint(if (dark) Color.White else Color.Black))
}

@Composable
private fun GitHubButton(label: String, method: String? = null, modifier: Modifier = Modifier,
    enabled: Boolean = true, compact: Boolean = false, secondary: Boolean = false, onClick: () -> Unit) {
    val dark = Theme[colors][backgroundColor].luminance() < 0.5f
    val ink = if (secondary) Theme[colors][secondaryColor] else if (dark) Color.White else Color.Black
    val foreground = if (secondary) Theme[colors][onPanelColor] else if (dark) Color.Black else Color.White
    Button(onClick = onClick, enabled = enabled, style = ButtonStyle.Ghost, contentColor = foreground,
        modifier = modifier.then(if (compact) Modifier.widthIn(min = 88.dp) else Modifier)
            .heightIn(min = if (compact) 32.dp else 48.dp).clip(CircleShape)
            .background(ink.copy(alpha = if (enabled) 1f else 0.4f)),
        buttonSize = if (compact) ButtonSize.Small else ButtonSize.Default,
        contentPadding = PaddingValues(horizontal = if (compact) 24.dp else 16.dp, vertical = if (compact) 6.dp else 12.dp),
        shape = CircleShape) {
        Text(buildAnnotatedString {
            append(label)
            if (method != null) {
                append(" ")
                withStyle(SpanStyle(color = foreground.copy(alpha = 0.7f))) { append(method) }
            }
        }, color = foreground, fontSize = 16.sp)
    }
}

@Composable
private fun GitHubAccountRow(account: GitHubMcpAccount?, loading: Boolean) {
    val content = Theme[colors][onPanelColor]
    val muted = Theme[colors][mutedColor]
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(tr("登录账号"), Modifier.weight(1f), color = muted, fontSize = 16.sp)
        Row(Modifier.weight(2f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (account != null) {
                Box(Modifier.size(24.dp).clip(CircleShape).background(Theme[colors][secondaryColor]), contentAlignment = Alignment.Center) {
                    Text(account.login.take(1).uppercase(), color = muted, fontSize = 14.sp)
                    if (account.avatarUrl.isNotBlank()) {
                        AsyncImage(model = account.avatarUrl, contentDescription = tr("GitHub 头像"),
                            contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                    }
                }
                Spacer(Modifier.width(8.dp))
            }
            val label = when {
                account == null -> tr(if (loading) "读取中" else "账号暂不可用")
                account.email.isNotBlank() -> account.email
                else -> "${account.login} · ${tr("邮箱未授权")}"
            }
            Text(label, modifier = Modifier.weight(1f, fill = false), color = content, fontSize = 16.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun GitHubInfoRow(subtitle: String, label: String, onClick: (() -> Unit)? = null) {
    val labelColor = if (onClick != null) {
        if (Theme[colors][backgroundColor].luminance() < 0.5f) Color(0xFF93C5FD) else Color(0xFF2563EB)
    } else Theme[colors][onPanelColor]
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(subtitle, Modifier.weight(1f), color = Theme[colors][mutedColor], fontSize = 16.sp)
        Text(label, color = labelColor, fontSize = 16.sp)
    }
}

@Composable
private fun GitHubInfoDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Theme[colors][borderColor]))
}

private fun openGitHubPage(context: android.content.Context, url: String): Boolean =
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.isSuccess
