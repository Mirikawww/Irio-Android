package com.irio

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.fonts.FontStyle as PlatformFontStyle
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight

enum class AppFont(val label: String, val stored: String) {
    SANS("非衬线体", "sans"),
    SERIF("衬线体", "serif");

    companion object {
        fun fromStored(value: String?): AppFont =
            entries.firstOrNull { it.stored.equals(value, ignoreCase = true) } ?: SANS
    }
}

private val LoraAxisWeights = listOf(100, 200, 300, 400, 500, 600, 700, 800, 900)

private fun loraFont(resId: Int, weight: Int, style: FontStyle) = LoraAxisFont(
    resourceId = resId,
    axisWeight = weight,
    weight = FontWeight(weight),
    style = style,
)

private fun loraFamily(weights: List<Int>) = FontFamily(
    weights.flatMap { weight ->
        listOf(
            loraFont(R.font.lora_variable, weight, FontStyle.Normal),
            loraFont(R.font.lora_italic_variable, weight, FontStyle.Italic),
        )
    },
)

/** 字重轴上的 Lora。系统粗细调节会落到最近的一档。 */
internal val LoraFontFamily = loraFamily(LoraAxisWeights)

/** 读不到系统粗细时只用 Regular。 */
internal val LoraRegularFamily = loraFamily(listOf(400))

/** API 31 以下或读取失败时返回 null。 */
internal fun systemFontWeightAdjustment(configuration: Configuration): Int? =
    try {
        if (Build.VERSION.SDK_INT >= 31) configuration.fontWeightAdjustment else null
    } catch (_: Throwable) {
        null
    }

/**
 * 可变字体沿用文字自己的字重，再加上系统粗细调节。
 * 系统粗细读不到时整段退回 Regular（400）。
 */
internal fun resolvedVariableWeight(requested: FontWeight, adjustment: Int?): Int {
    if (adjustment == null) return 400
    return (requested.weight + adjustment).coerceIn(1, 1000)
}

/**
 * 资源字体的默认加载会丢掉 `FontVariation`，同一份可变字体文件会被画成默认字重。
 * 这里按字重轴自己建 Typeface，系统粗细调节才能落到对应的 wght。
 */
private class LoraAxisFont(
    val resourceId: Int,
    val axisWeight: Int,
    override val weight: FontWeight,
    override val style: FontStyle,
) : AndroidFont(
    loadingStrategy = FontLoadingStrategy.Blocking,
    typefaceLoader = LoraTypefaceLoader,
    variationSettings = FontVariation.Settings(FontVariation.weight(axisWeight)),
)

private object LoraTypefaceLoader : AndroidFont.TypefaceLoader {
    override fun loadBlocking(context: Context, font: AndroidFont): Typeface {
        val lora = font as LoraAxisFont
        val slant = if (lora.style == FontStyle.Italic) {
            PlatformFontStyle.FONT_SLANT_ITALIC
        } else {
            PlatformFontStyle.FONT_SLANT_UPRIGHT
        }
        val face = android.graphics.fonts.Font.Builder(context.resources, lora.resourceId)
            .setWeight(lora.axisWeight)
            .setSlant(slant)
            .setFontVariationSettings("'wght' ${lora.axisWeight}")
            .build()
        val family = android.graphics.fonts.FontFamily.Builder(face).build()
        return Typeface.CustomFallbackBuilder(family)
            .setStyle(PlatformFontStyle(lora.axisWeight, slant))
            .build()
    }

    override suspend fun awaitLoad(context: Context, font: AndroidFont): Typeface? =
        loadBlocking(context, font)
}

@Composable
internal fun rememberAppFontFamily(): FontFamily? {
    if (ThemeStore.appFont != AppFont.SERIF) return null
    val configuration = LocalContext.current.resources.configuration
    val adjustment = remember(configuration) { systemFontWeightAdjustment(configuration) }
    return if (adjustment == null) LoraRegularFamily else LoraFontFamily
}
