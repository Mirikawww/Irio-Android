package com.irio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.composables.ui.components.BottomSheetDetent
import com.composables.ui.components.BottomSheetState

internal enum class GitHubLogin { PAT, OAuth, TODOIST }

/** Keeps the parent installation and its selected authentication sheet together. */
internal class GitHubMcpSheetState(
    val details: BottomSheetState,
    val authentication: BottomSheetState,
) {
    var login by mutableStateOf<GitHubLogin?>(null)
        private set

    private var loginErrorValue by mutableStateOf<String?>(null)
    var loginErrorNonce by mutableIntStateOf(0)
        private set
    var loginError: String?
        get() = loginErrorValue
        set(value) {
            loginErrorValue = value
            if (value != null) loginErrorNonce++
        }
    var detailsError by mutableStateOf<String?>(null)

    fun openLogin(method: GitHubLogin) {
        login = method
        authentication.targetDetent = BottomSheetDetent.FullyExpanded
    }

    suspend fun closeLogin() {
        // Keep the selected body stable while the modal animates out.
        authentication.hide()
    }
}
