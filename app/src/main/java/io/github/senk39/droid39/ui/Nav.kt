package io.github.senk39.droid39.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

sealed interface Route {
    data object Home : Route
    data class Node(val id: Int) : Route
    /** Kilka kategorii naraz (np. „Pozostałe” na półce Serie). */
    data class Multi(val label: String, val paths: List<String>) : Route
    data object Playlists : Route
    data class Playlist(val rel: String, val title: String) : Route
    /** unl, best, liked, recent, played, all, offline */
    data class Special(val kind: String) : Route
    data object Tags : Route
    data class Tag(val id: Long) : Route
    data object Search : Route
    data object Settings : Route
    /** Jedna sekcja ustawień: nas, sync, storage, card, offline, ui, playback, scrobbler. */
    data class SettingsSection(val id: String) : Route
    data object Stars : Route
    data object Commits : Route
    data object Queue : Route
    /** Folder biblioteki (ścieżka względna z „/”; „” – folder główny muzyki). */
    data class Folder(val path: String) : Route
}

/** Prosta nawigacja (stos ekranów) – stan na poziomie procesu. */
object Nav {
    val stack = mutableStateListOf<Route>(Route.Home)
    var playerOpen by mutableStateOf(false)

    val current: Route get() = stack.last()

    fun go(r: Route) {
        playerOpen = false
        if (stack.lastOrNull() != r) stack.add(r)
    }

    fun back(): Boolean {
        if (playerOpen) {
            playerOpen = false
            return true
        }
        if (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
            return true
        }
        return false
    }

    fun home() {
        playerOpen = false
        stack.clear()
        stack.add(Route.Home)
    }
}
