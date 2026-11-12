package com.vector.messenger.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.vector.messenger.R
import com.vector.messenger.feature.*
import kotlinx.coroutines.launch

object Routes {
    const val Chats = "Chats"
    const val Contacts = "Contacts"
    const val Music = "Music"
    const val Downloads = "Downloads"
    const val Privacy = "Privacy"
    const val Settings = "Settings"
    const val Conversation = "conversation/{chatId}"
    val main = listOf(Chats, Contacts, Music, Downloads, Privacy, Settings)
    fun conversation(id: Long): String { require(id > 0); return "conversation/$id" }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DefaultApp(chats: ChatsViewModel, preferences: PreferencesViewModel) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: Routes.Chats
    val conversation = route == Routes.Conversation
    val allChats by chats.chats.collectAsStateWithLifecycle()
    val id = entry?.arguments?.getLong("chatId")
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    fun navigate(destination: String) {
        nav.navigate(destination) {
            popUpTo(Routes.Chats) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    ModalNavigationDrawer(
        drawerState = drawer,
        // Visible menu is the Phase 1 alternative; global swipe interception is intentionally disabled.
        gesturesEnabled = false,
        drawerContent = {
            ModalDrawerSheet {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(24.dp))
                Routes.main.forEach { destination ->
                    NavigationDrawerItem(
                        label = { Text(destination) }, selected = route == destination,
                        onClick = { navigate(destination); scope.launch { drawer.close() } },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
        },
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    title = { Text(if (conversation) allChats.find { it.id == id }?.title ?: "Conversation" else if (route == Routes.Chats) stringResource(R.string.app_name) else route) },
                    navigationIcon = {
                        if (conversation) IconButton(onClick = { nav.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to chats")
                        } else IconButton(onClick = { scope.launch { drawer.open() } }) {
                            Icon(Icons.Default.Menu, "Open navigation")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                Text(
                    "Demo · no Telegram connection", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                )
                NavHost(navController = nav, startDestination = Routes.Chats, modifier = Modifier.weight(1f)) {
                    composable(Routes.Chats) {
                        ChatsScreen(chats) { chatId -> chats.open(chatId); nav.navigate(Routes.conversation(chatId)) }
                    }
                    composable(Routes.Conversation, arguments = listOf(navArgument("chatId") { type = NavType.LongType })) { backStack ->
                        ConversationScreen(backStack.arguments!!.getLong("chatId"), chats)
                    }
                    composable(Routes.Contacts) {
                        ContactsScreen(allChats.filter { it.isContact }) { chatId -> chats.open(chatId); nav.navigate(Routes.conversation(chatId)) }
                    }
                    composable(Routes.Music) { MusicScreen() }
                    composable(Routes.Downloads) { DownloadsScreen() }
                    composable(Routes.Privacy) { PrivacyScreen(allChats.filter { it.isContact }, preferences) }
                    composable(Routes.Settings) { SettingsScreen(preferences, onPrivacy = { navigate(Routes.Privacy) }) }
                }
            }
        }
    }
}
