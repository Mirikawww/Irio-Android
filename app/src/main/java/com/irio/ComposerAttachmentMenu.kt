@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.irio

import androidx.compose.foundation.background
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.sp
import com.composables.ui.components.ButtonSize
import com.composables.ui.components.ButtonStyle
import com.composables.ui.components.DropdownMenuAlignment
import com.composables.ui.components.DropdownMenuScope
import com.composables.ui.components.DropdownMenuPanelContentScope
import com.composables.ui.components.DropdownMenuItem
import com.composables.ui.components.DropdownMenuSide
import com.composables.ui.components.Icon
import com.composables.ui.components.IconButton
import com.composables.ui.components.Text
import com.composables.ui.theme.colors
import com.composables.ui.theme.onBackgroundColor
import com.composables.ui.theme.onPanelColor
import com.composables.ui.theme.panelColor
import com.composables.ui.theme.secondaryColor
import com.composeunstyled.LocalContentColor
import com.composeunstyled.theme.Theme
import com.composeunstyled.DropdownMenuPanel as UnstyledDropdownMenuPanel
import com.kyant.shapes.RoundedRectangle

private val AttachmentMenuTriggerSize = 32.dp

@Composable
internal fun ComposerAttachmentMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onPhotos: () -> Unit,
    onFiles: () -> Unit,
) {
    ImePreservingDropdownMenu(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = Modifier.wrapContentWidth(Alignment.Start),
        alignment = DropdownMenuAlignment.Start,
        side = DropdownMenuSide.Top,
        // Align the panel's bottom with the trigger's bottom, covering the plus itself.
        sideOffset = -AttachmentMenuTriggerSize,
        panel = {
            AttachmentMenuPanel {
                DropdownMenuItem(
                    modifier = Modifier.heightIn(min = 56.dp),
                    onClick = { onExpandedChange(false); onPhotos() },
                ) {
                    Row(Modifier.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AttachmentMenuIcon(IrioIcons.Image, "photos")
                        Text(tr("照片"), color = LocalContentColor.current, fontSize = 17.sp, lineHeight = 22.sp)
                    }
                }
                DropdownMenuItem(
                    modifier = Modifier.heightIn(min = 56.dp),
                    onClick = { onExpandedChange(false); onFiles() },
                ) {
                    Row(Modifier.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AttachmentMenuIcon(IrioIcons.Paperclip, "files")
                        Text(tr("文件"), color = LocalContentColor.current, fontSize = 17.sp, lineHeight = 22.sp)
                    }
                }
            }
        },
    ) {
        IconButton(
            onClick = { onExpandedChange(!expanded) },
            style = ButtonStyle.Ghost,
            buttonSize = ButtonSize.Small,
            modifier = Modifier.size(AttachmentMenuTriggerSize).semantics { contentDescription = tr("添加附件") },
        ) {
            Icon(
                imageVector = IrioIcons.Plus,
                contentDescription = tr("添加附件"),
                modifier = Modifier.size(20.dp),
                tint = Theme[colors][onBackgroundColor],
            )
        }
    }
}

/** Use the same dropdown state and IME-aware window, with a reference-specific surface only. */
@Composable
private fun DropdownMenuScope.AttachmentMenuPanel(content: @Composable DropdownMenuPanelContentScope.() -> Unit) {
    val shape = RoundedRectangle(24.dp)
    with(unstyledScope) {
        UnstyledDropdownMenuPanel(
            modifier = Modifier.width(220.dp)
                .dropShadow(shape, Shadow(radius = 20.dp, color = Color.Black.copy(alpha = .14f),
                    offset = DpOffset(0.dp, 4.dp)))
                .clip(shape)
                .background(Theme[colors][panelColor], shape)
                .testTag("attachment-menu-surface")
                .padding(6.dp),
            enter = fadeIn(tween(120)) + scaleIn(tween(160), initialScale = .96f,
                transformOrigin = TransformOrigin(0f, 1f)),
            exit = fadeOut(tween(90)),
        ) {
            val panelScope = DropdownMenuPanelContentScope(this)
            ProvideAppTextScale { panelScope.content() }
        }
    }
}

@Composable
private fun AttachmentMenuIcon(vector: ImageVector, name: String) {
    Box(
        Modifier.size(40.dp).background(Theme[colors][secondaryColor], CircleShape)
            .testTag("attachment-icon:$name"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = vector,
            contentDescription = null,
            modifier = Modifier.size(22.dp).testTag("attachment-glyph:$name"),
            tint = Theme[colors][onPanelColor],
        )
    }
}
