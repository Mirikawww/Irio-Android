package com.irio

sealed class Screen {
    data object Chat : Screen()
    data object Settings : Screen()
    data object Link : Screen()
    data object Model : Screen()
    data object Speech : Screen()
    data object SystemPrompt : Screen()
    data object Appearance : Screen()
    data object Memory : Screen()
    data object Other : Screen()
    data object Providers : Screen()
    data class ProviderDetail(val id: String) : Screen()
}
