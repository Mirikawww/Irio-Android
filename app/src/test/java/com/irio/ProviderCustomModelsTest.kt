package com.irio

import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ProviderCustomModelsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val keyField = ProviderStore::class.java.getDeclaredField("cachedSecretKey").apply { isAccessible = true }
    private var oldKey: Any? = null

    @Before fun setup() {
        oldKey = keyField.get(ProviderStore)
        keyField.set(ProviderStore, SecretKeySpec(ByteArray(32) { 7 }, "AES"))
        ProviderStore.preferences(context).edit().clear().commit()
        ProviderStore.saveProvider(context, ProviderConfig("one", "One", "https://example.test", "fixture",
            models = listOf("server", "disabled"), disabledModels = setOf("disabled"), selectedModel = "server"))
        ProviderStore.saveProvider(context, ProviderConfig("two", "Two", "https://other.test", "other-fixture",
            models = listOf("other"), selectedModel = "other", enabled = false))
        ProviderStore.saveActiveProviderId(context, "two")
    }

    @After fun cleanup() {
        ProviderStore.preferences(context).edit().clear().commit()
        keyField.set(ProviderStore, oldKey)
    }

    @Test fun addTrimsIdAndPreservesAllOtherProviderAndSelectionState() {
        val other = ProviderStore.loadProvider(context, "two")
        val before = ProviderStore.loadProvider(context, "one")!!
        assertNull(ProviderStore.addCustomModel(context, "one", "  vendor/My_Model:custom  "))
        val saved = ProviderStore.loadProvider(context, "one")!!
        assertEquals(before.models + "vendor/My_Model:custom", saved.models)
        assertEquals(listOf("vendor/My_Model:custom"), saved.customModels)
        assertTrue("New ID should be usable by the normal model picker", "vendor/My_Model:custom" in saved.enabledModels())
        assertEquals(before, saved.copy(models = before.models, customModels = emptyList()))
        assertEquals(other, ProviderStore.loadProvider(context, "two"))
        assertEquals("two", ProviderStore.loadActiveProviderId(context))
    }

    @Test fun validationCannotWriteEmptyDuplicateOrMultiIdInput() {
        val before = ProviderStore.snapshot(context).toString()
        for (input in listOf("", " \n ", "server", " server ", "bad id", "a\tb", "a\u0000b")) {
            assertNotNull(input, ProviderStore.addCustomModel(context, "one", input))
            assertEquals(before, ProviderStore.snapshot(context).toString())
        }
        assertEquals("找不到这个提供商", ProviderStore.addCustomModel(context, "missing", "valid"))
        assertEquals(before, ProviderStore.snapshot(context).toString())
    }

    @Test fun multilineInputAddsEveryNonblankLineInOrderAsOneSave() {
        val ids = listOf("gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna")
        assertNull(ProviderStore.addCustomModel(context, "one", " \n ${ids[0]} \r\n${ids[1]}\n\n${ids[2]}\n"))
        val saved = ProviderStore.loadProvider(context, "one")!!
        assertEquals(ids, saved.customModels)
        assertEquals(listOf("server", "disabled") + ids, saved.models)
        assertTrue(saved.enabledModels().containsAll(ids))
        assertEquals("server", saved.selectedModel)
        assertEquals("two", ProviderStore.loadActiveProviderId(context))
    }

    @Test fun invalidOrDuplicateLineRejectsTheWholeBatchWithoutPartialWrites() {
        val before = ProviderStore.snapshot(context).toString()
        for (input in listOf("new\nserver", "new\nnew", "new\nbad id", "new\nbad\tid")) {
            assertNotNull(ProviderStore.addCustomModel(context, "one", input))
            assertEquals("No partial model list may be saved", before, ProviderStore.snapshot(context).toString())
        }
    }

    @Test fun deletionOnlyRemovesTheTargetManualIdAndSurvivesRefreshAndBackup() {
        ProviderStore.addCustomModel(context, "one", "manual-one\nmanual-two")
        ProviderStore.saveDisabledModels(context, "one", setOf("disabled", "manual-one"))
        val before = ProviderStore.snapshot(context).toString()
        assertFalse(ProviderStore.deleteCustomModel(context, "one", "server"))
        assertFalse(ProviderStore.deleteCustomModel(context, "missing", "manual-one"))
        assertEquals(before, ProviderStore.snapshot(context).toString())
        val other = ProviderStore.loadProvider(context, "two")
        assertTrue(ProviderStore.deleteCustomModel(context, "one", "manual-one"))
        val saved = ProviderStore.loadProvider(context, "one")!!
        assertEquals(listOf("manual-two"), saved.customModels)
        assertEquals(listOf("server", "disabled", "manual-two"), saved.models)
        assertEquals(setOf("disabled"), saved.disabledModels)
        assertEquals("server", saved.selectedModel)
        assertEquals(other, ProviderStore.loadProvider(context, "two"))
        assertEquals("two", ProviderStore.loadActiveProviderId(context))
        assertEquals(listOf("server", "manual-two"), saved.withRefreshedModels(listOf("server")).models)
        val snapshot = ProviderStore.snapshot(context)
        ProviderStore.applySnapshot(context, snapshot, overwrite = true)
        assertEquals(saved, ProviderStore.loadProvider(context, "one"))
        assertFalse(ProviderStore.deleteCustomModel(context, "one", "manual-one"))
        assertNull(ProviderStore.addCustomModel(context, "one", "manual-one"))
    }

    @Test fun refreshAndConfigurationSaveKeepCustomIdsAndDisabledChoices() {
        ProviderStore.addCustomModel(context, "one", "manual")
        ProviderStore.saveDisabledModels(context, "one", setOf("manual", "disabled"))
        val before = ProviderStore.loadProvider(context, "one")!!
        val refreshed = before.withRefreshedModels(listOf("new", "manual", "new"))
        assertEquals(listOf("new", "manual"), refreshed.models)
        assertEquals(listOf("manual"), refreshed.customModels)
        assertTrue(modelRefreshDiff(before.models, refreshed.models).removed.none { it == "manual" })
        ProviderStore.saveProvider(context, refreshed)
        val saved = ProviderStore.loadProvider(context, "one")!!
        assertEquals(listOf("new", "manual"), saved.models)
        assertEquals(setOf("manual"), saved.disabledModels)
        assertEquals(listOf("manual"), saved.withRefreshedModels(emptyList()).models)
        assertEquals("two", ProviderStore.loadActiveProviderId(context))
    }

    @Test fun backupImportPreservesCustomIdsAndLegacyProvidersNeedNoMigration() {
        ProviderStore.addCustomModel(context, "one", "manual")
        val snapshot = ProviderStore.snapshot(context)
        val expected = ProviderStore.loadProviders(context)
        ProviderStore.preferences(context).edit().clear().commit()
        ProviderStore.applySnapshot(context, snapshot, overwrite = true)
        assertEquals(expected, ProviderStore.loadProviders(context))
        assertEquals("two", ProviderStore.loadActiveProviderId(context))
        val legacy = JSONObject().put("providers", JSONArray().put(JSONObject()
            .put("id", "legacy").put("apiKey", "fixture").put("models", JSONArray().put("server"))))
        ProviderStore.applySnapshot(context, legacy, overwrite = true)
        val loaded = ProviderStore.loadProviders(context).single()
        assertEquals(listOf("server"), loaded.models)
        assertTrue(loaded.customModels.isEmpty())
    }

    @Test fun addingDoesNotAutoSelectAnUnselectedModelOrEnableADisabledProvider() {
        val current = ProviderStore.loadProvider(context, "one")!!
        ProviderStore.saveProvider(context, current.copy(selectedModel = "", enabled = false))
        ProviderStore.addCustomModel(context, "one", "manual")
        val saved = ProviderStore.loadProvider(context, "one")!!
        assertEquals("", saved.selectedModel)
        assertFalse(saved.enabled)
    }
}
