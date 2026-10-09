package com.irio

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.composables.ui.components.BottomSheetDetent
import com.composables.ui.components.rememberBottomSheetState
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class GitHubMcpSheetStateTest {
    @Test
    fun patOpensAboveInstallationWithoutDismissingIt() = runTest {
        val recomposer = Recomposer(coroutineContext)
        val composition = Composition(NoNodesApplier(), recomposer)
        lateinit var sheets: GitHubMcpSheetState
        try {
            composition.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    sheets = GitHubMcpSheetState(
                        rememberBottomSheetState(initialDetent = BottomSheetDetent.FullyExpanded),
                        rememberBottomSheetState(),
                    )
                }
            }
            sheets.openLogin(GitHubLogin.PAT)
            runCurrent()
            assertEquals("Installation must remain underneath authentication",
                BottomSheetDetent.FullyExpanded, sheets.details.targetDetent)
            assertEquals(GitHubLogin.PAT, sheets.login)
            assertEquals(BottomSheetDetent.FullyExpanded, sheets.authentication.targetDetent)
        } finally {
            composition.dispose()
            recomposer.close()
        }
    }

    @Test
    fun closingPatRetainsPatContentThroughoutExit() = runTest {
        val recomposer = Recomposer(coroutineContext)
        val composition = Composition(NoNodesApplier(), recomposer)
        lateinit var sheets: GitHubMcpSheetState
        try {
            composition.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    sheets = GitHubMcpSheetState(
                        rememberBottomSheetState(initialDetent = BottomSheetDetent.FullyExpanded),
                        rememberBottomSheetState(),
                    )
                }
            }
            sheets.openLogin(GitHubLogin.PAT)
            runCurrent()
            sheets.closeLogin()
            assertEquals("PAT must not switch to OAuth during the closing animation", GitHubLogin.PAT, sheets.login)
            runCurrent()
            assertEquals(GitHubLogin.PAT, sheets.login)
            sheets.openLogin(GitHubLogin.OAuth)
            assertEquals(GitHubLogin.OAuth, sheets.login)
        } finally {
            composition.dispose()
            recomposer.close()
        }
    }

    @Test
    fun authenticationErrorsNeverAppearInInstallation() = runTest {
        val recomposer = Recomposer(coroutineContext)
        val composition = Composition(NoNodesApplier(), recomposer)
        lateinit var sheets: GitHubMcpSheetState
        try {
            composition.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    sheets = GitHubMcpSheetState(
                        rememberBottomSheetState(initialDetent = BottomSheetDetent.FullyExpanded),
                        rememberBottomSheetState(),
                    )
                }
            }
            for (method in GitHubLogin.entries) {
                sheets.openLogin(method)
                runCurrent()
                sheets.loginError = "Authentication failed"
                assertEquals("$method errors must stay inside authentication", null, sheets.detailsError)
                sheets.closeLogin()
                assertEquals("Closing $method must not reveal its error in installation", null, sheets.detailsError)
                sheets.loginError = null
                sheets.detailsError = "Management failed"
                assertEquals("Management errors must not enter authentication", null, sheets.loginError)
                sheets.detailsError = null
            }
        } finally {
            composition.dispose()
            recomposer.close()
        }
    }

    private class NoNodesApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = error("No UI nodes expected")
        override fun insertBottomUp(index: Int, instance: Unit) = error("No UI nodes expected")
        override fun remove(index: Int, count: Int) = error("No UI nodes expected")
        override fun move(from: Int, to: Int, count: Int) = error("No UI nodes expected")
        override fun onClear() = Unit
    }
}
