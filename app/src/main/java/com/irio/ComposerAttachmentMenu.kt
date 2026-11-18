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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
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
import com.composeunstyled.DropdownMenuPanelScope
import com.composeunstyled.MenuItem
import com.composables.ui.components.DropdownMenuSide
import com.composables.ui.components.Icon
import com.composables.ui.components.IconButton
import com.composables.ui.components.Text
import com.composables.ui.theme.colors
import com.composables.ui.theme.defaultIndication
import com.composables.ui.theme.indications
import com.composables.ui.theme.onBackgroundColor
import com.composables.ui.theme.onPanelColor
import com.composables.ui.theme.panelColor
import com.composables.ui.theme.secondaryColor
import com.composeunstyled.theme.Theme
import com.composeunstyled.DropdownMenuPanel as UnstyledDropdownMenuPanel
import com.kyant.shapes.RoundedRectangle

private val AttachmentMenuTriggerSize = 32.dp
private val AttachmentMenuRadius = 24.dp

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
                AttachmentMenuItem(IrioIcons.Image, "photos", tr("照片")) {
                    onExpandedChange(false)
                    onPhotos()
                }
                AttachmentMenuItem(IrioIcons.Paperclip, "files", tr("文件")) {
                    onExpandedChange(false)
                    onFiles()
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
private fun DropdownMenuScope.AttachmentMenuPanel(content: @Composable DropdownMenuPanelScope.() -> Unit) {
    val shape = remember { RoundedRectangle(AttachmentMenuRadius) }
    with(unstyledScope) {
        UnstyledDropdownMenuPanel(
            modifier = Modifier.width(220.dp)
                .dropShadow(shape, Shadow(radius = 20.dp, color = Color.Black.copy(alpha = .14f),
                    offset = DpOffset(0.dp, 4.dp)))
                .clip(shape)
                .background(Theme[colors][panelColor], shape)
                .testTag("attachment-menu-surface"),
            enter = fadeIn(tween(120)) + scaleIn(tween(160), initialScale = .96f,
                transformOrigin = TransformOrigin(0f, 1f)),
            exit = fadeOut(tween(90)),
        ) {
            ProvideAppTextScale { content() }
        }
    }
}

@Composable
private fun DropdownMenuPanelScope.AttachmentMenuItem(
    vector: ImageVector,
    name: String,
    label: String,
    onClick: () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val focused by interactions.collectIsFocusedAsState()
    val pressed by interactions.collectIsPressedAsState()
    MenuItem(
        onClick = onClick,
        // The panel clips the outer corners; each row clips feedback at the shared edge.
        modifier = Modifier.fillMaxWidth().clipToBounds()
            .background(if (hovered || focused || pressed) Theme[colors][secondaryColor] else Color.Transparent),
        interactionSource = interactions,
        indication = Theme[indications][defaultIndication],
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 6.dp).heightIn(min = 50.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AttachmentMenuIcon(vector, name)
            Text(label, color = Theme[colors][onPanelColor], fontSize = 17.sp, lineHeight = 22.sp)
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
