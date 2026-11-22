package com.vector.messenger.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
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
import com.vector.messenger.design.DefaultAction
import com.vector.messenger.design.DefaultDesign
import com.vector.messenger.design.DefaultDialog
import com.vector.messenger.design.DefaultHeader
import com.vector.messenger.design.DefaultIconControl
import com.vector.messenger.design.VectorMotionSpec
import com.vector.messenger.design.backdropBlur
import com.vector.messenger.feature.*
import com.vector.messenger.navigation.gesture.*
import kotlinx.coroutines.launch
import com.vector.messenger.feature.search.*
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DefaultApp(chats: ChatsViewModel, preferences: PreferencesViewModel, search: SearchViewModel, onAccount: () -> Unit = {}) {
    val navigation = rememberVectorNavigationController()
    // Keep the six controllers above the active content: switching modules does not reset stacks.
    val controllers = AppModule.entries.associateWith { key(it) { rememberNavController() } }
    // Search opened from another module can reveal Chats without recording a module visit.
    // The first subsequent module Back returns to the unchanged visit-history head.
    var transientChats by rememberSaveable { mutableStateOf(false) }
    val displayedModule = if (transientChats) AppModule.Chats else navigation.module
    val nav = controllers.getValue(displayedModule)
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val conversation = route == Routes.Conversation
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchScopeName by rememberSaveable { mutableStateOf(SearchScope.Chats.name) }
    val searchScope = SearchScope.valueOf(searchScopeName)
    val searchVisible = searchOpen && displayedModule == AppModule.Chats && !conversation
    val searchVisibility = remember { MutableTransitionState(false) }.apply { targetState = searchVisible }
    val searchOwnsInput = searchVisible || searchVisibility.currentState || searchVisibility.targetState
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val view = LocalView.current
    fun closeSearch() { keyboard?.hide(); focusManager.clearFocus(); searchOpen = false }
    val appearance = LocalAppearance.current
    val chatQuery by search.chatQuery.collectAsStateWithLifecycle()
    val musicQuery by search.musicQuery.collectAsStateWithLifecycle()
    var blockedAttempt by remember { mutableIntStateOf(0) }
    val haptic = LocalHapticFeedback.current
    fun switchSearch(target: SearchScope) {
        if (target == searchScope) return
        if (searchLocked(if (searchScope == SearchScope.Chats) chatQuery else musicQuery, appearance.autoLock)) {
            blockedAttempt++
            if (appearance.haptics && appearance.elastic) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        } else { searchScopeName = target.name; if (appearance.haptics) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    }
    val gestureConfig = VectorGestureConfig(distance = appearance.distance.dp, holdMillis = appearance.hold.toLong())
    val allChats by chats.chats.collectAsStateWithLifecycle()
    val chatStatus by chats.status.collectAsStateWithLifecycle()
    val contact = allChats.find { it.id == entry?.arguments?.getLong("chatId") }
    val gesture = remember { VectorGestureController() }
    val exclusions = remember { GestureExclusions() }
    val touchExploration = rememberTouchExplorationEnabled()
    val floatingInterface = com.vector.messenger.design.floatingInterfaceOpen()
    var menu by remember { mutableStateOf(false) }
    // One dialog for the whole app: the header's New chat control and the chats list both just
    // flip this, so there is no second copy of the contact picker to keep in sync.
    var newMessage by remember { mutableStateOf(false) }
    fun startConversation(id: Long) {
        newMessage = false
        chats.open(id)
        controllers.getValue(AppModule.Chats).navigate(Routes.conversation(id)) { launchSingleTop = true }
    }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val holder = rememberSaveableStateHolder()
    val modulePolicy: () -> GestureCancelReason = {
        moduleGestureEligibility(
            enabled = preferences.appearance.value.modules,
            searchActive = searchOwnsInput || (searchOpen && (transientChats || navigation.module == AppModule.Chats) &&
                controllers.getValue(AppModule.Chats).currentBackStackEntry?.destination?.route != Routes.Conversation),
            modalOpen = menu || newMessage || floatingInterface,
            touchExploration = touchExploration,
        )
    }
    val gestureScope = "$displayedModule:${entry?.id}:$route:$searchOwnsInput:${appearance.modules}:$touchExploration:$menu:$newMessage:$floatingInterface"
    val scale = animateFloatAsState(VectorMotionSpec.surfaceScale(gesture.activated, appearance), VectorMotionSpec.scale(), label = "Module gesture scale")
    // Aurora: the surface leans 10 dp toward the swipe while the hold charges, so the
    // gesture reads as a pull before it changes module. Feedback only; no detection changes.
    val motionStrength = VectorMotionSpec.strength(appearance)
    val holding = gesture.state == VectorGestureState.Holding || gesture.activated
    val nudge by animateFloatAsState(if (holding) 1f else 0f, VectorMotionSpec.scale(), label = "Module gesture nudge")
    val nudgeDirection = if (gesture.direction == VectorGestureDirection.Left) -1f else 1f
    fun previousModule() {
        if (transientChats) transientChats = false
        else if (!navigation.previous()) scope.launch { snackbar.showSnackbar("No previous module", duration = SnackbarDuration.Short) }
    }
    val release: (VectorGestureDirection) -> Unit = { direction ->
        // Read live policy too: a state change can precede the next composition/input restart.
        if (modulePolicy() == GestureCancelReason.None) {
            if (direction == VectorGestureDirection.Right) menu = true else previousModule()
        }
    }
    com.vector.messenger.design.MusicPalette(enabled = displayedModule == AppModule.Music) {
    CompositionLocalProvider(LocalGestureExclusions provides exclusions) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .vectorModuleGestures(gesture, modulePolicy, release, config = gestureConfig, haptics = appearance.haptics,
                enabled = !searchOwnsInput, scopeKey = gestureScope, contentEnabled = !conversation)) {
            Scaffold(
                modifier = Modifier
                    // The menu is in-window, so its backdrop is this very content: blur it while
                    // the menu is open. Compose cannot blur what sits behind a composable.
                    .backdropBlur(menu)
                    .graphicsLayer {
                        scaleX = scale.value; scaleY = scale.value
                        translationX = nudgeDirection * nudge * 10.dp.toPx() * motionStrength
                    },
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets.safeDrawing,
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = { if (!searchOwnsInput) com.vector.messenger.feature.audio.FloatingAudioPlayer { chatId ->
                    closeSearch(); menu = false
                    if (navigation.module != AppModule.Chats) transientChats = true
                    chats.open(chatId)
                    controllers.getValue(AppModule.Chats).navigate(Routes.conversation(chatId)) { launchSingleTop = true }
                } },
                topBar = { if (!searchOwnsInput) {
                    val internalBack = nav.previousBackStackEntry != null
                    DefaultHeader(
                        title = if (conversation) contact?.title ?: "Conversation" else if (displayedModule == AppModule.Chats) "DEFAULT" else displayedModule.label,
                        subtitle = if (conversation) { if (chatStatus.live) "Telegram" else "Sample conversation" }
                            else if (searchVisible) "Search workspace" else if (displayedModule == AppModule.Chats) {
                                if (!chatStatus.live) "Demo chats · not synchronized" else when (chatStatus.connection) {
                                    com.vector.core.profile.ProfileConnection.Ready -> "Telegram"
                                    com.vector.core.profile.ProfileConnection.WaitingForNetwork -> "Waiting for network"
                                    else -> "Connecting to Telegram…"
                                }
                            } else "",
                        avatarName = if (conversation) contact?.title else null,
                        avatarPhoto = if (conversation) contact?.photo else null,
                        // Root controls stay on the edges; nested screens retain their Back action.
                        leading = {
                            if (internalBack || searchVisible) {
                                DefaultIconControl(Icons.AutoMirrored.Filled.ArrowBack,
                                    if (searchVisible) "Close search" else if (conversation && displayedModule == AppModule.Contacts) "Back to contacts" else if (conversation) "Back to chats" else "Back within module",
                                    { if (searchVisible) closeSearch() else nav.popBackStack() },
                                    Modifier.excludeModuleGestures(), quiet = true)
                            } else {
                                DefaultIconControl(Icons.Default.Menu, "Open navigation", { menu = true },
                                    Modifier.excludeModuleGestures(), quiet = true)
                            }
                        },
                        trailing = {
                            if (conversation && contact != null) com.vector.messenger.feature.ConversationOptions(contact, chats)
                            else if (internalBack && !conversation) DefaultIconControl(Icons.Default.Menu, "Open navigation", { menu = true },
                                Modifier.excludeModuleGestures(), quiet = true)
                            else if (!conversation && displayedModule == AppModule.Chats) DefaultIconControl(Icons.Default.Add, "New chat", { newMessage = true },
                                Modifier.excludeModuleGestures(), quiet = true)
                        },
                        titleModifier = Modifier.moduleGestureSurface(gesture, ModuleGestureSurface.Header).testTag("header_gesture_surface"),
                    )
                } },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                    .moduleGestureSurface(gesture, ModuleGestureSurface.Content).testTag("module_gesture_surface")) {
                    AnimatedContent(
                        targetState = displayedModule,
                        transitionSpec = {
                            VectorMotionSpec.moduleTransition(navigation.backwards, appearance).using(null)
                        }, label = "Module transition",
                    ) { module ->
                        holder.SaveableStateProvider(module.name) {
                            Box(Modifier.fillMaxSize()) {
                                // Keep the same Chats entry/list alive underneath Search.
                                Box(Modifier.fillMaxSize().then(if (module == AppModule.Chats && searchOwnsInput) Modifier.clearAndSetSemantics {} else Modifier)) {
                                    VectorNavigationHost(module, controllers.getValue(module), chats, preferences,
                                        onPrivacy = { transientChats = false; navigation.select(AppModule.Privacy) }, onSearch = { searchScopeName = SearchScope.Chats.name; searchOpen = true })
                                }
                                if (module == AppModule.Chats) SearchOverlay(searchVisibility, searchVisible && !menu && !floatingInterface && !touchExploration,
                                    search, searchScope, blockedAttempt, ::switchSearch, ::closeSearch) { id ->
                                    closeSearch(); chats.open(id); controllers.getValue(AppModule.Chats).navigate(Routes.conversation(id)) { launchSingleTop = true }
                                }
                            }
                        }
                    }
                }
            }
            // Feedback overlay for the same gesture: it consumes no pointer input and sets no semantics.
            AuroraGestureCue(gesture.state, gesture.direction, appearance.hold)
            // System Back pops an internal screen first, then visit history. At the first root Android exits.
            BackHandler(enabled = !menu && (searchVisible || transientChats || nav.previousBackStackEntry != null || navigation.history.canGoBack)) {
                if (searchVisible) {
                    // Query the current platform state in the callback, not a Boolean captured
                    // before the IME's asynchronous hide animation/insets delivery.
                    if (ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true) {
                        keyboard?.hide(); focusManager.clearFocus()
                    } else closeSearch()
                } else if (nav.previousBackStackEntry != null) nav.popBackStack() else previousModule()
            }
            VectorModuleMenu(menu, displayedModule, onDismiss = { menu = false }, onSelect = { transientChats = false; navigation.select(it); menu = false }, onAccount = { menu = false; onAccount() }, onSearch = {
                if (displayedModule != AppModule.Chats) transientChats = true
                controllers.getValue(AppModule.Chats).popBackStack(AppModule.Chats.name, false)
                searchScopeName = SearchScope.Chats.name; searchOpen = true; menu = false
            })
            // Also cover a quick Back before the dialog window has acquired input focus.
            BackHandler(enabled = menu) { menu = false }
            if (newMessage) DefaultDialog("New message", onDismiss = { newMessage = false }) {
                Text(if (chatStatus.live) "Choose a Telegram conversation" else "Choose a sample contact",
                    color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.bodyMedium)
                Box(Modifier.weight(1f, fill = false)) {
                    ContactsScreen(allChats.filter { it.isContact }) { startConversation(it) }
                }
                DefaultAction("Cancel", { newMessage = false }, Modifier.fillMaxWidth())
            }
        }
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
        enterTransition = { VectorMotionSpec.screenEnter(appearance) }, exitTransition = { VectorMotionSpec.screenExit(appearance) },
        popEnterTransition = { VectorMotionSpec.screenEnter(appearance, backwards = true) },
        popExitTransition = { VectorMotionSpec.screenExit(appearance, backwards = true) }) {
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
