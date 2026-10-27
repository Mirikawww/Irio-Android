package com.irio

import org.junit.Assert.*
import org.junit.Test

class AppTextScaleTest {
    @Test fun settingIsSearchableAndLocalized() {
        val chinese = searchSettings("文本缩放") { it }.single()
        assertEquals("appearance.textScale", chinese.id)
        assertEquals(AppearanceSettingsActivity::class.java, chinese.destination)
        assertEquals("Text scaling", translate(AppLanguage.EN, "文本缩放"))
        val english = searchSettings("Text scaling") { translate(AppLanguage.EN, it) }.single()
        assertEquals(chinese.id, english.id)
    }
}
