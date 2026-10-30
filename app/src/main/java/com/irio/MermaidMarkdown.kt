package com.irio

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.ui.components.Icon
import com.composables.ui.components.IconButton
import com.composables.ui.components.ButtonSize
import com.composables.ui.components.ButtonStyle
import com.composables.ui.components.Text
import com.composables.ui.theme.colors
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.onBackgroundColor
import com.composables.ui.theme.secondaryColor
import com.composeunstyled.theme.Theme
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCode
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownPadding
import com.swithun.cmpmermaid.compose.AndroidMermaidAssetProvider
import com.swithun.cmpmermaid.compose.MermaidSceneCanvas
import com.swithun.cmpmermaid.compose.rememberMermaidScene
import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidTheme
import com.swithun.cmpmermaid.core.MermaidThemePreset
import kotlinx.coroutines.launch
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxThemes

/** A closed Mermaid fence is rendered offline; its original source is always recoverable. */
@Composable
internal fun MermaidMarkdownBlock(source: String) {
    val dark = LocalResolvedTheme.current.isDark
    val theme = remember(dark) {
        MermaidTheme.preset(if (dark) MermaidThemePreset.Dark else MermaidThemePreset.Default)
    }
    var showSource by remember(source) { mutableStateOf(false) }
    var failure by remember(source, dark) { mutableStateOf<String?>(null) }
    var ratio by remember(source, dark) { mutableStateOf<Float?>(null) }
    val assets = remember { AndroidMermaidAssetProvider() }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copied = rememberCopyConfirmation(source.hashCode().toLong())
    val foreground = Theme[colors][onBackgroundColor]
    val iconColor = foreground.copy(alpha = 0.65f)
    val bodyShape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
    val diagramVisible = !showSource && failure == null
    Column(
        Modifier.testTag("mermaid-block").fillMaxWidth().padding(vertical = 8.dp),
    ) {
        Row(
            Modifier.testTag("mermaid-header").fillMaxWidth()
                .background(Theme[colors][secondaryColor], RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Mermaid", modifier = Modifier.weight(1f), color = iconColor, fontSize = 10.sp, lineHeight = 14.sp)
            IconButton(onClick = { showSource = !showSource }, style = ButtonStyle.Ghost,
                modifier = Modifier.size(24.dp), buttonSize = ButtonSize.Small, enabled = failure == null) {
                Icon(imageVector = if (showSource || failure != null) IrioIcons.Image else IrioIcons.Braces,
                    contentDescription = tr(if (showSource || failure != null) "图表" else "源代码"),
                    tint = if (failure == null) iconColor else iconColor.copy(alpha = 0.3f), modifier = Modifier.size(14.dp))
            }
            IconButton(onClick = { scope.launch {
                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Mermaid", source)))
                copied.copy {}
            } },
                modifier = Modifier.size(24.dp), style = ButtonStyle.Ghost, buttonSize = ButtonSize.Small) {
                Icon(imageVector = if (copied.confirmed) IrioIcons.Check else IrioIcons.Copy,
                    contentDescription = tr(if (copied.confirmed) "已复制" else "复制"),
                    tint = iconColor, modifier = Modifier.size(14.dp))
            }
        }
        failure?.let {
            Text(tr("图表无法渲染，已显示源代码"), color = Theme[colors][mutedColor],
                fontSize = 13.sp, lineHeight = 18.sp)
        }
        // Retain the native renderer and its prepared scene/viewport while source is shown.
        // An unplaced child occupies no height, draws nothing and cannot receive input.
        BoxWithConstraints(
            Modifier.fillMaxWidth()
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, if (diagramVisible) placeable.height else 0) {
                        if (diagramVisible) placeable.placeRelative(0, 0)
                    }
                }
                .then(if (diagramVisible) Modifier else Modifier.clearAndSetSemantics {})
                .clip(bodyShape),
        ) {
            val height = ratio?.let { (maxWidth / it).coerceIn(112.dp, 480.dp) } ?: 180.dp
            // Own the viewport and render state here: the upstream 0.1.21 Diagram wrapper
            // can lose its Canvas after subcomposition, although scene preparation succeeds.
            val scene = rememberMermaidScene(source = source, theme = theme).value
            LaunchedEffect(scene) {
                when (scene) {
                    is GMResult.Ok -> if (scene.value.width > 0f && scene.value.height > 0f) {
                        ratio = scene.value.width / scene.value.height
                    }
                    is GMResult.Err -> failure = scene.error.message
                    null -> Unit
                }
            }
            Box {
                if (scene is GMResult.Ok) MermaidSceneCanvas(
                    scene = scene.value,
                    modifier = Modifier.testTag("mermaid-diagram").fillMaxWidth().height(height),
                    contentDescription = tr("Mermaid 图表"),
                    assetProvider = assets,
                    respectSourceViewportSizing = false,
                )
                if (ratio == null) {
                    Text(tr("图表渲染中"), modifier = Modifier.align(Alignment.Center),
                        color = Theme[colors][mutedColor], fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
        }
        if (!diagramVisible) {
            MermaidSourceCode(source)
        }
    }
}

/** The same native code-block implementation and preferences as ordinary Markdown fences. */
@Composable
private fun MermaidSourceCode(source: String) {
    val foreground = Theme[colors][onBackgroundColor]
    val dark = LocalResolvedTheme.current.isDark
    val colors = markdownColor(text = foreground, codeBackground = foreground.copy(alpha = 0.08f), darkTheme = dark)
    val highlights = remember(dark) { Highlights.Builder().theme(SyntaxThemes.atom(darkMode = dark)) }
    CompositionLocalProvider(
        LocalMarkdownColors provides colors,
        LocalMarkdownDimens provides markdownDimens(),
        LocalMarkdownPadding provides markdownPadding(),
    ) {
        MarkdownHighlightedCode(
            code = source,
            language = "mermaid",
            style = irioCodeBlockStyle().copy(color = foreground),
            highlightsBuilder = highlights,
            showHeader = false,
            softWrap = ThemeStore.codeBlockWrap,
            lineNumbers = ThemeStore.codeBlockLineNumbers,
            backgroundShape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp),
            outerPadding = PaddingValues(0.dp),
        )
    }
}
