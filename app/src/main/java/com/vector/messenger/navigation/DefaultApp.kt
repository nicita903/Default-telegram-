package com.vector.messenger.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.vector.messenger.design.ChatAvatar
import com.vector.messenger.design.VectorMotionSpec
import com.vector.messenger.feature.*
import com.vector.messenger.navigation.gesture.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DefaultApp(chats: ChatsViewModel, preferences: PreferencesViewModel, onAccount: () -> Unit = {}) {
    val navigation = rememberVectorNavigationController()
    // Keep the six controllers above the active content: switching modules does not reset stacks.
    val controllers = AppModule.entries.associateWith { key(it) { rememberNavController() } }
    val nav = controllers.getValue(navigation.module)
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val conversation = route == Routes.Conversation
    val allChats by chats.chats.collectAsStateWithLifecycle()
    val contact = allChats.find { it.id == entry?.arguments?.getLong("chatId") }
    val gesture = remember { VectorGestureController() }
    val exclusions = remember { GestureExclusions() }
    val gesturesEnabled by preferences.moduleGestures.collectAsStateWithLifecycle()
    val touchExploration = rememberTouchExplorationEnabled()
    var menu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val holder = rememberSaveableStateHolder()
    val gestureEnabled = gesturesEnabled && !touchExploration && !menu
    val gestureScope = "${navigation.module}:${route}"
    val scale = animateFloatAsState(if (gesture.activated) VectorMotionSpec.ActivatedScale else 1f, VectorMotionSpec.scale(), label = "Module gesture scale")
    fun previousModule() {
        if (!navigation.previous()) scope.launch { snackbar.showSnackbar("No previous module", duration = SnackbarDuration.Short) }
    }
    val release: (VectorGestureDirection) -> Unit = { direction ->
        if (direction == VectorGestureDirection.Right) menu = true else previousModule()
    }
    CompositionLocalProvider(LocalGestureExclusions provides exclusions) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Scaffold(
                modifier = Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value },
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets.safeDrawing,
                snackbarHost = { SnackbarHost(snackbar) },
                topBar = {
                    TopAppBar(
                        title = {
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).vectorModuleGestures(gesture, gestureEnabled, release, scopeKey = gestureScope).testTag("header_gesture_surface"), verticalAlignment = Alignment.CenterVertically) {
                                if (conversation && contact != null) {
                                    ChatAvatar(contact.title, 38.dp)
                                    Spacer(Modifier.width(12.dp))
                                }
                                Column {
                                    Text(if (conversation) contact?.title ?: "Conversation" else if (navigation.module == AppModule.Chats) "DEFAULT" else navigation.module.label, style = MaterialTheme.typography.titleLarge)
                                    Text(if (conversation) "Sample conversation" else "Demo chats · not synchronized", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        },
                        navigationIcon = {
                            if (nav.previousBackStackEntry != null) IconButton(onClick = { nav.popBackStack() }, modifier = Modifier.excludeModuleGestures()) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, if (conversation && navigation.module == AppModule.Contacts) "Back to contacts" else if (conversation) "Back to chats" else "Back within module")
                            } else IconButton(onClick = { menu = true }, modifier = Modifier.excludeModuleGestures()) { Icon(Icons.Default.Menu, "Open navigation") }
                        },
                        actions = {
                            if (nav.previousBackStackEntry != null) IconButton(onClick = { menu = true }, modifier = Modifier.excludeModuleGestures()) { Icon(Icons.Default.Menu, "Open navigation") }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    )
                },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                    .vectorModuleGestures(gesture, gestureEnabled, release, scopeKey = gestureScope).testTag("module_gesture_surface")) {
                    AnimatedContent(
                        targetState = navigation.module,
                        transitionSpec = {
                            val sign = if (navigation.backwards) -1 else 1
                            (fadeIn(VectorMotionSpec.transition()) + slideInHorizontally(VectorMotionSpec.transition()) { sign * it / 12 }) togetherWith
                                (fadeOut(VectorMotionSpec.transition()) + slideOutHorizontally(VectorMotionSpec.transition()) { -sign * it / 12 })
                        }, label = "Module transition",
                    ) { module ->
                        holder.SaveableStateProvider(module.name) {
                            VectorNavigationHost(module, controllers.getValue(module), chats, preferences, onPrivacy = { navigation.select(AppModule.Privacy) })
                        }
                    }
                }
            }
            // System Back pops an internal screen first, then visit history. At the first root Android exits.
            BackHandler(enabled = !menu && (nav.previousBackStackEntry != null || navigation.history.canGoBack)) {
                if (nav.previousBackStackEntry != null) nav.popBackStack() else previousModule()
            }
            VectorModuleMenu(menu, navigation.module, onDismiss = { menu = false }, onSelect = { navigation.select(it); menu = false }, onAccount = { menu = false; onAccount() })
            // Also cover a quick Back before the dialog window has acquired input focus.
            BackHandler(enabled = menu) { menu = false }
        }
    }
}

@Composable
private fun VectorNavigationHost(
    module: AppModule,
    nav: androidx.navigation.NavHostController,
    chats: ChatsViewModel,
    preferences: PreferencesViewModel,
    onPrivacy: () -> Unit,
) {
    val allChats by chats.chats.collectAsStateWithLifecycle()
    NavHost(navController = nav, startDestination = module.name,
        enterTransition = { fadeIn(VectorMotionSpec.transition()) }, exitTransition = { fadeOut(VectorMotionSpec.transition()) }) {
        when (module) {
            AppModule.Chats, AppModule.Contacts -> {
                composable(module.name) {
                    val open: (Long) -> Unit = { id -> chats.open(id); nav.navigate(Routes.conversation(id)) { launchSingleTop = true } }
                    if (module == AppModule.Chats) ChatsScreen(chats, onSelect = open) else ContactsScreen(allChats.filter { it.isContact }, open)
                }
                composable(Routes.Conversation, arguments = listOf(navArgument("chatId") { type = NavType.LongType })) { entry ->
                    ConversationScreen(entry.arguments!!.getLong("chatId"), chats)
                }
            }
            AppModule.Music -> {
                composable(module.name) { MusicScreen(onSection = { nav.navigate("library/$it") { launchSingleTop = true } }) }
                composable(Routes.MusicSection, arguments = listOf(navArgument("section") { type = NavType.StringType })) { entry ->
                    MusicSectionScreen(entry.arguments!!.getString("section") ?: "Tracks")
                }
            }
            AppModule.Downloads -> composable(module.name) { DownloadsScreen() }
            AppModule.Privacy -> composable(module.name) { PrivacyScreen(allChats.filter { it.isContact }, preferences) }
            AppModule.Settings -> composable(module.name) { SettingsScreen(preferences, onPrivacy) }
        }
    }
}
