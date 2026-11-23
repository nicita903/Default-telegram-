package com.vector.messenger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vector.messenger.design.DefaultTheme
import com.vector.messenger.feature.ChatsViewModel
import com.vector.messenger.feature.PreferencesViewModel
import com.vector.messenger.feature.AuthenticationViewModel
import com.vector.messenger.feature.ProfileViewModel
import com.vector.messenger.feature.AuthenticationDialog
import com.vector.messenger.feature.LocalAppearance
import com.vector.messenger.feature.accessibleAppearance
import androidx.compose.runtime.CompositionLocalProvider
import com.vector.messenger.feature.search.SearchViewModel
import com.vector.messenger.navigation.DefaultApp
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @javax.inject.Inject lateinit var sounds: com.vector.messenger.sound.DefaultSounds
    @javax.inject.Inject lateinit var notifications: com.vector.messenger.notifications.TelegramNotifications
    override fun onStart() { super.onStart(); notifications.foreground = true; sounds.foreground(true) }
    override fun onStop() { notifications.foreground = false; sounds.foreground(false); super.onStop() }
    private val chats: ChatsViewModel by viewModels()
    private val preferences: PreferencesViewModel by viewModels()
    private val authentication: AuthenticationViewModel by viewModels()
    private val profile: ProfileViewModel by viewModels()
    private val avatars: com.vector.messenger.feature.avatar.AvatarViewModel by viewModels()
    private val profileModule: com.vector.messenger.feature.profile.ProfileModuleViewModel by viewModels()
    private val search: SearchViewModel by viewModels()
    private val media: com.vector.messenger.feature.media.MediaViewModel by viewModels()
    private val audio: com.vector.messenger.feature.audio.AudioViewModel by viewModels()
    private val voice: com.vector.messenger.feature.audio.VoiceViewModel by viewModels()
    private val music: com.vector.messenger.feature.music.MusicViewModel by viewModels()
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent); setIntent(intent); notifications.accept(intent)
        if (intent.getBooleanExtra("open_music_player", false)) music.openPlayer()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notifications.accept(intent)
        enableEdgeToEdge()
        val account = authentication // Starts/restores the process-owned Telegram session asynchronously.
        setContent {
            val appearance by preferences.appearance.collectAsStateWithLifecycle()
            val recording by voice.state.collectAsStateWithLifecycle()
            SideEffect { sounds.recording(recording.phase in setOf(com.vector.core.audio.RecordingPhase.Starting, com.vector.core.audio.RecordingPhase.Recording,
                com.vector.core.audio.RecordingPhase.Locked, com.vector.core.audio.RecordingPhase.Finishing)) }
            SideEffect {
                // Aurora owns a single dark canvas; the system bars never switch to light icons.
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
            }
            var accountOpen by remember { mutableStateOf(false) }
            CompositionLocalProvider(LocalAppearance provides accessibleAppearance(appearance),
                com.vector.messenger.feature.media.LocalMediaViewModel provides media,
                com.vector.messenger.feature.audio.LocalAudioViewModel provides audio,
                com.vector.messenger.feature.audio.LocalVoiceViewModel provides voice,
                com.vector.messenger.feature.avatar.LocalAvatarViewModel provides avatars,
                com.vector.messenger.feature.profile.LocalProfileModuleViewModel provides profileModule,
                com.vector.messenger.feature.avatar.LocalAccountAction provides { accountOpen = true },
                com.vector.messenger.feature.music.LocalMusicViewModel provides music) {
            DefaultTheme {
                DefaultApp(chats, preferences, search, onAccount = { accountOpen = true }, notificationHost = notifications, soundHost = sounds)
                com.vector.messenger.feature.audio.AudioLifecycle()
                com.vector.messenger.feature.music.NowPlayingHost()
                androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { music.refresh() }
                androidx.compose.runtime.LaunchedEffect(Unit) { if (intent.getBooleanExtra("open_music_player", false)) music.openPlayer() }
                if (accountOpen) AuthenticationDialog(account, profile, onClose = { accountOpen = false })
                com.vector.messenger.feature.avatar.AvatarHost(avatars, chats)
            }
            }
        }
    }
}
