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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.vector.messenger.design.DefaultHeader
import com.vector.messenger.design.VectorMotionSpec
import com.vector.messenger.feature.*
import com.vector.messenger.navigation.gesture.*
import kotlinx.coroutines.launch
import com.vector.messenger.feature.search.*
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DefaultApp(chats: ChatsViewModel, preferences: PreferencesViewModel, search: SearchViewModel, onAccount: () -> Unit = {}) {
    val navigation = rememberVectorNavigationController()
    // Keep the six controllers above the active content: switching modules does not reset stacks.
    val controllers = AppModule.entries.associateWith { key(it) { rememberNavController() } }
    val nav = controllers.getValue(navigation.module)
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val conversation = route == Routes.Conversation
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchScopeName by rememberSaveable { mutableStateOf(SearchScope.Chats.name) }
    val searchScope = SearchScope.valueOf(searchScopeName)
    val searchVisible = searchOpen && navigation.module == AppModule.Chats && !conversation
    val appearance by preferences.appearance.collectAsStateWithLifecycle()
    val travel = if (appearance.reducedMotion) 0f else appearance.intensity / 100f
    val chatQuery by search.chatQuery.collectAsStateWithLifecycle()
    val musicQuery by search.musicQuery.collectAsStateWithLifecycle()
    var blockedAttempt by remember { mutableIntStateOf(0) }
    val haptic = LocalHapticFeedback.current
    fun switchSearch(target: SearchScope) {
        if (target == searchScope) return
        if (searchLocked(if (searchScope == SearchScope.Chats) chatQuery else musicQuery, appearance.autoLock)) {
            blockedAttempt++
            if (appearance.haptics) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        } else { searchScopeName = target.name; if (appearance.haptics) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    }
    val gestureConfig = VectorGestureConfig(distance = appearance.distance.dp, holdMillis = appearance.hold.toLong())
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
    val scale = animateFloatAsState(if (gesture.activated && !appearance.reducedMotion) 1f - appearance.zoom / 100f * appearance.intensity / 100f else 1f, VectorMotionSpec.scale(), label = "Module gesture scale")
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
                    val internalBack = nav.previousBackStackEntry != null
                    DefaultHeader(
                        title = if (conversation) contact?.title ?: "Conversation" else if (navigation.module == AppModule.Chats) "Default" else navigation.module.label,
                        subtitle = if (conversation) "Sample conversation" else if (searchVisible) "Search workspace" else if (navigation.module == AppModule.Chats) "Demo chats · not synchronized" else "",
                        avatarName = if (conversation) contact?.title else null,
                        navigationIcon = if (internalBack || searchVisible) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Menu,
                        navigationDescription = if (searchVisible) "Close search" else if (!internalBack) "Open navigation" else if (conversation && navigation.module == AppModule.Contacts) "Back to contacts" else if (conversation) "Back to chats" else "Back within module",
                        onNavigation = { if (searchVisible) searchOpen = false else if (internalBack) nav.popBackStack() else menu = true },
                        onMenu = if (internalBack || searchVisible) ({ menu = true }) else null,
                        titleModifier = Modifier.vectorModuleGestures(gesture, gestureEnabled, release, config = gestureConfig, haptics = appearance.haptics, scopeKey = gestureScope).testTag("header_gesture_surface"),
                    )
                },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                    .vectorModuleGestures(gesture, gestureEnabled, release, config = gestureConfig, haptics = appearance.haptics,
                        fastSearch = searchVisible && appearance.fastSearch && !touchExploration && !menu,
                        fastSensitivity = appearance.sensitivity, onFastSwipe = { direction ->
                            if (direction == VectorGestureDirection.Right && searchScope == SearchScope.Chats) switchSearch(SearchScope.Music)
                            else if (direction == VectorGestureDirection.Left && searchScope == SearchScope.Music) switchSearch(SearchScope.Chats)
                        }, scopeKey = "$gestureScope:$searchVisible:$searchScope").testTag("module_gesture_surface")) {
                    AnimatedContent(
                        targetState = navigation.module,
                        transitionSpec = {
                            val sign = if (navigation.backwards) -1 else 1
                            (fadeIn(VectorMotionSpec.transition(appearance)) + slideInHorizontally(VectorMotionSpec.transition(appearance)) { (sign * it / 12 * travel).toInt() }) togetherWith
                                (fadeOut(VectorMotionSpec.transition(appearance)) + slideOutHorizontally(VectorMotionSpec.transition(appearance)) { (-sign * it / 12 * travel).toInt() })
                        }, label = "Module transition",
                    ) { module ->
                        holder.SaveableStateProvider(module.name) {
                            AnimatedContent(module == AppModule.Chats && searchVisible, transitionSpec = {
                                (fadeIn(VectorMotionSpec.transition(appearance)) + slideInVertically(VectorMotionSpec.transition(appearance)) { (it / 10 * travel).toInt() }) togetherWith
                                    fadeOut(VectorMotionSpec.transition(appearance))
                            }, label = "Pull search expansion") { searching ->
                            if (searching) {
                                AnimatedContent(searchScope, transitionSpec = {
                                    val sign = if (targetState == SearchScope.Music) 1 else -1
                                    (fadeIn(VectorMotionSpec.transition(appearance)) + slideInHorizontally(VectorMotionSpec.transition(appearance)) { (sign * it / 8 * travel).toInt() }) togetherWith
                                        (fadeOut(VectorMotionSpec.transition(appearance)) + slideOutHorizontally(VectorMotionSpec.transition(appearance)) { (-sign * it / 8 * travel).toInt() })
                                }, label = "Search scope") { selected ->
                                    holder.SaveableStateProvider("search:${selected.name}") { SearchWorkspace(search, selected, blockedAttempt, ::switchSearch) { id ->
                                        searchOpen = false; chats.open(id); controllers.getValue(AppModule.Chats).navigate(Routes.conversation(id)) { launchSingleTop = true }
                                    } }
                                }
                            } else VectorNavigationHost(module, controllers.getValue(module), chats, preferences, onPrivacy = { navigation.select(AppModule.Privacy) }, onSearch = { searchScopeName = SearchScope.Chats.name; searchOpen = true })
                            }
                        }
                    }
                }
            }
            // System Back pops an internal screen first, then visit history. At the first root Android exits.
            BackHandler(enabled = !menu && (searchVisible || nav.previousBackStackEntry != null || navigation.history.canGoBack)) {
                if (searchVisible) searchOpen = false else if (nav.previousBackStackEntry != null) nav.popBackStack() else previousModule()
            }
            VectorModuleMenu(menu, navigation.module, onDismiss = { menu = false }, onSelect = { navigation.select(it); menu = false }, onAccount = { menu = false; onAccount() }, onSearch = {
                navigation.select(AppModule.Chats); controllers.getValue(AppModule.Chats).popBackStack(AppModule.Chats.name, false)
                searchScopeName = SearchScope.Chats.name; searchOpen = true; menu = false
            })
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
    onSearch: () -> Unit,
) {
    val appearance = LocalAppearance.current
    val allChats by chats.chats.collectAsStateWithLifecycle()
    NavHost(navController = nav, startDestination = module.name,
        enterTransition = { fadeIn(VectorMotionSpec.transition(appearance)) }, exitTransition = { fadeOut(VectorMotionSpec.transition(appearance)) }) {
        when (module) {
            AppModule.Chats, AppModule.Contacts -> {
                composable(module.name) {
                    val open: (Long) -> Unit = { id -> chats.open(id); nav.navigate(Routes.conversation(id)) { launchSingleTop = true } }
                    if (module == AppModule.Chats) ChatsScreen(chats, onSelect = open, onSearch = onSearch) else ContactsScreen(allChats.filter { it.isContact }, open)
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
