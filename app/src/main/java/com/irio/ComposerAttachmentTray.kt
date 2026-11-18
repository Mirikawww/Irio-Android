package com.irio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.composables.ui.components.Icon
import com.composables.ui.theme.borderColor
import com.composables.ui.theme.colors
import com.composables.ui.theme.fieldColor
import com.composables.ui.theme.onPanelColor
import com.composeunstyled.theme.Theme
import com.kyant.shapes.RoundedRectangle

/** The field stays in the same slot while the strip opens upward above it. */
@Composable
internal fun ComposerAttachmentTray(
    attachments: List<ChatAttachment>,
    loadingFiles: Set<String>,
    expansion: State<Float>,
    expandedLayout: Boolean,
    onRemove: (ChatAttachment) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val visible = attachments.isNotEmpty()
    // AnimatedVisibility keeps its slot alive, but the draft clears immediately.
    // Retain the previous chips for drawing until the closing clip finishes.
    var lastAttachments by remember { mutableStateOf(emptyList<ChatAttachment>()) }
    if (visible) SideEffect { lastAttachments = attachments }

    val field = Theme[colors][fieldColor]
    val ink = Theme[colors][onPanelColor]
    val trayFill = lerp(field, ink, 0.035f)
    val border = Theme[colors][borderColor]
    val radius = ThemeStore.inputCornerRadius
    val trayShape = remember(radius) { RoundedRectangle((radius + 4).dp) }
    val backdrop = animateFloatAsState(
        targetValue = if (visible && !expandedLayout) 1f else 0f,
        animationSpec = tween(if (visible) 220 else 180),
        label = "composer attachment backdrop",
    )

    Box(
        modifier.fillMaxWidth().composerAnimatedPadding(
            expansion, start = 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp,
        )
    ) {
        Box(
            Modifier.matchParentSize()
                .graphicsLayer { alpha = backdrop.value }
                .background(trayFill, trayShape)
                .border(1.dp, border, trayShape),
        )
        Column(Modifier.composerAnimatedPadding(expansion, start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
            AnimatedVisibility(
                visible = visible,
                enter = expandVertically(tween(220), expandFrom = Alignment.Bottom),
                exit = shrinkVertically(tween(180), shrinkTowards = Alignment.Bottom),
                modifier = Modifier.fillMaxWidth(),
                label = "composer attachments",
            ) {
                Row(
                    Modifier.fillMaxWidth()
                        .then(if (expandedLayout) Modifier.background(trayFill, trayShape) else Modifier)
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 4.dp, end = 4.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    (if (visible) attachments else lastAttachments).forEach { attachment ->
                        PendingAttachmentChip(
                            attachment = attachment,
                            loading = attachment.filePath in loadingFiles,
                            enabled = visible,
                            onRemove = { onRemove(attachment) },
                        )
                    }
                }
            }
            content()
        }
    }
}

@Composable
private fun PendingAttachmentChip(
    attachment: ChatAttachment,
    loading: Boolean,
    enabled: Boolean,
    onRemove: () -> Unit,
) {
    val shape = remember { RoundedRectangle(11.dp) }
    val ink = Theme[colors][onPanelColor]
    AttachmentChip(
        attachment = attachment,
        loading = loading,
        modifier = Modifier.border(1.dp, Theme[colors][borderColor], shape),
    ) {
        val removeInteractions = remember { MutableInteractionSource() }
        Box(
            Modifier.size(width = 44.dp, height = 36.dp)
                .clickable(
                    interactionSource = removeInteractions,
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onRemove,
                )
                .semantics { contentDescription = tr("移除附件") },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(26.dp)
                    .clip(CircleShape)
                    .indication(removeInteractions, LocalIndication.current),
            )
            Icon(IrioIcons.X, contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
        }
    }
}
