package com.irio

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelRefreshDiffTest {
    @Test
    fun addedStayOnTopAndRemovedSink() {
        val previous = listOf("a", "gone", "b", "also-gone")
        val next = listOf("b", "a", "new-z", "new-a")
        val diff = modelRefreshDiff(previous, next)
        assertEquals(setOf("new-z", "new-a"), diff.added)
        assertEquals(listOf("gone", "also-gone"), diff.removed)
        assertEquals(
            listOf(
                ModelDiffEntry("new-z", ModelDiffKind.Added),
                ModelDiffEntry("new-a", ModelDiffKind.Added),
                ModelDiffEntry("b", ModelDiffKind.Unchanged),
                ModelDiffEntry("a", ModelDiffKind.Unchanged),
                ModelDiffEntry("gone", ModelDiffKind.Removed),
                ModelDiffEntry("also-gone", ModelDiffKind.Removed),
            ),
            modelDiffRows(next, diff),
        )
    }

    @Test
    fun identicalListsHaveNoDiff() {
        val models = listOf("a", "b")
        val diff = modelRefreshDiff(models, models)
        assertEquals(emptySet<String>(), diff.added)
        assertEquals(emptyList<String>(), diff.removed)
        assertEquals(
            listOf(
                ModelDiffEntry("a", ModelDiffKind.Unchanged),
                ModelDiffEntry("b", ModelDiffKind.Unchanged),
            ),
            modelDiffRows(models, diff),
        )
    }

    @Test
    fun emptyRefreshKeepsRemovedAtTheBottom() {
        val diff = modelRefreshDiff(listOf("old"), emptyList())
        assertEquals(emptySet<String>(), diff.added)
        assertEquals(listOf("old"), diff.removed)
        assertEquals(
            listOf(ModelDiffEntry("old", ModelDiffKind.Removed)),
            modelDiffRows(emptyList(), diff),
        )
    }
}
