package com.irio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

enum class TextScaleMode(val label: String, val stored: String) {
    SYSTEM("跟随系统", "system"),
    CUSTOM("自定义", "custom");

    companion object {
        fun fromStored(value: String?): TextScaleMode =
            entries.firstOrNull { it.stored.equals(value, ignoreCase = true) } ?: SYSTEM
    }
}

internal const val DefaultTextScalePercent = 100
internal val TextScalePercentRange = 50..200

/** Custom scale is absolute, not multiplied by Android's font scale. Keep dp unchanged. */
@Composable
internal fun ProvideAppTextScale(content: @Composable () -> Unit) {
    val systemDensity = LocalDensity.current
    val mode = ThemeStore.textScaleMode
    val percent = ThemeStore.textScalePercent
    val density = if (mode == TextScaleMode.SYSTEM) {
        // Retain the platform Density itself, including Android's nonlinear sp conversion.
        systemDensity
    } else {
        remember(systemDensity.density, percent) {
            Density(systemDensity.density, percent / 100f)
        }
    }
    CompositionLocalProvider(LocalDensity provides density, content = content)
}
