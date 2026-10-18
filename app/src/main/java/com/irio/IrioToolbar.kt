package com.irio


import androidx.compose.animation.core.Easing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.ui.components.ButtonSize
import com.composables.ui.components.ButtonStyle
import com.composables.ui.components.Icon
import com.composables.ui.components.IconButton
import com.composables.ui.components.Text
import com.composables.ui.theme.backgroundColor as backgroundColorToken
import com.composables.ui.theme.colors
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.onBackgroundColor as onBackgroundColorToken
import com.composables.ui.theme.panelColor
import com.composables.ui.theme.primaryColor as primaryColorToken
import com.composables.ui.theme.onPrimaryColor as onPrimaryColorToken
import com.composeunstyled.LocalTextStyle
import com.composeunstyled.ProvideContentColor
import com.composeunstyled.ProvideTextStyle
import com.composeunstyled.theme.Theme
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/**
 * 页面顶栏。Composables `Toolbar` Medium 固定 64.dp，默认按钮只有 48.dp，
 * 控件上下会空出一截。这里按内容高度包一层，保留同样的 inset、12.dp 左右边距和标题样式。
 *
 * Scaffold 按顶栏实测高度叠内容，焦点描边（2dp 宽 + 2dp 外偏）会画到布局外。
 * 底部 [IrioToolbarOverflow] 只把这块溢出让给内容，不把按钮再垫高。
 */
@Composable
fun IrioToolbar(
    title: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    backgroundColor: Color = Theme[colors][backgroundColorToken],
    contentColor: Color = Theme[colors][onBackgroundColorToken],
    compact: Boolean = false,
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    ProvideContentColor(contentColor) {
        Column(modifier = modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (backgroundColor == Color.Transparent) Modifier
                        else Modifier.background(backgroundColor)
                    )
                    .windowInsetsPadding(
                        WindowInsets.systemBars.only(
                            WindowInsetsSides.Horizontal + WindowInsetsSides.Top,
                        )
                    )
                    .then(if (compact) Modifier else Modifier.heightIn(min = 48.dp))
                    .padding(
                        start = 12.dp,
                        end = 12.dp,
                        top = if (compact) 6.dp else 0.dp,
                    ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(if (compact) Alignment.TopStart else Alignment.CenterStart),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (leading != null) {
                        leading()
                    }
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ProvideTextStyle(
                            LocalTextStyle.current.merge(IrioToolbarTitleStyle),
                        ) {
                            title()
                        }
                    }
                    if (trailing != null) {
                        trailing()
                    }
                }
            }
            if (!compact) {
                Spacer(Modifier.height(IrioToolbarOverflow))
            }
        }
    }
}

internal val ToolbarCapsuleShape = ContinuousCapsule

/**
 * 圆形图标按钮。强调色只填这个圆，图标用对比色，不跟强调色。
 * [active] 为 false（例如发不出去）时用淡圆，不跟强调色。
 */
@Composable
internal fun AccentCircleIconButton(
    onClick: () -> Unit,
    imageVector: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = true,
    buttonSize: Dp = 32.dp,
    iconSize: Dp = 20.dp,
) {
    val showAccent = active && enabled
    val fill: Color
    val iconTint: Color
    if (!showAccent) {
        fill = Theme[colors][mutedColor].copy(alpha = 0.28f)
        iconTint = Theme[colors][mutedColor]
    } else {
        fill = Theme[colors][primaryColorToken]
        iconTint = Theme[colors][onPrimaryColorToken]
    }
    IconButton(
        onClick = onClick,
        enabled = enabled,
        style = ButtonStyle.Ghost,
        buttonSize = ButtonSize.Small,
        shape = ContinuousCapsule,
        modifier = modifier
            .size(buttonSize)
            .background(fill, ContinuousCapsule)
            .semantics { this.contentDescription = contentDescription },
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize),
            tint = iconTint,
        )
    }
}

@Composable
internal fun ToolbarCapsule(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(ToolbarCapsuleShape)
            .background(Theme[colors][panelColor]),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

internal val IrioToolbarOverflow = 8.dp

/** 胶囊下方再留一段，模糊淡出不要贴着按钮底边。 */
internal val ChatToolbarHazeBleed = 28.dp

/** 输入框上方再留一段，模糊淡出不要贴着字段顶边。 */
internal val ChatComposerHazeBleed = ChatToolbarHazeBleed

/** Reverse the curve as well as the intensities so the top mirrors the unchanged bottom. */
private val ChatToolbarHazeEasing = Easing { 1f - IrioEaseOut.transform(1f - it) }

/**
 * 毛玻璃：主题色半透明着色 + 模糊，渐变铺满整段 overlay。
 * 顶部与底部的曲线和渐变方向互为镜像。
 */
@Composable
internal fun chatToolbarHazeStyle(): HazeBlurStyle = chatEdgeHazeStyle(bottomStrong = false)

@Composable
internal fun chatComposerHazeStyle(): HazeBlurStyle = chatEdgeHazeStyle(bottomStrong = true)

@Composable
private fun chatEdgeHazeStyle(bottomStrong: Boolean): HazeBlurStyle {
    val container = Theme[colors][backgroundColorToken]
    val frost = if (container.luminance() >= 0.5f) 0.83f else 0.9f
    return remember(container, frost, bottomStrong) {
        HazeBlurStyle {
            blurRadius(40.dp)
            backgroundColor(container)
            colorEffects(
                listOf(HazeColorEffect.tint(container.copy(alpha = frost))),
            )
            progressive(
                HazeProgressive.verticalGradient(
                    easing = if (bottomStrong) IrioEaseOut else ChatToolbarHazeEasing,
                    startIntensity = if (bottomStrong) 0f else 1f,
                    endIntensity = if (bottomStrong) 1f else 0f,
                ),
            )
            mask(
                Brush.verticalGradient(
                    if (bottomStrong) listOf(Color.Transparent, Color.Black)
                    else listOf(Color.Black, Color.Transparent),
                ),
            )
        }
    }
}

private val IrioToolbarTitleStyle = TextStyle(
    fontSize = 20.sp,
    lineHeight = 24.sp,
    fontWeight = FontWeight.Medium,
)

@Composable
internal fun SettingsTitleCapsule(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    titleTrailing: (@Composable () -> Unit)? = null,
    onHeightChanged: (Int) -> Unit = {},
    titleFontWeight: FontWeight = FontWeight.Medium,
) {
    val content = Theme[colors][onBackgroundColorToken]
    Box(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { onHeightChanged(it.height) }
            .windowInsetsPadding(
                WindowInsets.systemBars.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Top,
                ),
            )
            .padding(start = 12.dp, end = 12.dp, top = 6.dp),
    ) {
        ToolbarCapsule {
            Row(
                modifier = Modifier.padding(end = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onBack,
                    style = ButtonStyle.Ghost,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = tr("返回"),
                        tint = content,
                    )
                }
                Text(
                    title,
                    modifier = Modifier.weight(1f, fill = false),
                    color = content,
                    fontSize = 18.sp,
                    lineHeight = 22.sp,
                    fontWeight = titleFontWeight,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (titleTrailing != null) {
                    titleTrailing()
                }
            }
        }
    }
}

/**
 * 设置页顶栏。毛玻璃和主页同一段（上强下弱），返回和标题收进一颗胶囊。
 * [onChromeHeightChanged] 是胶囊加上 [belowChrome] 的高度，不含底下那段淡出。
 */
@Composable
fun SettingsHazeBar(
    title: String,
    onBack: () -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    titleTrailing: (@Composable () -> Unit)? = null,
    belowChrome: @Composable ColumnScope.() -> Unit = {},
    onChromeHeightChanged: (Int) -> Unit = {},
    titleFontWeight: FontWeight = FontWeight.Medium,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .hazeBlur(
                input = HazeInput.Sources(hazeState),
                style = chatToolbarHazeStyle(),
            ),
    ) {
        Column(Modifier.onSizeChanged { onChromeHeightChanged(it.height) }) {
            SettingsTitleCapsule(
                title = title,
                onBack = onBack,
                titleTrailing = titleTrailing,
                titleFontWeight = titleFontWeight,
            )
            belowChrome()
        }
        Spacer(Modifier.height(ChatToolbarHazeBleed))
    }
}

/**
 * 设置页骨架：页面铺在毛玻璃下面，顶栏浮在上面。
 * [topInset] 垫进滚动内容，滚上来的行才会进到模糊里；
 * [bottomInset] 是导航栏，垫在滚动区域外面。
 */
@Composable
fun SettingsHazeScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    titleTrailing: (@Composable () -> Unit)? = null,
    belowChrome: @Composable ColumnScope.() -> Unit = {},
    titleFontWeight: FontWeight = FontWeight.Medium,
    content: @Composable (topInset: Dp, bottomInset: Dp) -> Unit,
) {
    val hazeState = rememberHazeState()
    val background = Theme[colors][backgroundColorToken]
    val chromePx = remember { mutableIntStateOf(0) }
    val topInset = with(LocalDensity.current) { chromePx.intValue.toDp() }
    val bottomInset = with(LocalDensity.current) {
        WindowInsets.systemBars.getBottom(this).toDp()
    }
    Box(modifier.fillMaxSize().background(background)) {
        Box(Modifier.fillMaxSize().hazeSource(hazeState)) {
            content(topInset, bottomInset)
        }
        SettingsHazeBar(
            title = title,
            onBack = onBack,
            hazeState = hazeState,
            titleTrailing = titleTrailing,
            belowChrome = belowChrome,
            titleFontWeight = titleFontWeight,
            onChromeHeightChanged = { chromePx.intValue = it },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .zIndex(1f),
        )
    }
}
