package com.vector.messenger.feature

import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.*
import com.vector.core.music.MusicRepeat
import com.vector.messenger.calling.*
import com.vector.messenger.design.*
import com.vector.messenger.feature.avatar.LocalAccountAction
import com.vector.messenger.feature.music.LocalMusicViewModel
import com.vector.messenger.navigation.gesture.excludeModuleGestures
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*

object SettingsRoutes {
    const val Search = "settings/search"
    const val Category = "settings/category/{section}?focus={focus}"
    fun category(section: String, focus: String = "") = "settings/category/$section?focus=$focus"
    fun title(section: String) = when (section) {
        "appearance" -> "Appearance"; "motion" -> "Motion & Gestures"; "messaging" -> "Chats"
        "sounds" -> "Sounds"; "notifications" -> "Alerts"; "privacy" -> "Privacy"
        "calling" -> "Calls"; "music" -> "Music"; "storage" -> "Storage"; "about" -> "About Default"
        "devices" -> "Devices"
        "tg_notifications" -> "Alerts"
        else -> telegramSettingLinks.firstOrNull { it.route == section }?.title ?: "Settings"
    }
}
private data class SettingEntry(val id: String, val title: String, val section: String, val icon: ImageVector,
    val keywords: String = "", val value: String = "", val checked: Boolean? = null, val options: List<String> = emptyList(),
    val selected: Int = -1, val choose: ((Int) -> Unit)? = null, val action: (() -> Unit)? = null,
    val destination: String? = null, val home: String? = null, val searchOnly: Boolean = false, val info: String? = null, val indexed: Boolean = true)

private val featuredSettings = listOf("appearance" to "Appearance", "privacy" to "Privacy", "sounds" to "Sounds", "notifications" to "Alerts")
private val secondarySettings = listOf("messaging" to "Chats", "calling" to "Calls", "devices" to "Devices", "storage" to "Storage", "music" to "Music")
private fun settingGroup(entry: SettingEntry) = when (entry.section) {
    "appearance" -> when (entry.id) { "interface" -> "Interface"; "accent" -> "Accent"; "typography" -> "Text"; "about" -> "Application"; "motion_link" -> "Interaction"; else -> "Display" }
    "motion" -> when (entry.id) { "speed", "intensity", "reduced" -> "Motion"; "blur" -> "Glass effects"; "haptic", "interface_sound" -> "Feedback"; else -> "Gestures" }
    "messaging" -> "Media"
    "sounds" -> when (entry.id) { "ringtone", "ring_volume", "call_effects" -> "Calls"; "haptics_link" -> "Feedback"; else -> "In app" }
    "calling" -> when (entry.id) { "verification" -> "Security"; "call_proximity" -> "Audio"; "call_sounds_link" -> "Sounds"; else -> "Incoming calls" }
    "notifications" -> "Android notification channels"
    "music" -> "Playback"
    "storage" -> "Management"
    else -> "Preferences"
}

@Composable
private fun SettingsControl(entry: SettingEntry, open: (SettingEntry) -> Unit) {
    when {
        entry.id == "accent" -> SettingsColorSelector(entry.selected) { entry.choose?.invoke(it) }
        entry.id in listOf("interface", "density", "repeat") -> SettingsSegments(if (entry.id == "interface") "" else entry.title, entry.options, entry.selected) { entry.choose?.invoke(it) }
        entry.options.size > 1 -> SettingsValueSlider(entry.title, entry.value, entry.selected, entry.options.size) { entry.choose?.invoke(it) }
        else -> CompactSettingsRow(entry.title, entry.value, entry.icon, checked = entry.checked,
            leading = if (entry.checked != null) ({}) else null) { open(entry) }
    }
}

/** One catalog supplies home shortcuts, categories, summaries and search. Storage/handlers stay unchanged. */
@Composable
fun SettingsScreen(viewModel: PreferencesViewModel, onPrivacy: () -> Unit, onDownloads: () -> Unit,
    onNavigate: (String) -> Unit, onProfile: () -> Unit, page: String = "home", focus: String = "") {
    val currentPage = page
    val appearance by viewModel.appearance.collectAsStateWithLifecycle()
    val sounds by viewModel.sounds.collectAsStateWithLifecycle()
    val notifications by viewModel.notifications.collectAsStateWithLifecycle()
    val ghost by viewModel.ghostState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val calls = LocalVoiceCalls.current
    val callSettingsFlow = remember(calls) { calls?.settings?.state ?: MutableStateFlow(CallSettings()) }
    val callSettings by callSettingsFlow.collectAsStateWithLifecycle()
    val proximitySupported = remember(context) { CallProximityController.supported(context) }
    val music = LocalMusicViewModel.current
    val musicFlow = remember(music) { music?.repository?.settings?.map { Triple(it.queue.shuffle, it.queue.repeat, it.queue.speed) }?.distinctUntilChanged()
        ?: flowOf(Triple(false, MusicRepeat.Off, 1f)) }
    val initialMusic = music?.repository?.settings?.value?.queue
    val musicOptions by musicFlow.collectAsStateWithLifecycle(Triple(initialMusic?.shuffle ?: false, initialMusic?.repeat ?: MusicRepeat.Off, initialMusic?.speed ?: 1f))
    val video = com.vector.messenger.feature.video.LocalVideoNotes.current
    val autoplay by (video?.autoplay ?: remember { MutableStateFlow(false) }).collectAsStateWithLifecycle()
    var permitted by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    var ringtone by remember { mutableStateOf("Android channel") }
    val system = remember(context) { context.getSystemService(NotificationManager::class.java) }
    var fullScreen by remember { mutableStateOf(Build.VERSION.SDK_INT < 34 || system.canUseFullScreenIntent()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        permitted = NotificationManagerCompat.from(context).areNotificationsEnabled()
        fullScreen = Build.VERSION.SDK_INT < 34 || system.canUseFullScreenIntent()
        ringtone = system.getNotificationChannel("telegram_incoming_calls_v1")?.let {
            when (it.sound) { null -> "Silent"; android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE) -> "System default"; else -> "Custom" }
        } ?: "Not configured"
    }
    var notice by remember { mutableStateOf<String?>(null) }
    fun openAndroid(intent: Intent) {
        try { context.startActivity(intent) } catch (_: Exception) { notice = "Android could not open these settings." }
    }
    fun channel(id: String) = openAndroid(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, id))
    val entries = remember(appearance, sounds, notifications, callSettings, proximitySupported, calls, ghost.authenticated, musicOptions, autoplay, permitted, ringtone, fullScreen, music, video) {
        buildList {
            telegramSettingLinks.forEach { link -> add(SettingEntry(link.route, link.title,
                when { link.route == "devices" -> "devices"; link.route == "tg_downloads" -> "messaging"; link.group == "Notifications" -> "notifications"; link.group == "Storage" -> "storage"; else -> "privacy" },
                SettingsIcons.route(link.route), link.words, destination = link.route, searchOnly = true)) }
            fun nav(id: String, title: String, section: String, icon: ImageVector, value: String = "", home: String? = null, keywords: String = "") {
                add(SettingEntry(id, title, section, icon, keywords, value, destination = section, home = home))
            }
            fun toggle(id: String, title: String, section: String, value: Boolean, keywords: String = "", home: String? = null, change: (Boolean) -> Unit) {
                add(SettingEntry(id, title, section, SettingsIcons.route(section),
                    keywords, checked = value, action = { change(!value) }, home = home))
            }
            fun choice(id: String, title: String, section: String, value: Int, options: List<Int>, unit: String = "", names: List<String>? = null,
                home: String? = null, keywords: String = "", change: (Int) -> Unit) {
                val values = (options + value).distinct().sorted()
                fun label(v: Int) = names?.getOrNull(options.indexOf(v)) ?: "$v$unit"
                add(SettingEntry(id, title, section, if (section == "sounds") VectorIcons.Music else VectorIcons.Clock, keywords,
                    label(value), options = values.map(::label), selected = values.indexOf(value), choose = { change(values[it]) }, home = home))
            }
            choice("interface", "Interface", "appearance", appearance.interfaceStyle.ordinal, listOf(0,1), names = listOf("Console", "Aurora Glass"), keywords = "theme console glass design") {
                viewModel.appearance { p -> p.copy(interfaceStyle = InterfaceStyle.entries[it]) }
            }
            toggle("amoled", "AMOLED mode", "appearance", appearance.amoledBlack, "black dark background oled", "Appearance") { viewModel.appearance { p -> p.copy(amoledBlack = it) } }
            choice("accent", "Accent color", "appearance", appearance.accent, listOf(0,1,2), names = listOf("Aurora blue", "Violet", "Teal"), home = "Appearance", keywords = "electric blue colour") { viewModel.appearance { p -> p.copy(accent = it) } }
            choice("blur", "Blur Intensity", "motion", appearance.glassBlur, listOf(0,1,2,3), names = listOf("Off", "Low", "Medium", "High"), home = "Appearance", keywords = "glass diffusion transparency") { viewModel.appearance { p -> p.copy(glassBlur = it) } }
            choice("typography", "Text size", "appearance", appearance.typeScale, listOf(85,100,115,130), "%", home = "Appearance", keywords = "typography scale font text size accessibility") { viewModel.appearance { p -> p.copy(typeScale = it) } }
            choice("density", "Interface density", "appearance", if (appearance.compact) 0 else 1, listOf(0,1), names = listOf("Compact", "Comfortable"), keywords = "spacing layout") { viewModel.appearance { p -> p.copy(compact = it == 0) } }
            choice("speed", "Animation speed", "motion", appearance.speed, listOf(50,75,100,125,150), "%") { viewModel.appearance { p -> p.copy(speed = it) } }
            choice("intensity", "Animation intensity", "motion", appearance.intensity, listOf(0,25,50,75,100), "%") { viewModel.appearance { p -> p.copy(intensity = it) } }
            toggle("reduced", "Reduced motion", "motion", appearance.reducedMotion, "accessibility disable animations") { viewModel.appearance { p -> p.copy(reducedMotion = it) } }
            toggle("modules", "Module gestures", "motion", appearance.modules, "swipe hold navigation menu") { viewModel.moduleGestures(it) }
            choice("distance", "Swipe activation distance", "motion", appearance.distance, listOf(20,28,36,48,60), " dp") { viewModel.appearance { p -> p.copy(distance = it) } }
            choice("hold", "Hold duration", "motion", appearance.hold, listOf(120,180,240,320,400), " ms") { viewModel.appearance { p -> p.copy(hold = it) } }
            toggle("haptic", "Haptic feedback", "motion", appearance.haptics, "vibration touch") { viewModel.appearance { p -> p.copy(haptics = it) } }
            choice("zoom", "Navigation zoom", "motion", appearance.zoom, listOf(0,3,5,8,10), "%") { viewModel.appearance { p -> p.copy(zoom = it) } }
            toggle("pull", "Pull-down search", "motion", appearance.pullSearch, "open search") { viewModel.appearance { p -> p.copy(pullSearch = it) } }
            choice("pull_distance", "Pull-down threshold", "motion", appearance.pullDistance, listOf(48,60,72,96,120), " dp") { viewModel.appearance { p -> p.copy(pullDistance = it) } }
            toggle("dismiss", "Swipe-down dismissal", "motion", appearance.dismissSearch, "close search") { viewModel.appearance { p -> p.copy(dismissSearch = it) } }
            toggle("fast", "Fast Chat/Music switching", "motion", appearance.fastSearch, "horizontal search swipe") { viewModel.appearance { p -> p.copy(fastSearch = it) } }
            choice("sensitivity", "Horizontal swipe sensitivity", "motion", appearance.sensitivity, listOf(70,85,100,120,140), "%", keywords = "search switching") { viewModel.appearance { p -> p.copy(sensitivity = it) } }
            toggle("auto_lock", "Automatic search locking", "motion", appearance.autoLock, "query text") { viewModel.appearance { p -> p.copy(autoLock = it) } }
            toggle("lock_visible", "Lock indicator visibility", "motion", appearance.lockVisible) { viewModel.appearance { p -> p.copy(lockVisible = it) } }
            toggle("lock_feedback", "Lock feedback", "motion", appearance.elastic, "resistance elastic") { viewModel.appearance { p -> p.copy(elastic = it) } }
            if (video != null) toggle("video_autoplay", "Autoplay cached video circles", "messaging", autoplay, "video notes") { video.autoplay(it) }
            toggle("message_sounds", "Message sounds", "sounds", sounds.messages) { viewModel.soundPreference { p -> p.copy(messages = it) } }
            toggle("send_sound", "Sent messages", "sounds", sounds.send, "send sound messaging") { viewModel.soundPreference { p -> p.copy(send = it) } }
            toggle("receive_sound", "Received messages", "sounds", sounds.receive, "receive sound messaging") { viewModel.soundPreference { p -> p.copy(receive = it) } }
            toggle("error_sound", "Failed messages", "sounds", sounds.errors, "failed message sound errors messaging") { viewModel.soundPreference { p -> p.copy(errors = it) } }
            toggle("interface_sound", "Interface sounds", "sounds", sounds.interfaceSounds) { viewModel.soundPreference { p -> p.copy(interfaceSounds = it) } }
            choice("volume", "Volume", "sounds", sounds.volume, (0..100 step 5).toList(), "%", keywords = "sound volume messaging") { viewModel.soundPreference { p -> p.copy(volume = it) } }
            listOf("notification_enabled" to "Message notifications", "notification_previews" to "Message previews", "notification_groups" to "Group alerts", "notification_access" to "Android notification settings").forEach { (id,title) ->
                val aliases = when (id) {
                    "notification_enabled" -> "enable notifications delivery permissions"
                    "notification_previews" -> "hide message content and identity preview privacy"
                    "notification_groups" -> "group notifications"
                    else -> "android system permissions sound vibration"
                }
                add(SettingEntry(id,title,"notifications",SettingsIcons.Bell, "notifications alerts $aliases", searchOnly = true))
            }
            listOf("Messages" to "messages_v1", "Groups" to "groups_v1", "Mentions" to "mentions_v1").forEach { (name,id) ->
                add(SettingEntry(id,"$name notification sound","notifications",VectorIcons.Music,"ringtone vibration android channel", "Android", action = { channel(id) }))
            }
            add(SettingEntry("verification", "Encryption verification", "calling", VectorIcons.Check, "four emoji security key fingerprint", info =
                "During an active Telegram voice call, compare the four emojis below the call duration with the other participant, in the same order. Tap the emojis for details. Opening that explanation does not automatically verify the participant's identity."))
            if (calls != null) {
                toggle("call_effects", "Call sounds", "sounds", callSettings.effects, "call sound effects ringback connecting busy disconnect") {
                    calls.settings.update { p -> p.copy(effects = it) }
                }
                if (proximitySupported) toggle("call_proximity", "Proximity during earpiece calls", "calling", callSettings.proximity, "sensor screen ear display") {
                    calls.settings.update { p -> p.copy(proximity = it) }
                }
            }
            add(SettingEntry("ring_volume", "Ringtone volume", "sounds", SettingsIcons.Speaker, "system ring volume silent do not disturb",
                "Android", action = { openAndroid(Intent(Settings.ACTION_SOUND_SETTINGS)) }))
            add(SettingEntry("ringtone","Ringtone","sounds",CallIcons.Phone,"incoming call ringtone voice ring sound",ringtone,action = { channel("telegram_incoming_calls_v1") }))
            if (Build.VERSION.SDK_INT >= 34) add(SettingEntry("full_screen","Lock-screen calls","calling",SettingsIcons.Lock,"lock-screen call display full screen incoming notifications", if (fullScreen) "Allowed" else "Restricted",action = {
                openAndroid(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,Uri.parse("package:${context.packageName}"))) }))
            if (music != null) {
                toggle("shuffle","Shuffle","music",musicOptions.first) { music.player.shuffle() }
                val repeats = MusicRepeat.entries
                add(SettingEntry("repeat","Repeat","music",VectorIcons.Refresh,"loop playback",musicOptions.second.name,
                    options = repeats.map { it.name }, selected = repeats.indexOf(musicOptions.second), choose = { index ->
                        repeat((index-repeats.indexOf(musicOptions.second)+repeats.size)%repeats.size) { music.player.repeat() }
                    }))
                val speeds = listOf(.75f,1f,1.25f,1.5f,2f).plus(musicOptions.third).distinct().sorted()
                add(SettingEntry("music_speed","Playback speed","music",VectorIcons.Clock,"tempo", "${musicOptions.third}×",
                    options = speeds.map { "$it×" }, selected = speeds.indexOf(musicOptions.third), choose = { music.player.speed(speeds[it]) }))
            }
            add(SettingEntry("downloads","Downloads","storage",VectorIcons.Download,"files media storage",action = onDownloads, home = "Storage"))
            add(SettingEntry("motion_link", "Motion & Gestures", "appearance", SettingsIcons.Controls, "interface animation blur haptic feedback", destination = "motion"))
            add(SettingEntry("haptics_link", "Haptics", "sounds", SettingsIcons.Controls, action = { onNavigate(SettingsRoutes.category("motion", "haptic")) }, indexed = false))
            add(SettingEntry("call_sounds_link", "Ringtone & call sounds", "calling", SettingsIcons.Speaker, action = { onNavigate(SettingsRoutes.category("sounds", "ringtone")) }, indexed = false))
            add(SettingEntry("about","About Default","appearance",Icons.Default.Info,"version account encryption",destination = "about"))
            listOf("cache" to "App disk cache", "telegram_media" to "Telegram media", "database" to "Telegram database").forEach { (id,title) ->
                if (id == "cache" || ghost.authenticated) add(SettingEntry(id,title,"storage",VectorIcons.Download,"storage usage size bytes",searchOnly = true))
            }
        }
    }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    fun open(entry: SettingEntry) {
        when {
            entry.options.isNotEmpty() || entry.info != null -> selected = entry.id
            entry.checked != null || entry.action != null -> entry.action?.invoke()
            else -> onNavigate(SettingsRoutes.category(entry.destination ?: entry.section, entry.id))
        }
    }
    val list = rememberLazyListState()
    var highlight by remember(focus) { mutableStateOf(focus) }
    val category = remember(entries,currentPage) {
        val groupOrder = listOf("Interface", "Display", "Accent", "Text", "Interaction", "Application", "Motion", "Glass effects", "Gestures", "In app", "Calls", "Feedback", "Media", "Sounds", "Incoming calls", "Audio", "Security", "Android notification channels", "Playback", "Management", "Preferences")
        entries.filter { it.section == currentPage && !it.searchOnly && it.destination != currentPage }
            .sortedBy { groupOrder.indexOf(settingGroup(it)) }
    }
    val categoryItems = remember(category) {
        buildList<Pair<String, SettingEntry?>> {
            var previous = ""
            category.forEach { entry ->
                val group = settingGroup(entry)
                if (group != previous) { add("heading:$group" to null); previous = group }
                add(entry.id to entry)
            }
        }
    }
    LaunchedEffect(page, focus) {
        if (focus.isNotEmpty()) {
            val index = categoryItems.indexOfFirst { it.first == focus }
            list.scrollToItem(if (index >= 0) index + 1 + (if (currentPage in listOf("calling", "notifications", "storage", "messaging")) 1 else 0) else 0)
            delay(1200); highlight = ""
        }
    }
    val targetTitle = entries.firstOrNull { it.id == highlight }?.title.orEmpty()
    CompositionLocalProvider(LocalSettingsHighlight provides targetTitle) {
        if (currentPage == "privacy") {
            TelegramPrivacyIndex(onNavigate)
        } else if (currentPage == "tg_ghost") {
            PrivacyScreen(emptyList(), viewModel, focus = focus)
        } else if (currentPage.startsWith("tg_") || currentPage == "devices") {
            TelegramSettingsPage(currentPage, viewModel, onNavigate)
        } else if (currentPage == "search") {
            val requester = remember { FocusRequester() }
            LaunchedEffect(Unit) { requester.requestFocus(); keyboard?.show() }
            val results = remember(entries,query) {
                val words = query.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
                entries.filter { entry -> entry.indexed && words.all { word -> "${entry.title} ${SettingsRoutes.title(entry.section)} ${entry.keywords}".contains(word,true) } }
            }
            Column(Modifier.fillMaxSize().excludeModuleGestures().padding(horizontal = 20.dp)) {
                DefaultInput(query,{query=it},"Search settings…",Modifier.fillMaxWidth().focusRequester(requester), leadingIcon = Icons.Default.Search,
                    framed = false, surface = Color.Transparent)
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item("results") { SettingsHeading("Results") }
                    if (results.isEmpty()) item("empty") { Text("No matching settings",color=DefaultDesign.colors.secondary,style=MaterialTheme.typography.bodyMedium) }
                    items(results,key={it.id}) { entry -> CompactSettingsRow(entry.title,icon=entry.icon,subtitle=SettingsRoutes.title(entry.section)) {
                        keyboard?.hide(); onNavigate(SettingsRoutes.category(entry.destination ?: entry.section,entry.id))
                    } }
                }
            }
        } else LazyColumn(Modifier.fillMaxSize().padding(horizontal=20.dp).testTag("settings_scroll"),state=list,contentPadding=PaddingValues(bottom=28.dp)) {
            if (currentPage == "home") {
                item("account") { SettingsAccountRow(onProfile) }
                featuredSettings.chunked(2).forEachIndexed { row, modules ->
                    item("featured:$row") {
                        Row(Modifier.fillMaxWidth().padding(top = if (row == 0) 20.dp else 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            modules.forEach { (id, title) -> SettingsModuleTile(title, SettingsIcons.route(id), Modifier.weight(1f), accent = id == "appearance") { onNavigate(SettingsRoutes.category(id)) } }
                        }
                    }
                }
                item("secondary_space") { Spacer(Modifier.height(18.dp)) }
                items(secondarySettings, key = { "module:${it.first}" }) { (id, title) ->
                    CompactSettingsRow(title, icon = SettingsIcons.route(id)) { onNavigate(SettingsRoutes.category(id)) }
                }
            } else {
                item("scope") { Spacer(Modifier.height(4.dp)) }
                if (currentPage == "notifications") item("notification_preferences") {
                    SettingsHeading("Messages")
                    listOf("private" to "Private chats", "groups" to "Groups", "channels" to "Channels").forEach { (scope, title) ->
                        CompactSettingsRow(title, icon = if (scope == "private") VectorIcons.Chat else SettingsIcons.Group) { onNavigate(SettingsRoutes.category("tg_notifications_$scope")) }
                    }
                    com.vector.messenger.notifications.NotificationSettings(viewModel)
                }
                if (currentPage == "calling") item("call_privacy_controls") {
                    SettingsHeading("Privacy")
                    if (ghost.authenticated) {
                        CompactSettingsRow("Who Can Call Me", icon = CallIcons.Phone) { onNavigate(SettingsRoutes.category("tg_privacy_calls")) }
                        CompactSettingsRow("Peer-to-Peer Calls", icon = CallIcons.Phone) { onNavigate(SettingsRoutes.category("tg_privacy_p2p")) }
                    }
                    else {
                        Text("Sign in to configure Telegram call privacy.",color=DefaultDesign.colors.secondary,style=MaterialTheme.typography.bodySmall)
                        val account = LocalAccountAction.current
                        CompactSettingsRow("Telegram account",icon=SettingsIcons.User,onClick=account)
                    }
                }
                if (currentPage == "storage") item("storage_usage") {
                    SettingsStoragePanel(viewModel)
                    CompactSettingsRow("Cache", icon = SettingsIcons.Storage) { onNavigate(SettingsRoutes.category("tg_cache")) }
                    CompactSettingsRow("Automatic downloads", icon = VectorIcons.Download) { onNavigate(SettingsRoutes.category("tg_downloads")) }
                }
                if (currentPage == "messaging") item("automatic_downloads") {
                    SettingsHeading("Downloads")
                    CompactSettingsRow("Automatic downloads", icon = VectorIcons.Download) { onNavigate(SettingsRoutes.category("tg_downloads")) }
                }
                items(categoryItems, key = { it.first }) { (key, entry) ->
                    if (entry == null) SettingsHeading(key.removePrefix("heading:"))
                    else Box(Modifier.fillMaxWidth().background(if (entry.id == highlight) DefaultDesign.colors.accent.copy(alpha=.08f) else Color.Transparent)) {
                        SettingsControl(entry, ::open)
                    }
                }
                if (currentPage == "sounds") item("sound_note") { SettingsExplanation("About sounds", "In-app sounds respect silent mode, Do Not Disturb, calls, recording and music playback. Message notification sounds are under Alerts.") }
                if (currentPage == "motion") item("navigation_note") { SettingsExplanation("Gesture guide", "Right + hold opens the module menu. Left + hold returns to the previous module. Pull down at the top of Chats to search. The menu and Search buttons remain available with gestures disabled.") }
                if (currentPage == "about") item("about_info") {
                    val version = remember(context) { context.packageManager.getPackageInfo(context.packageName,0).versionName.orEmpty() }
                    Text("Default · $version",style=MaterialTheme.typography.titleMedium,color=DefaultDesign.colors.text)
                    Text("Appearance preferences are stored on this device. Signed-in chats use Telegram; signed-out chats are a separate demonstration. Manage your session from Account.",style=MaterialTheme.typography.bodyMedium,color=DefaultDesign.colors.secondary)
                    Text("Telegram cloud chats are not end-to-end encrypted. Only supported Secret Chats offer Telegram's end-to-end encryption model.",style=MaterialTheme.typography.bodySmall,color=DefaultDesign.colors.secondary)
                }
            }
            notice?.let { message -> item("notice") { Text(message,color=DefaultDesign.colors.secondary,style=MaterialTheme.typography.bodySmall) } }
        }
    }
    entries.firstOrNull { it.id == selected }?.let { entry ->
        DefaultDialog(entry.title,{selected=null}) {
            if (entry.info != null) Text(entry.info, color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.bodyMedium)
            else LazyColumn(Modifier.heightIn(max=400.dp)) {
                items(entry.options.size,key={it}) { index -> CompactSettingsRow(entry.options[index],icon=entry.icon,selected=index==entry.selected,chevron=false) {
                    entry.choose?.invoke(index); selected=null
                } }
            }
        }
    }
}
