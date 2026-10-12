package com.irio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddReaction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.ui.components.Icon
import com.composables.ui.theme.backgroundColor
import com.composables.ui.theme.colors
import com.composables.ui.theme.mutedColor
import com.composeunstyled.theme.Theme

/** Tapback hangs over the upper-left corner, with a thought-bubble trail and room to scale. */
@Composable
internal fun UserBubbleReaction(
    emoji: String,
    bubbleColor: Color,
    bounce: State<Float>,
    content: @Composable () -> Unit,
) {
    val emojiSize = with(LocalDensity.current) { 16.dp.toSp() }
    val emojiLineHeight = with(LocalDensity.current) { 20.dp.toSp() }
    // Only the original content determines size; the reaction is an overlaid decoration.
    Box {
        content()
        // Emoji inside a fixed badge behaves like an icon, not font-scaled body text.
        Box(Modifier.matchParentSize()) {
            if (emoji.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(x = (-12).dp, y = (-26).dp)
                        .wrapContentSize(Alignment.TopStart, unbounded = true)
                        .size(width = 38.dp, height = 40.dp)
                        .graphicsLayer {
                            scaleX = bounce.value
                            scaleY = bounce.value
                            clip = false
                        }
                        .semantics { contentDescription = tr("模型回应") + " " + emoji },
                ) {
                    Box(
                        modifier = Modifier.offset(x = 6.dp).size(30.dp)
                            .background(bubbleColor, ContinuousCapsule)
                            .border(2.dp, Theme[colors][backgroundColor], ContinuousCapsule),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            emoji,
                            style = TextStyle(
                                fontSize = emojiSize,
                                lineHeight = emojiLineHeight,
                                textAlign = TextAlign.Center,
                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
                            ),
                            maxLines = 1,
                        )
                    }
                    Box(Modifier.offset(y = 26.dp).size(9.dp).background(bubbleColor, ContinuousCapsule))
                    Box(Modifier.offset(x = (-4).dp, y = 37.dp).size(4.dp).background(bubbleColor, ContinuousCapsule))
                }
            }
        }
    }
}

/** Stable footer slot for the add icon or user's emoji; tapping the emoji removes it. */
@Composable
internal fun AssistantReactionButton(
    emoji: String,
    bounce: State<Float>,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
) {
    // Retain the original icon's 18dp footprint; the emoji draws beyond it without resizing the row.
    Box(
        modifier = Modifier.size(18.dp)
            .graphicsLayer {
                scaleX = bounce.value
                scaleY = bounce.value
                clip = false
            }
            .semantics {
                contentDescription = if (emoji.isEmpty()) "Add Reaction" else tr("撤销回应") + " " + emoji
            }
            .clickable(onClick = if (emoji.isEmpty()) onAdd else onRemove),
        contentAlignment = Alignment.Center,
    ) {
        if (emoji.isEmpty()) {
            Icon(
                imageVector = Icons.Rounded.AddReaction,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = Theme[colors][mutedColor],
            )
        } else {
            BasicText(
                emoji,
                modifier = Modifier.wrapContentSize(unbounded = true).padding(2.dp),
                style = TextStyle(
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    textAlign = TextAlign.Center,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
                ),
                maxLines = 1,
            )
        }
    }
}
