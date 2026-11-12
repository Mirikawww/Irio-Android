package com.irio

import androidx.compose.foundation.background

import androidx.compose.foundation.layout.Box

import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.ui.components.Text
import com.composables.ui.theme.backgroundColor

import com.composables.ui.theme.colors

import com.composables.ui.theme.largeShape
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.shapes
import com.composeunstyled.theme.Theme
import com.kyant.shapes.RoundedRectangle

/** Render the real chat input with an isolated pending shape and no editing. */
@Composable
internal fun InputCornerPreview(radius: Int, showValue: Boolean) {
    val shape = remember(radius) { RoundedRectangle(radius.dp) }
    val background = Theme[colors][backgroundColor]
    val sample = rememberTextFieldState(tr("这是一条预览消息"))
    Box(Modifier.background(background, Theme[shapes][largeShape]).padding(12.dp)
        .testTag("input-corner-preview")) {
        ComposerField(
            state = sample,
            placeholder = "",
            enabled = false,
            shape = shape,
            animateLineWrap = false,
            modifier = Modifier.widthIn(min = 180.dp)
                .testTag("input-corner-preview-field").semantics { stateDescription = "$radius dp" },
        )
        if (showValue) {
            Box(Modifier.matchParentSize().offset(y = (-10).dp), contentAlignment = Alignment.TopEnd) {
                Text("$radius dp", color = Theme[colors][mutedColor], fontSize = 12.sp, lineHeight = 16.sp,
                    modifier = Modifier.background(background, ContinuousCapsule).padding(horizontal = 4.dp))
            }
        }
    }
}
