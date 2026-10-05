package com.irio

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelRefreshSelectionTest {
    private val active = ProviderConfig(
        id = "active",
        name = "Active",
        endpointUrl = "https://example.invalid/v1",
        apiKey = "fixture-key",
        models = listOf("old", "kept"),
        selectedModel = "old",
    )

    @Test
    fun retainedModelIsNotChangedByReordering() {
        assertEquals("old", selection(active.copy(models = listOf("new", "old"))))
    }

    @Test
    fun soleEnabledActiveProviderCanReplaceDeletedModel() {
        assertEquals("new", selection(active.copy(models = listOf("new"))))
    }

    @Test
    fun disabledProviderCannotReplaceDeletedModel() {
        assertEquals("", selection(active.copy(enabled = false, models = listOf("new"))))
    }

    @Test
    fun anotherProviderEvenWhenDisabledPreventsReplacement() {
        val other = active.copy(id = "other", enabled = false)
        assertEquals("", selection(active.copy(models = listOf("new")), providers = listOf(active, other)))
    }

    @Test
    fun unselectedProviderCannotReplaceDeletedModel() {
        assertEquals("", selection(active.copy(models = listOf("new")), activeId = "other"))
    }

    @Test
    fun absentPriorSelectionIsNotTreatedAsDeletedModel() {
        val unselected = active.copy(selectedModel = "")
        assertEquals("", selection(unselected.copy(models = listOf("new")), providers = listOf(unselected)))
    }

    @Test
    fun replacementSkipsDisabledModels() {
        assertEquals("kept", selection(active.copy(models = listOf("disabled", "kept"), disabledModels = setOf("disabled"))))
    }

    @Test
    fun noEnabledReplacementLeavesSelectionEmpty() {
        assertEquals("", selection(active.copy(models = listOf("kept"), disabledModels = setOf("kept"))))
        assertEquals("", selection(active.copy(models = emptyList())))
    }

    @Test
    fun initialProviderSetupStillSelectsFirstEnabledModel() {
        val created = active.copy(selectedModel = "", disabledModels = setOf("old"))
        assertEquals("kept", selection(created, providers = emptyList(), activeId = ""))
    }

    private fun selection(
        updated: ProviderConfig,
        providers: List<ProviderConfig> = listOf(active),
        activeId: String = active.id,
    ): String = selectedModelAfterProviderSave(providers, activeId, updated)
}
