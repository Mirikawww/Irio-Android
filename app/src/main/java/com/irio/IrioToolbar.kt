package com.irio

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
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.ui.theme.backgroundColor as backgroundColorToken
import com.composables.ui.theme.colors
import com.composables.ui.theme.onBackgroundColor as onBackgroundColorToken
import com.composables.ui.theme.panelColor
import com.composeunstyled.LocalTextStyle
import com.composeunstyled.ProvideContentColor
import com.composeunstyled.ProvideTextStyle
import com.composeunstyled.theme.Theme

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

private val IrioToolbarTitleStyle = TextStyle(
    fontSize = 20.sp,
    lineHeight = 24.sp,
    fontWeight = FontWeight.Medium,
)
