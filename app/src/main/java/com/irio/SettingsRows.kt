package com.irio

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 设置项的统一最小行高。与 ListRowButton（ButtonSize.Large）实测的 52dp 一致，
 * 开关行、下拉行、按钮行都按这个高度对齐，避免同一页里有的高有的矮。
 * 行自己占满这段高度，卡片只留左右内边距，不再额外加上下内边距。
 */
internal val SettingsRowMinHeight = 52.dp

/** 整张卡片就是一行的设置项（启用记忆、启用 Linux 沙盒等）：撑满宽度，至少 [SettingsRowMinHeight] 高。 */
internal fun Modifier.settingsRow(): Modifier =
    fillMaxWidth().heightIn(min = SettingsRowMinHeight)

/**
 * 设置项的视觉间距按「文字/控件墨迹到相邻项」来定，不追求代码里数值相同。
 * 各控件自带的内部留白不同（两行文字上方约 4.6dp，开关为 0，滑条标签上方约 3.4dp），
 * 所以每种行单独取值，使相邻项墨迹相距约 15 到 16dp，首尾项离卡片边约 14dp。
 * 数值来自逐页截图的像素测量，改动控件或字号后要重新量。
 */
internal val SettingsRowInset = 6.dp

/** 卡片首尾的额外留白。首尾项离卡片边 = 它 + 该项自己的上/下留白 + 控件内部留白。 */
internal val SettingsCardInset = 6.dp

/** 单行开关：开关本身约 24dp 高，上下各 7dp，相邻开关墨迹相距 14dp。 */
internal fun Modifier.settingsSwitchRow(): Modifier = fillMaxWidth().padding(vertical = 7.dp)

/** 两行文字（标题加当前值）的下拉/选择行。放在 clickable 之后，点击区域包含留白。 */
internal fun Modifier.settingsDropdownInset(): Modifier = padding(top = 3.5.dp, bottom = 6.dp)

/** 只有一行文字加右侧 24dp 图标的可点击行（编辑系统提示词）。 */
internal fun Modifier.settingsPromptInset(): Modifier = padding(vertical = 4.dp)

/** 滑条：标签上方自带约 3.4dp，底部是滑轨本身，没有内部留白。 */
internal fun Modifier.settingsSliderInset(): Modifier = padding(top = 4.5.dp, bottom = 8.dp)
