package com.irio

import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect

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

internal val ToolbarCapsuleShape = RoundedCornerShape(50)

/**
 * 圆形图标按钮。强调色只填这个圆，图标用对比色，不跟强调色。
 * 黑色在深色主题里改用前景色当圆，避免黑圆贴在深色底上消失。
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
    val background = Theme[colors][backgroundColorToken]
    val content = Theme[colors][onBackgroundColorToken]
    val showAccent = active && enabled
    val accent = ThemeStore.accent
    val fill: Color
    val iconTint: Color
    if (!showAccent) {
        fill = Theme[colors][mutedColor].copy(alpha = 0.28f)
        iconTint = Theme[colors][mutedColor]
    } else if (accent == AccentColor.BLACK && background.luminance() < 0.5f) {
        fill = content
        iconTint = background
    } else {
        fill = Theme[colors][primaryColorToken]
        iconTint = Theme[colors][onPrimaryColorToken]
    }
    IconButton(
        onClick = onClick,
        enabled = enabled,
        style = ButtonStyle.Ghost,
        buttonSize = ButtonSize.Small,
        shape = CircleShape,
        modifier = modifier
            .size(buttonSize)
            .background(fill, CircleShape)
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

/**
 * 毛玻璃：主题色半透明着色 + 模糊，渐变铺满整段 overlay。
 * [bottomStrong] false = 顶栏上强下弱（EaseIn）；true = 输入框下强上弱（EaseOut，曲线镜像）。
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
                    easing = if (bottomStrong) EaseOut else EaseIn,
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
