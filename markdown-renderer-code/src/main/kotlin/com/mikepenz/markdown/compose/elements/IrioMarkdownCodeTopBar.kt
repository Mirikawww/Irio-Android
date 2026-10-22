/*
 * Adapted from mikepenz/multiplatform-markdown-renderer v0.46.0 MarkdownCodeTopBar.
 * Copyright 2022 Mike Penz. Licensed under the Apache License, Version 2.0.
 * Icon: Lucide copy, see app/src/main/assets/licenses/lucide.txt.
 */
package com.mikepenz.markdown.compose.elements

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.code.R
import com.mikepenz.markdown.compose.LocalMarkdownA11yLabels
import com.mikepenz.markdown.compose.LocalMarkdownColors

@Composable
internal fun IrioMarkdownCodeTopBar(language: String?, code: String) {
    @Suppress("DEPRECATION") val clipboardManager = LocalClipboardManager.current
    val textColor = LocalMarkdownColors.current.text
    val labels = LocalMarkdownA11yLabels.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val spokenLanguage = language?.takeIf { it.isNotBlank() } ?: labels.codeFallbackLanguage
        BasicText(
            text = language?.uppercase() ?: labels.codeFallbackLanguage,
            modifier = Modifier.semantics { contentDescription = labels.codeLanguage(spokenLanguage) },
            style = TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = textColor.copy(alpha = 0.6f)),
        )
        Box(
            modifier = Modifier.size(24.dp).clip(CircleShape)
                .semantics { contentDescription = labels.copyCode; role = Role.Button }
                .clickable(onClickLabel = labels.copyCode) { clipboardManager.setText(AnnotatedString(code)) },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_lucide_copy),
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                colorFilter = ColorFilter.tint(textColor.copy(alpha = 0.6f)),
            )
        }
    }
}
