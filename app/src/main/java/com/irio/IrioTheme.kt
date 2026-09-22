package com.irio

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.composables.ui.theme.ColorScheme
import com.composables.ui.theme.ComposablesTheme
import com.composables.ui.theme.LocalColorScheme
import com.composables.ui.theme.alphas
import com.composables.ui.theme.backgroundColor
import com.composables.ui.theme.borderColor
import com.composables.ui.theme.buttonShape
import com.composables.ui.theme.colors
import com.composables.ui.theme.controlColor
import com.composables.ui.theme.defaultIndication as defaultIndicationToken
import com.composables.ui.theme.defaultTextSelectionColors as defaultTextSelectionColorsToken
import com.composables.ui.theme.destructiveColor
import com.composables.ui.theme.dialogShape
import com.composables.ui.theme.disabledAlpha
import com.composables.ui.theme.fieldColor
import com.composables.ui.theme.fieldShape
import com.composables.ui.theme.indications
import com.composables.ui.theme.inverseIndication
import com.composables.ui.theme.largeShape
import com.composables.ui.theme.mediumShape
import com.composables.ui.theme.menuShape
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.onBackgroundColor
import com.composables.ui.theme.onControlColor
import com.composables.ui.theme.onDestructiveColor
import com.composables.ui.theme.onFieldColor
import com.composables.ui.theme.onPanelColor
import com.composables.ui.theme.onPrimaryColor
import com.composables.ui.theme.onSecondaryColor
import com.composables.ui.theme.onSelectedControlColor
import com.composables.ui.theme.overlayShadow
import com.composables.ui.theme.panelColor
import com.composables.ui.theme.primaryColor
import com.composables.ui.theme.raisedShadow
import com.composables.ui.theme.ringColor
import com.composables.ui.theme.scrimColor
import com.composables.ui.theme.secondaryColor
import com.composables.ui.theme.selectedControlColor
import com.composables.ui.theme.shadows
import com.composables.ui.theme.shapes
import com.composables.ui.theme.sheetShape
import com.composables.ui.theme.smallShape
import com.composables.ui.theme.switchSelectedTrackColor
import com.composables.ui.theme.switchThumbColor
import com.composables.ui.theme.switchTrackColor
import com.composables.ui.theme.textSelectionColors
import com.composables.ui.theme.thumbColor
import com.composeunstyled.LocalContentColor
import com.composeunstyled.LocalTextStyle
import com.composeunstyled.theme.Theme
import com.composeunstyled.theme.ThemeComposable
import com.composeunstyled.theme.buildTheme

fun ComponentActivity.setIrioContent(content: @Composable () -> Unit) {
    ThemeStore.ensureLoaded(this)
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.auto(
            android.graphics.Color.TRANSPARENT,
            android.graphics.Color.TRANSPARENT
        ),
        navigationBarStyle = SystemBarStyle.auto(
            android.graphics.Color.TRANSPARENT,
            android.graphics.Color.TRANSPARENT
        )
    )
    if (Build.VERSION.SDK_INT >= 29) {
        window.isNavigationBarContrastEnforced = false
    }
    setContent {
        AppTheme(content)
    }
}

/**
 * 贴底内容要避让的系统 UI insets：只取 `systemBars`。
 *
 * 不要并入 `systemGestures`：它不管当前是不是手势导航都报告底部强制手势区和
 * 左右返回手势热区，会让无导航条的界面无故垫出底部空白，并给全宽 Scaffold
 * 加上莫名其妙的横向 padding。真实的三键/手势导航条已经体现在 `navigationBars`
 * （`systemBars` 的子集）里。不含 IME：键盘避让由调用方按需 `imePadding()`。
 */
@Composable
fun irioSystemUiInsets(): WindowInsets = WindowInsets.systemBars

/** 只取底部那一条：顶部交给 [IrioToolbar] 或 `statusBarsPadding()`。 */
@Composable
fun irioBottomSystemUiInsets(): WindowInsets =
    irioSystemUiInsets().only(WindowInsetsSides.Bottom)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    val preference = ThemeStore.preference
    val resolvedTheme = preference.resolve(
        systemDark = isSystemInDarkTheme(),
        autoAmoled = ThemeStore.autoAmoled,
    )
    val darkTheme = resolvedTheme.isDark
    val colorScheme = if (darkTheme) ColorScheme.Dark else ColorScheme.Light

    SideEffect {
        val activity = view.context as? ComponentActivity ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.isAppearanceLightStatusBars = !darkTheme
        controller.isAppearanceLightNavigationBars = !darkTheme
        if (Build.VERSION.SDK_INT >= 29) {
            activity.window.isNavigationBarContrastEnforced = false
        }
    }

    CompositionLocalProvider(
        LocalColorScheme provides colorScheme,
        LocalAmoledTheme provides (resolvedTheme == ResolvedTheme.AMOLED),
    ) {
        ComposablesTheme {
            SurfaceOverride(content)
        }
    }
}

private val LocalAmoledTheme = staticCompositionLocalOf { false }

private val SurfaceOverride: ThemeComposable = buildTheme {
    val amoled = LocalAmoledTheme.current
    val darkTheme = LocalColorScheme.current == ColorScheme.Dark
    properties[colors] = mapOf(
        backgroundColor to if (amoled) Color.Black else Theme[colors][backgroundColor],
        onBackgroundColor to Theme[colors][onBackgroundColor],
        panelColor to if (amoled) Color(0xFF121212) else Theme[colors][panelColor],
        onPanelColor to Theme[colors][onPanelColor],
        mutedColor to Theme[colors][mutedColor],
        primaryColor to ThemeStore.accent.color,
        onPrimaryColor to if (ThemeStore.accent == AccentColor.BLACK) {
            if (amoled) Color.Black else Theme[colors][onPrimaryColor]
        } else {
            ThemeStore.accent.onContent()
        },
        secondaryColor to if (amoled) Color(0xFF121212) else Theme[colors][secondaryColor],
        onSecondaryColor to Theme[colors][onSecondaryColor],
        controlColor to if (amoled) Color(0xFF1A1A1A) else Theme[colors][controlColor],
        onControlColor to Theme[colors][onControlColor],
        thumbColor to Theme[colors][thumbColor],
        // Off-track stays monochrome. On-track follows a non-black accent; black
        // keeps the existing monochrome selected track.
        switchTrackColor to if (darkTheme) Color(0xFF4A4A4A) else Theme[colors][switchTrackColor],
        switchSelectedTrackColor to if (ThemeStore.accent == AccentColor.BLACK) {
            if (darkTheme) Color.Black else Theme[colors][switchSelectedTrackColor]
        } else {
            ThemeStore.accent.color
        },
        switchThumbColor to if (darkTheme) Color.White else Theme[colors][switchThumbColor],
        selectedControlColor to if (amoled) Color(0xFF222222) else Theme[colors][selectedControlColor],
        onSelectedControlColor to Theme[colors][onSelectedControlColor],
        // Use a softer red in dark mode that's visible but not harsh.
        destructiveColor to if (darkTheme) Color(0xFFEF5350) else Theme[colors][destructiveColor],
        onDestructiveColor to if (amoled) Color.Black else Theme[colors][onDestructiveColor],
        borderColor to if (amoled) Color(0xFF2A2A2A) else Theme[colors][borderColor],
        fieldColor to if (amoled) Color(0xFF121212) else Theme[colors][fieldColor],
        onFieldColor to Theme[colors][onFieldColor],
        scrimColor to Theme[colors][scrimColor],
        ringColor to Theme[colors][ringColor],
    )
    properties[shapes] = mapOf(
        smallShape to Theme[shapes][smallShape],
        mediumShape to Theme[shapes][mediumShape],
        largeShape to Theme[shapes][largeShape],
        buttonShape to Theme[shapes][buttonShape],
        dialogShape to Theme[shapes][dialogShape],
        sheetShape to Theme[shapes][sheetShape],
        menuShape to Theme[shapes][menuShape],
        fieldShape to Theme[shapes][fieldShape],
    )
    properties[shadows] = mapOf(
        raisedShadow to Theme[shadows][raisedShadow],
        overlayShadow to Theme[shadows][overlayShadow],
    )
    properties[alphas] = mapOf(
        disabledAlpha to Theme[alphas][disabledAlpha],
    )
    properties[indications] = mapOf(
        defaultIndicationToken to Theme[indications][defaultIndicationToken],
        inverseIndication to Theme[indications][inverseIndication],
    )
    properties[textSelectionColors] = mapOf(
        defaultTextSelectionColorsToken to Theme[textSelectionColors][defaultTextSelectionColorsToken],
    )
    defaultIndication = Theme[indications][defaultIndicationToken]
    defaultTextStyle = LocalTextStyle.current
    defaultContentColor = LocalContentColor.current
}
