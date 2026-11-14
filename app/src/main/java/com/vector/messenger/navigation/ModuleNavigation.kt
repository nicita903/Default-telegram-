package com.vector.messenger.navigation

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable

enum class AppModule(val label: String) {
    Chats("Chats"), Contacts("Contacts"), Music("Music"), Downloads("Downloads"),
    Privacy("Ghost Mode"), Settings("Settings"),
}

/** Visit history is independent of Navigation Compose's per-module screen back stacks. */
data class VectorModuleHistory(val visits: List<AppModule> = listOf(AppModule.Chats)) {
    init { require(visits.isNotEmpty()) }
    val current get() = visits.last()
    val canGoBack get() = visits.size > 1
    fun visit(module: AppModule): VectorModuleHistory =
        if (module == current) this else VectorModuleHistory((visits + module).takeLast(32))
    fun previous(): VectorModuleHistory = if (canGoBack) copy(visits = visits.dropLast(1)) else this
}

@Stable
class VectorNavigationController(initial: VectorModuleHistory) {
    var history by mutableStateOf(initial)
        private set
    var backwards by mutableStateOf(false)
        private set
    val module get() = history.current
    fun select(module: AppModule) { backwards = false; history = history.visit(module) }
    fun previous(): Boolean {
        if (!history.canGoBack) return false
        backwards = true
        history = history.previous() // Never re-add the departing module on back.
        return true
    }
}

@Composable
fun rememberVectorNavigationController(): VectorNavigationController = rememberSaveable(
    saver = listSaver(
        save = { it.history.visits.map(AppModule::name) },
        restore = { VectorNavigationController(VectorModuleHistory(it.map(AppModule::valueOf))) },
    ),
) { VectorNavigationController(VectorModuleHistory()) }

object Routes {
    const val Chats = "Chats"
    const val Contacts = "Contacts"
    const val Music = "Music"
    const val Downloads = "Downloads"
    const val Privacy = "Privacy"
    const val Settings = "Settings"
    const val Conversation = "conversation/{chatId}"
    const val MusicSection = "library/{section}"
    val main = AppModule.entries.map { it.name }
    fun conversation(id: Long): String { require(id != 0L); return "conversation/$id" }
}
