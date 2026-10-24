package com.irio

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.ui.components.Text
import com.composables.ui.theme.colors
import com.composables.ui.theme.onPanelColor
import com.composables.ui.theme.panelColor
import com.composeunstyled.theme.Theme
import com.kyant.shapes.RoundedRectangle
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import java.io.File

private data class ImageGenerationLayer(val bitmap: android.graphics.Bitmap? = null, val finalPath: String? = null)

internal fun imageGenerationPlaceholderColor(dark: Boolean): Color =
    if (dark) Color(0xFF303030) else Color(0xFFBDBDBD)

/** One continuously measured surface; its preview, sweep and caption share the animated bounds. */
@Composable
internal fun ImageGenerationCard(
    progress: ImageGenerationProgress,
    onOpen: (ChatAttachment) -> Unit = {},
) {
    if (!progress.active && progress.finalAttachment == null) return
    val attachment = progress.finalAttachment
    var lastPreview by remember(progress.id) { mutableStateOf(progress.preview) }
    LaunchedEffect(progress.preview) { progress.preview?.let { lastPreview = it } }
    val context = LocalContext.current
    val finalImage = remember(attachment?.filePath, context) {
        attachment?.let { ImageRequest.Builder(context).data(File(it.filePath)).size(1024).build() }
    }
    val finalPainter = rememberAsyncImagePainter(finalImage)
    val finalState by finalPainter.state.collectAsState()
    val finalReady = attachment != null && finalState is AsyncImagePainter.State.Success
    // History only persists the final attachment, not the live preview dimensions.
    // Coil decodes off the UI thread; use the loaded image's real ratio after reopening.
    val finalImageSize = (finalState as? AsyncImagePainter.State.Success)?.result?.image
    val targetRatio = if (finalImageSize != null && finalImageSize.width > 0 && finalImageSize.height > 0) {
        finalImageSize.width.toFloat() / finalImageSize.height
    } else progress.aspectRatio
    val ratio by animateFloatAsState(targetRatio, irioSpring(stiffness = 360f), label = "imageGenerationRatio")
    LaunchedEffect(finalReady) { if (finalReady) lastPreview = null }
    val layer = if (finalReady) ImageGenerationLayer(finalPath = attachment.filePath) else ImageGenerationLayer(lastPreview)
    BoxWithConstraints(Modifier.widthIn(max = 275.dp)) {
        val boundedRatio = ratio.coerceAtLeast(0.01f)
        Box(
            Modifier.testTag("image-generation-card").width(maxWidth * boundedRatio.coerceAtMost(1f)).aspectRatio(boundedRatio)
                .clip(RoundedRectangle(16.dp))
                .background(imageGenerationPlaceholderColor(LocalResolvedTheme.current.isDark))
                .clickable(enabled = attachment != null) { attachment?.let(onOpen) }
                .semantics {
                    contentDescription = tr(if (attachment == null) "图片生成预览" else "已生成图片")
                    stateDescription = "${progress.width}x${progress.height}"
                },
        ) {
            // Crossfade draws outgoing and incoming images simultaneously, never fade-through gray.
            Crossfade(
                targetState = layer,
                animationSpec = tween(480), label = "imageGenerationPreview",
            ) { visual ->
                if (visual.finalPath != null) {
                    Image(finalPainter, contentDescription = null,
                        modifier = Modifier.testTag("image-generation-final-image").fillMaxSize(), contentScale = ContentScale.Fit)
                } else if (visual.bitmap != null) {
                    Image(
                        visual.bitmap.asImageBitmap(), contentDescription = null,
                        modifier = Modifier.testTag("image-generation-blurred-preview").fillMaxSize().blur(18.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
            if (progress.active) ImageGenerationSweep(Modifier.fillMaxSize())
            AnimatedVisibility(
                visible = attachment == null,
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                enter = fadeIn(tween(200)), exit = fadeOut(tween(250)),
            ) {
                Box(
                    Modifier.clip(ContinuousCapsule).background(Theme[colors][panelColor].copy(alpha = 0.94f))
                        .animateContentSize(irioSpring(stiffness = 360f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    AnimatedContent(
                        targetState = progress.caption(),
                        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(220)) },
                        label = "imageGenerationCaption",
                    ) { caption ->
                        Text(caption, color = Theme[colors][onPanelColor], fontSize = 13.sp, lineHeight = 18.sp,
                            maxLines = if (boundedRatio >= 2f) 1 else 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageGenerationSweep(modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "imageGenerationScan")
    val phase = transition.animateFloat(
        -0.5f, 1.5f,
        infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
        label = "imageGenerationScanPhase",
    )
    Box(modifier.drawWithCache {
        onDrawBehind {
            val extent = size.width + size.height
            val center = phase.value * extent
            val band = extent * 0.22f
            drawRect(Brush.linearGradient(
                listOf(Color.Transparent, Color.White.copy(alpha = 0.22f), Color.Transparent),
                start = Offset(center - band, 0f), end = Offset(center + band, band * 1.2f),
            ))
        }
    })
}
