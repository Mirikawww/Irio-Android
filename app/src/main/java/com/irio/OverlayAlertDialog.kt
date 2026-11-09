/*
 * Adapted from Composables UI AlertDialog.kt for a screen-sized sibling overlay.
 * Copyright (c) 2026 Composable Horizons
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.irio

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.composables.ui.theme.colors
import com.composables.ui.theme.dialogShape
import com.composables.ui.theme.overlayShadow
import com.composables.ui.theme.scrimColor
import com.composables.ui.theme.shadows
import com.composables.ui.theme.shapes
import com.composeunstyled.DialogPanel
import com.composeunstyled.ProvideContentColor
import com.composeunstyled.Scrim
import com.composeunstyled.UnstyledDialog
import com.composeunstyled.theme.Theme

/** Keep the library panel and motion; notices share its window but not its clip or width. */
@Composable
internal fun OverlayAlertDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier,
    backgroundColor: Color,
    contentColor: Color,
    overlay: @Composable BoxScope.() -> Unit,
    content: @Composable () -> Unit,
) {
    val decelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val accelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 1f)
    val shape = Theme[shapes][dialogShape]
    UnstyledDialog(visible = visible, onDismissRequest = onDismissRequest, overlay = {
        Scrim(scrimColor = Theme[colors][scrimColor],
            enter = fadeIn(tween(150, easing = decelerate)),
            exit = fadeOut(tween(100, easing = accelerate)))
    }) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                DialogPanel(
                    modifier = modifier.widthIn(min = 280.dp, max = 560.dp).fillMaxWidth().heightIn(max = 560.dp)
                        .dropShadow(shape, Theme[shadows][overlayShadow]).clip(shape)
                        .background(backgroundColor, shape).padding(24.dp),
                    paneTitle = "Alert dialog",
                    enter = scaleIn(initialScale = 0.92f, animationSpec = tween(400, easing = decelerate)) +
                        fadeIn(tween(150, easing = decelerate)),
                    exit = scaleOut(targetScale = 0.92f, animationSpec = tween(200, easing = accelerate)) +
                        fadeOut(tween(100, easing = accelerate)),
                ) { ProvideContentColor(contentColor) { content() } }
            }
            // A layout-only sibling cannot steal touches outside the notice itself.
            Box(Modifier.fillMaxSize().imePadding().navigationBarsPadding().testTag("dialog-screen-overlay"), content = overlay)
        }
    }
}
