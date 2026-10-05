package com.irio

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ProviderSelectionTest {
    @Before
    fun resetStore() {
        // Gradle redirects all desktop tests away from the user's app data.
        val testHome = requireNotNull(System.getenv("IRIO_HOME"))
        check(File(testHome).canonicalFile == AppPaths.root.canonicalFile)
        AppPaths.providersFile.delete()
        AppPaths.keyFile.delete()
    }

    @After
    fun cleanStore() {
        AppPaths.providersFile.delete()
        AppPaths.keyFile.delete()
    }

    @Test
    fun refreshingDisabledUnselectedProviderDoesNotChangeActiveSelection() {
        val active = provider("active", selected = "kept")
        val disabled = provider("disabled", enabled = false, selected = "old")
        ProviderStore.saveProvider(active)
        ProviderStore.saveProvider(disabled)
        ProviderStore.saveActiveProviderId(active.id)

        ProviderStore.saveProvider(disabled.copy(models = listOf("new")))

        assertEquals(active.id, ProviderStore.loadActiveProviderId())
        assertEquals("kept", ProviderStore.loadProvider()?.selectedModel)
    }

    @Test
    fun removedActiveModelWithMultipleProvidersLeavesSelectionEmpty() {
        val active = provider("active", selected = "old")
        ProviderStore.saveProvider(active)
        ProviderStore.saveProvider(provider("other", selected = "other-model"))
        ProviderStore.saveActiveProviderId(active.id)

        ProviderStore.saveProvider(active.copy(models = listOf("new")))

        assertEquals(active.id, ProviderStore.loadActiveProviderId())
        assertEquals("", ProviderStore.loadProvider()?.selectedModel)
    }

    @Test
    fun refreshingEnabledUnselectedProviderDoesNotChangeActiveSelection() {
        val active = provider("active", selected = "kept")
        val other = provider("other", selected = "old")
        ProviderStore.saveProvider(active)
        ProviderStore.saveProvider(other)
        ProviderStore.saveActiveProviderId(active.id)

        ProviderStore.saveProvider(other.copy(models = listOf("new")))

        assertEquals(active.id, ProviderStore.loadActiveProviderId())
        assertEquals("kept", ProviderStore.loadProvider()?.selectedModel)
        assertEquals("", ProviderStore.loadProvider(other.id)?.selectedModel)
    }

    @Test
    fun soleEnabledProviderReplacesRemovedModelWithFirstEnabledModel() {
        val active = provider("active", selected = "old").copy(disabledModels = setOf("disabled"))
        ProviderStore.saveProvider(active.copy(models = listOf("old", "disabled", "new")))
        ProviderStore.saveActiveProviderId(active.id)

        ProviderStore.saveProvider(active.copy(models = listOf("disabled", "new")))

        assertEquals("new", ProviderStore.loadProvider()?.selectedModel)
    }

    @Test
    fun soleDisabledProviderDoesNotReplaceRemovedModel() {
        val active = provider("active", enabled = false, selected = "old")
        ProviderStore.saveProvider(active)
        ProviderStore.saveActiveProviderId(active.id)

        ProviderStore.saveProvider(active.copy(models = listOf("new")))

        assertEquals("", ProviderStore.loadProvider()?.selectedModel)
    }

    @Test
    fun anotherDisabledProviderStillPreventsAutomaticReplacement() {
        val active = provider("active", selected = "old")
        ProviderStore.saveProvider(active)
        ProviderStore.saveProvider(provider("other", enabled = false, selected = "other-model"))
        ProviderStore.saveActiveProviderId(active.id)

        ProviderStore.saveProvider(active.copy(models = listOf("new")))

        assertEquals("", ProviderStore.loadProvider()?.selectedModel)
    }

    @Test
    fun existingSelectionSurvivesReorderedModels() {
        val active = provider("active", selected = "kept")
        ProviderStore.saveProvider(active)
        ProviderStore.saveActiveProviderId(active.id)

        ProviderStore.saveProvider(active.copy(models = listOf("new", "kept")))

        assertEquals("kept", ProviderStore.loadProvider()?.selectedModel)
    }

    @Test
    fun emptySelectionStaysEmptyOnSubsequentRefreshes() {
        val active = provider("active", selected = "old")
        ProviderStore.saveProvider(active)
        ProviderStore.saveActiveProviderId(active.id)
        ProviderStore.saveProvider(active.copy(models = emptyList()))

        val cleared = requireNotNull(ProviderStore.loadProvider())
        ProviderStore.saveProvider(cleared.copy(models = listOf("new")))

        assertEquals("", ProviderStore.loadProvider()?.selectedModel)
    }

    @Test
    fun manualSelectionStillSwitchesToChosenProvider() {
        val active = provider("active", selected = "kept")
        val other = provider("other", selected = "other-model")
        ProviderStore.saveProvider(active)
        ProviderStore.saveProvider(other)
        ProviderStore.saveActiveProviderId(active.id)

        ProviderStore.saveActiveProviderId(other.id)
        ProviderStore.saveSelectedModel("other-model", other.id)

        assertEquals(other.id, ProviderStore.loadActiveProviderId())
        assertEquals("other-model", ProviderStore.loadProvider()?.selectedModel)
    }

    @Test
    fun clearedSelectionSurvivesSnapshotRoundTrip() {
        val active = provider("active", selected = "old")
        ProviderStore.saveProvider(active)
        ProviderStore.saveActiveProviderId(active.id)
        ProviderStore.saveProvider(active.copy(models = emptyList()))
        ProviderStore.saveProvider(requireNotNull(ProviderStore.loadProvider()).copy(models = listOf("new")))

        ProviderStore.applySnapshot(ProviderStore.snapshot(), overwrite = true)

        assertEquals("", ProviderStore.loadProvider()?.selectedModel)
    }

    private fun provider(id: String, enabled: Boolean = true, selected: String) = ProviderConfig(
        id = id,
        name = id,
        endpointUrl = "https://example.invalid/v1",
        apiKey = "fixture-key",
        enabled = enabled,
        models = listOf(selected),
        selectedModel = selected,
    )
}
