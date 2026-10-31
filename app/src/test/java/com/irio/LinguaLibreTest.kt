package com.irio

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment

internal fun pronunciationFixture(word: String = "hello", language: String = "eng"): JSONObject {
    val title = "File:LL-Q1860 ($language)-Example-$word.wav"
    fun field(value: String) = JSONObject().put("value", value)
    val metadata = JSONObject().put("Categories", field("Lingua Libre pronunciation-$language|Other"))
        .put("Artist", field("<b>Example speaker</b>"))
        .put("LicenseShortName", field("CC BY-SA 4.0"))
        .put("LicenseUrl", field("https://creativecommons.org/licenses/by-sa/4.0/"))
    val info = JSONObject().put("mime", "audio/wav").put("size", 1000)
        .put("url", "https://upload.wikimedia.org/wikipedia/commons/a/ab/recording.wav?tracking=1")
        .put("descriptionurl", "https://commons.wikimedia.org/wiki/$title").put("extmetadata", metadata)
    return JSONObject().put("query", JSONObject().put("pages", JSONArray().put(JSONObject()
        .put("pageid", 123).put("title", title).put("imageinfo", JSONArray().put(info)))))
}

internal fun pronunciationEntity(word: String = "hello", title: String = "File:LL-Q1860 (eng)-Example-hello.wav") =
    JSONObject().put("entities", JSONObject().put("M123", JSONObject().put("title", title)
        .put("statements", JSONObject().put("P9533", JSONArray().put(JSONObject().put("mainsnak", JSONObject()
            .put("datavalue", JSONObject().put("value", JSONObject().put("text", word).put("language", "en")))))))))

internal fun fixturePronunciation(): PronunciationRecording = LinguaLibreClient.parse(pronunciationFixture(), "hello", "eng").single()
internal fun pronunciationToolRecord(recording: PronunciationRecording = fixturePronunciation()): ChatToolRecord = ChatToolRecord(
    ChatToolCall("pronunciation-1", LinguaLibreTool.NAME, """{"word":"hello","language":"eng"}"""),
    JSONObject().put("recordings", JSONArray().put(recording.toJson())).toString(),
)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class LinguaLibreTest {
    @After fun resetHttp() { IrioHttp.resetForTests() }

    @Test fun extractsOnlyLinguaLibreAudioAndPreservesAttribution() {
        val recording = fixturePronunciation()
        assertEquals("Example speaker", recording.author)
        assertEquals("CC BY-SA 4.0", recording.license)
        assertFalse(recording.audioUrl.contains('?'))
        assertEquals("[hello](irio-pronunciation:${recording.id})", recording.toJson().getString("marker"))
        assertTrue(LinguaLibreClient.parse(pronunciationFixture("hello world"), "hello", "eng").isEmpty())
        assertTrue(LinguaLibreClient.parse(pronunciationFixture(language = "fra"), "hello", "eng").isEmpty())
        val bad = pronunciationFixture()
        bad.getJSONObject("query").getJSONArray("pages").getJSONObject(0).getJSONArray("imageinfo").getJSONObject(0)
            .put("url", "http://127.0.0.1/private.wav")
        assertTrue(LinguaLibreClient.parse(bad, "hello", "eng").isEmpty())
    }

    @Test fun verifiesTheExactSpokenTextUsingCommonsStructuredData() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(pronunciationFixture().toString()))
            server.enqueue(MockResponse().setBody(pronunciationEntity().toString()))
            val found = LinguaLibreClient(server.url("/api.php").toString()).search("hello", "eng", ChatRequestCancellation(), "Irio/Test")
            assertEquals("hello", found.single().word)
            val search = server.takeRequest()
            assertTrue(search.requestUrl!!.queryParameter("gsrsearch")!!.contains("Lingua Libre pronunciation-eng"))
            assertEquals("Irio/Test", search.getHeader("User-Agent"))
            assertNull(search.getHeader("Authorization"))
            assertEquals("M123", server.takeRequest().requestUrl!!.queryParameter("ids"))
        }
    }

    @Test fun filenameSuffixAloneCannotMislabelACompoundWord() {
        MockWebServer().use { server ->
            val title = "File:LL-Q1860 (eng)-Example-say-hello.wav"
            server.enqueue(MockResponse().setBody(pronunciationFixture("say-hello").toString()))
            server.enqueue(MockResponse().setBody(pronunciationEntity("say-hello", title).toString()))
            assertTrue(LinguaLibreClient(server.url("/api.php").toString())
                .search("hello", "eng", ChatRequestCancellation(), "Irio/Test").isEmpty())
        }
    }

    @Test fun realRegistryPublishesSearchResultForTheNextModelTurn() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(pronunciationFixture().toString()))
            server.enqueue(MockResponse().setBody(pronunciationEntity().toString()))
            val registry = ToolRegistry(listOf(LinguaLibreToolProvider(LinguaLibreClient(server.url("/api.php").toString()))))
            val updates = mutableListOf<ChatStreamUpdate>()
            val result = registry.execute(pronunciationToolRecord().call, ToolExecutionContext(
                ProviderConfig("p", "P", "https://example.com", ""), "m", onUpdate = { updates += it }))
            assertEquals(ToolOutcome.SUCCESS, result.outcome)
            assertEquals(1, JSONObject(result.text).getJSONArray("recordings").length())
            assertEquals(listOf(true, false), updates.map { it.toolActive })
            assertEquals(result.text, updates.last().toolContent)
        }
    }

    @Test fun pronunciationIsAvailableWithoutPaidSearchAndObeysToolPreferences() {
        val context = RuntimeEnvironment.getApplication()
        ShadowEnvironment.addExternalDir(context.cacheDir.resolve("external").absolutePath)
        val active = chatToolRegistry(context, webSearch = false, imageGenModel = "")
        assertTrue(active.handles(LinguaLibreTool.NAME))
        assertTrue(ToolCatalog.entries(ToolFeatureState()).any { it.name == LinguaLibreTool.NAME && it.blockedReason == null })
        for (protocol in ProviderProtocol.entries) {
            assertTrue(active.definitions(protocol).any { (it.optJSONObject("function") ?: it).optString("name") == LinguaLibreTool.NAME })
        }
        ToolPreferencesStore.saveEnabled(context, LinguaLibreTool.NAME, false)
        val disabled = chatToolRegistry(context, false, "")
        assertFalse(disabled.handles(LinguaLibreTool.NAME))
        assertFalse(disabled.systemPrompt(null).orEmpty().contains(LinguaLibreTool.NAME))
    }

    @Test fun metadataSurvivesSaveAndLinkButCannotLeakBetweenReplyVersions() {
        val record = pronunciationToolRecord()
        val reply = ChatMessage(2, record.result, false, toolRecords = listOf(record))
        val original = Conversation("pronunciation", "Example", 1, listOf(ChatMessage(1, "hello", true), reply))
        val local = ConversationStore.parseConversation(ConversationStore.conversationJson(original))
        val linked = LinkConversationJson.decode(LinkConversationJson.encode(local))
        listOf(local, linked).forEach { conversation ->
            val loaded = pronunciationRecordings(conversation.messages.last().toolRecords).values.single()
            assertEquals(fixturePronunciation(), loaded)
            assertEquals("CC BY-SA 4.0", loaded.license)
        }
        assertTrue(pronunciationRecordings(emptyList()).isEmpty())
        val tampered = JSONObject(record.result)
        tampered.getJSONArray("recordings").getJSONObject(0).put("audio_url", "https://evil.example/test.wav")
        assertTrue(pronunciationRecordings(listOf(record.copy(result = tampered.toString()))).isEmpty())
    }

    @Test fun actualRequestsIncludeTheToolAndInstructionsAcrossEveryProviderProtocol() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        ShadowEnvironment.addExternalDir(context.cacheDir.resolve("external").absolutePath)
        ToolPreferencesStore.saveDescription(context, LinguaLibreTool.NAME, "A custom pronunciation description")
        MockWebServer().use { model ->
            for (enabled in listOf(true, false)) {
                ToolPreferencesStore.saveEnabled(context, LinguaLibreTool.NAME, enabled)
                for (protocol in ProviderProtocol.entries.filter { it != ProviderProtocol.MINIMAX_VIDEO }) {
                    model.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(""))
                    val registry = chatToolRegistry(context, false, "")
                    ChatApi.streamChat(ProviderConfig("test", "Test", model.url("/v1beta").toString(), "fixture", protocol),
                        "model", listOf(ChatRequestMessage("user", "pronounce hello")), ReasoningEffort.OFF, false,
                        toolRegistry = registry, systemPrompt = registry.systemPrompt(null),
                        cancellation = ChatRequestCancellation(), onUpdate = {})
                    val body = model.takeRequest().body.readUtf8()
                    assertEquals("$protocol must obey the pronunciation toggle", enabled, body.contains("\"${LinguaLibreTool.NAME}\""))
                    assertEquals(enabled, body.contains("A custom pronunciation description"))
                    assertEquals(enabled, body.contains("irio-pronunciation:ID"))
                }
            }
        }
    }

    @Test fun audioDownloadsAreCachedAndNeverReceiveProviderCredentials() {
        val directory = Files.createTempDirectory("irio-pronunciation-cache").toFile()
        var requests = 0
        val bytes = byteArrayOf(82, 73, 70, 70, 1, 2, 3)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            assertEquals("upload.wikimedia.org", chain.request().url.host)
            assertNull(chain.request().header("Authorization"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(bytes.toResponseBody("audio/wav".toMediaType())).build()
        }.build()
        try {
            val cache = PronunciationAudioCache(client)
            val first = cache.fetch(directory, fixturePronunciation(), ChatRequestCancellation(), "Irio/Test")
            assertArrayEquals(bytes, first.readBytes())
            assertEquals(first, cache.fetch(directory, fixturePronunciation(), ChatRequestCancellation(), "Irio/Test"))
            assertEquals(1, requests)
            assertFalse(directory.listFiles().orEmpty().any { it.extension == "part" })
        } finally { directory.deleteRecursively() }
    }

    @Test fun rawAudioUrlsCannotEscapeTheTrustedCommonsHost() {
        listOf("http://upload.wikimedia.org/wikipedia/commons/a/a.wav", "https://upload.wikimedia.org.evil.test/wikipedia/commons/a.wav",
            "https://name:secret@upload.wikimedia.org/wikipedia/commons/a.wav", "https://upload.wikimedia.org:444/wikipedia/commons/a.wav",
            "https://upload.wikimedia.org/private.wav").forEach { assertNull(it, pronunciationAudioUrl(it)) }
    }

    @Test fun pronunciationLookupIsSilentUnlessFailedOrStoppedAndNeverInflatesMergedCount() {
        val record = pronunciationToolRecord()
        val currentNotice = "waiting"
        for (outcome in listOf<ToolOutcome?>(null, ToolOutcome.SUCCESS, ToolOutcome.FAILED, ToolOutcome.STOPPED)) {
            val event = ProcessEvent(1, ProcessEventKind.TOOL, record.result, live = outcome == null,
                toolName = LinguaLibreTool.NAME, toolOutcome = outcome, displayText = "custom lookup label")
            val exceptional = outcome == ToolOutcome.FAILED || outcome == ToolOutcome.STOPPED
            val expected = if (exceptional) listOf(event) else emptyList()
            for (merge in listOf(false, true)) {
                assertEquals("Failed/stopped lookups must remain visible (outcome=$outcome, merge=$merge)",
                    expected, renderedProcessEvents(listOf(event), merge))
                val time = ProcessEvent(2, ProcessEventKind.TOOL, toolName = TimeTool.NAME, toolOutcome = ToolOutcome.SUCCESS)
                assertEquals(expected + renderedProcessEvents(listOf(time), merge), renderedProcessEvents(listOf(event, time), merge))
            }
            assertEquals("", mergedToolSummary(listOf(event), AppLanguage.ZH_CN))
            val expectedNotice = when (outcome) {
                ToolOutcome.FAILED -> tr("检索发音失败")
                ToolOutcome.STOPPED -> tr("发音检索已停止")
                else -> currentNotice
            }
            assertEquals(expectedNotice, advanceReplyNotice(currentNotice, ChatStreamUpdate(
                toolActive = outcome == null, toolName = LinguaLibreTool.NAME, toolOutcome = outcome, displayText = "custom lookup label")))
            assertEquals(fixturePronunciation(), pronunciationRecordings(listOf(record)).values.single())
        }
        assertEquals(currentNotice, advanceReplyNotice(currentNotice, ChatStreamUpdate(notifyTool = LinguaLibreTool.NAME)))
        val registry = ToolRegistry(listOf(LinguaLibreToolProvider()), smartToolHints = true)
        ProviderProtocol.entries.forEach { protocol ->
            assertFalse("Silent lookup needs no visible hint in $protocol", registry.definitions(protocol).single().toString().contains(SmartToolHints.ARGUMENT))
        }
    }
}
