package com.irio

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeToolTest {
    @Test
    fun nowReturnsLocalTimeWithDateWeekdayAndZone() {
        val text = TimeTool.now()
        assertTrue(text.contains("当前时间："))
        assertTrue(Regex("""\d{4}年\d{1,2}月\d{1,2}日""").containsMatchIn(text))
        assertTrue(Regex("""星期[一二三四五六日天]""").containsMatchIn(text))
        assertTrue(Regex("""\d{2}:\d{2}:\d{2}""").containsMatchIn(text))
        assertTrue(text.contains("时区"))
    }

    @Test
    fun schemaForAllProtocolsHasNoParameters() {
        ProviderProtocol.entries.forEach { protocol ->
            val schema = TimeTool.schemaFor(protocol)
            val params: JSONObject = when (protocol) {
                ProviderProtocol.GEMINI -> schema.getJSONObject("parametersJsonSchema")
                ProviderProtocol.GEMINI_INTERACTIONS -> schema.getJSONObject("parameters")
                ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.MINIMAX_VIDEO -> schema.getJSONObject("function").getJSONObject("parameters")
                ProviderProtocol.RESPONSES -> schema.getJSONObject("parameters")
                ProviderProtocol.ANTHROPIC -> schema.getJSONObject("input_schema")
            }
            assertEquals(0, params.optJSONObject("properties")?.length() ?: 0)
            assertEquals("object", params.optString("type"))
            assertNotNull(params.optJSONArray("required"))
        }
    }

    @Test
    fun schemaNameIsStable() {
        ProviderProtocol.entries.forEach { protocol ->
            val schema = TimeTool.schemaFor(protocol)
            val name = when (protocol) {
                ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.MINIMAX_VIDEO -> schema.getJSONObject("function").optString("name")
                else -> schema.optString("name")
            }
            assertEquals("get_time", name)
        }
    }
}
