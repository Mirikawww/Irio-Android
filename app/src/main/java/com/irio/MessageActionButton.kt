package com.irio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.ui.components.Icon

/** Keep compact footer layout slots while giving each action a larger round feedback surface. */
@Composable
internal fun MessageActionButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    slotSize: Dp = 18.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.size(slotSize), contentAlignment = Alignment.Center) {
        content()
        // Clip only the indication. Reaction glyphs may paint beyond their compact slot.
        Box(
            Modifier.matchParentSize()
                .wrapContentSize(Alignment.Center, unbounded = true)
                .requiredSize(32.dp)
                .clip(CircleShape)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics { this.contentDescription = contentDescription },
        )
    }
}

@Composable
internal fun MessageActionIconButton(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color,
    enabled: Boolean = true,
    iconSize: Dp = 18.dp,
) {
    MessageActionButton(contentDescription, onClick, enabled = enabled, slotSize = iconSize) {
        Icon(imageVector, contentDescription = null, modifier = Modifier.size(iconSize), tint = tint)
    }
}
