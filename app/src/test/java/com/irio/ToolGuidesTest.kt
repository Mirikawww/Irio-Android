package com.irio



import org.json.JSONObject

import org.junit.Assert.*

import org.junit.Test

import org.junit.runner.RunWith

import org.robolectric.RobolectricTestRunner

import org.robolectric.RuntimeEnvironment

import org.robolectric.annotation.Config



@RunWith(RobolectricTestRunner::class)

@Config(sdk = [33], manifest = Config.NONE)

class ToolGuidesTest {
    @Test fun successfulRealRegistryCallEmitsOneVisibleCompletedTutorialHint() = kotlinx.coroutines.runBlocking {
        val turn = SubagentTurn()
        val updates = mutableListOf<ChatStreamUpdate>()
        val registry = ToolRegistry(listOf(ToolGuideProvider(index)),
            preferences = ToolPreferences(disabledTools = setOf(ToolGuideProvider.NAME)))
        val result = registry.execute(
            ChatToolCall("tutorial", ToolGuideProvider.NAME, """{"query":"web_search"}"""),
            ToolExecutionContext(ProviderConfig("fixture", "Fixture", "https://example.test", "fixture"), "model",
                onUpdate = { updates += it; turn.apply(it) }),
        )
        assertEquals(ToolOutcome.SUCCESS, result.outcome)
        assertEquals("Successful retrieval must emit start and finish, not silently return", listOf(true, false),
            updates.mapNotNull { it.toolActive })
        val event = turn.process().single()
        assertEquals(ToolGuideProvider.NAME, event.toolName)
        assertFalse(event.live)
        assertEquals(ToolOutcome.SUCCESS, event.toolOutcome)
        assertEquals(result.text, event.content)
        assertEquals("已搜索工具教程", event.completedLabel())
        assertTrue(renderedProcessEvents(turn.process(), false).contains(event))
    }


    @Test fun invocationHintShowsLocalizedProgressCompletionFailureAndStop() {

        assertEquals("正在搜索工具教程", MemoryTool.liveLabel(ToolGuideProvider.NAME))

        assertEquals("已搜索工具教程", MemoryTool.doneLabel(ToolGuideProvider.NAME))

        assertEquals("工具教程搜索失败", toolFailureLabel(ToolGuideProvider.NAME))

        assertEquals("工具教程搜索已停止", toolStoppedLabel(ToolGuideProvider.NAME))

        assertEquals("Searching tool tutorials", translate(AppLanguage.EN, MemoryTool.liveLabel(ToolGuideProvider.NAME)))

        assertEquals("Searched tool tutorials", translate(AppLanguage.EN, MemoryTool.doneLabel(ToolGuideProvider.NAME)))

        assertEquals("Tool tutorial search failed", translate(AppLanguage.EN, toolFailureLabel(ToolGuideProvider.NAME)))

        assertEquals("Tool tutorial search stopped", translate(AppLanguage.EN, toolStoppedLabel(ToolGuideProvider.NAME)))

    }



    private val index = ToolGuideIndex.parse(

        """{"version":1,"guides":[

          {"id":"web-search","tool":"web_search","title":"网页搜索教程","aliases":["查资料","search"],"pages":[

            {"content":"需要当前网页信息时调用 web_search。参数 query 是具体搜索词；结果是来源和摘要，不是已验证的完整网页。"},

            {"content":"之后用 web_fetch 读取可信来源。"}]},

          {"id":"web-fetch","tool":"web_fetch","title":"读取网页教程","aliases":["网页正文"],"pages":[

            {"content":"已知完整 HTTPS URL 时调用 web_fetch，参数 url 必须是页面地址。"}]}

        ]}""",

    )



    @Test fun searchRanksRelevantGuidesAndPaginatesSmallPages() {

        val first = JSONObject(ToolGuideIndex.search(index, "网页", offset = 0, limit = 1))

        assertEquals(2, first.getInt("total"))

        assertEquals("web-fetch", first.getJSONArray("results").getJSONObject(0).getString("guideId"))

        assertTrue(first.getBoolean("hasMore"))

        assertEquals(1, first.getInt("nextOffset"))

        val second = JSONObject(ToolGuideIndex.search(index, "网页", offset = first.getInt("nextOffset"), limit = 1))

        assertEquals("web-search", second.getJSONArray("results").getJSONObject(0).getString("guideId"))

        assertFalse(second.getBoolean("hasMore"))

        assertTrue(second.getJSONArray("results").getJSONObject(0).getString("content").length <= ToolGuideIndex.MAX_CHUNK_CHARS)

    }



    @Test fun guideSearchIsAlwaysRegisteredEvenWhenDisabledInPreferencesAndKeepsExistingPrompts() {

        val provider = ToolGuideProvider(index)

        val registry = ToolRegistry(

            providers = listOf(provider, ColoredBubbleToolProvider()),

            preferences = ToolPreferences(disabledTools = setOf(ToolGuideProvider.NAME)),

        )

        assertTrue(registry.handles(ToolGuideProvider.NAME))

        assertEquals(1, registry.definitions(ProviderProtocol.CHAT_COMPLETIONS).count {

            (it.optJSONObject("function") ?: it).optString("name") == ToolGuideProvider.NAME

        })

        val prompt = registry.systemPrompt("Original base prompt").orEmpty()

        assertTrue(prompt.contains("Original base prompt"))

        assertTrue(prompt.contains("Colored bubble output is available"))

        assertTrue(prompt.contains("Local tool tutorials are available"))

        assertFalse(provider.definitions(ProviderProtocol.CHAT_COMPLETIONS).single().optBoolean("strict"))

    }



    @Test fun searchRejectsBlankQueriesAndInvalidPagination() {

        assertThrows(IllegalArgumentException::class.java) {

            ToolGuideIndex.search(index, "   ")

        }

        assertThrows(IllegalArgumentException::class.java) {

            ToolGuideIndex.search(index, "web_search", offset = -1)

        }

        assertThrows(IllegalArgumentException::class.java) {

            ToolGuideIndex.search(index, "web_search", limit = ToolGuideIndex.MAX_RESULTS + 1)

        }

    }



    @Test fun bundledLocalIndexCoversEveryBuiltInModelTool() {

        val bundled = ToolGuideIndex.load(RuntimeEnvironment.getApplication())

        val documented = bundled.guides.map { it.tool }.toSet()

        val builtIns = ToolRegistry.default().providers().flatMap { it.toolNames() }.toSet() + setOf(

            MemoryTool.GET, MemoryTool.UPDATE, *SubagentTools.NAMES.toTypedArray(),

            LinuxShellTool.NAME, LinuxBackgroundTool.NAME,

        )

        assertTrue("Missing local tutorials: ${builtIns - documented}", documented.containsAll(builtIns))

        assertTrue("The configurable MCP integrations need a schema-first tutorial", "mcp" in documented)

    }

}
