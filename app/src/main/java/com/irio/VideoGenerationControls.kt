package com.irio

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.ui.components.Icon
import com.composables.ui.components.Text
import com.composables.ui.theme.colors
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.onBackgroundColor
import com.composables.ui.theme.primaryColor
import com.composeunstyled.theme.Theme
import com.kyant.shapes.RoundedRectangle

internal enum class VideoGenerationChoice { RATIO }

// MiniMax Video Generation V2: https://platform.minimax.io/docs/api-reference/video-generation-v2-create
private fun videoGenerationModelId(model: String): String = VideoGenerationOptions.models
    .firstOrNull { it.equals(model.substringAfterLast('/'), ignoreCase = true) } ?: model

internal fun videoGenerationResolutions(model: String): List<String> =
    VideoGenerationOptions.resolutionsFor(videoGenerationModelId(model))

internal fun videoGenerationDurations(model: String): IntRange =
    VideoGenerationOptions.durationsFor(videoGenerationModelId(model))

// A prompt-only request must use a concrete ratio, not adaptive.
internal val videoGenerationRatios = VideoGenerationOptions.ratios

internal fun videoGenerationChoiceValues(choice: VideoGenerationChoice, model: String): List<String> = when (choice) {
    VideoGenerationChoice.RATIO -> videoGenerationRatios
}

@Composable
internal fun VideoGenerationControls(
    options: VideoGenerationOptions,
    active: Boolean,
    model: String,
    onChange: (VideoGenerationOptions) -> Unit,
    onChoice: (VideoGenerationChoice) -> Unit,
) {
    val resolutions = videoGenerationResolutions(model)
    Row(
        Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(tr("分辨率"), Modifier.weight(1f), color = Theme[colors][onBackgroundColor], fontSize = 17.sp, lineHeight = 20.sp)
        ImageChoiceTabs(tr("分辨率"), resolutions, resolutions.indexOf(options.resolution).coerceAtLeast(0), active) {
            onChange(options.copy(resolution = resolutions[it]))
        }
    }
    VideoDurationSlider(options.duration, videoGenerationDurations(model), active) {
        onChange(options.copy(duration = it))
    }
    VideoGenerationOptionRow(tr("宽高比"), options.ratio, active) {
        onChoice(VideoGenerationChoice.RATIO)
    }
}

@Composable
private fun VideoGenerationOptionRow(title: String, value: String, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(44.dp).clip(RoundedRectangle(10.dp))
            .clickable(remember { MutableInteractionSource() }, LocalIndication.current, enabled = active,
                role = Role.Button, onClick = onClick)
            .semantics { contentDescription = title; stateDescription = value }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, Modifier.weight(1f), color = Theme[colors][onBackgroundColor], fontSize = 17.sp, lineHeight = 20.sp)
        Text(value, color = Theme[colors][onBackgroundColor], fontSize = 15.sp, lineHeight = 20.sp)
        Icon(IrioIcons.ChevronDown, contentDescription = null, tint = Theme[colors][mutedColor], modifier = Modifier.size(18.dp))
    }
}

/** Peer of ImageAspectRatioList: reuse the popup's list page, checkmark and dismissal path. */
@Composable
internal fun VideoGenerationChoiceList(
    choice: VideoGenerationChoice,
    model: String,
    options: VideoGenerationOptions,
    modifier: Modifier,
    opacity: State<Float>,
    active: Boolean,
    onSelect: (VideoGenerationOptions) -> Unit,
) {
    val values = videoGenerationChoiceValues(choice, model)
    val selectedValue = when (choice) {
        VideoGenerationChoice.RATIO -> options.ratio
    }
    AndroidComposeRecyclerList(
        items = values, key = { it }, modifier = modifier,
        contentPadding = PaddingValues(vertical = 4.dp), cornerRadius = 12.dp,
        opacity = opacity, interactionEnabled = active,
    ) { value ->
        val selected = value == selectedValue
        Row(
            Modifier.fillMaxWidth().height(36.dp).clip(RoundedRectangle(8.dp))
                .selectable(selected, enabled = active, role = Role.RadioButton, onClick = {
                    onSelect(when (choice) {
                        VideoGenerationChoice.RATIO -> options.copy(ratio = value)
                    })
                }).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(value,
                Modifier.weight(1f), color = Theme[colors][onBackgroundColor], fontSize = 16.sp, lineHeight = 20.sp)
            if (selected) Icon(IrioIcons.Check, contentDescription = null, tint = Theme[colors][primaryColor], modifier = Modifier.size(18.dp))
        }
    }
}
