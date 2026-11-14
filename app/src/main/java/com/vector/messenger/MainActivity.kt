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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vector.core.ThemeMode
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
    private val chats: ChatsViewModel by viewModels()
    private val preferences: PreferencesViewModel by viewModels()
    private val authentication: AuthenticationViewModel by viewModels()
    private val profile: ProfileViewModel by viewModels()
    private val search: SearchViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val account = authentication // Starts/restores the process-owned Telegram session asynchronously.
        setContent {
            val theme by preferences.theme.collectAsStateWithLifecycle()
            val dark = theme == ThemeMode.Dark.name || (theme == ThemeMode.System.name && isSystemInDarkTheme())
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            var accountOpen by remember { mutableStateOf(false) }
            val appearance by preferences.appearance.collectAsStateWithLifecycle()
            CompositionLocalProvider(LocalAppearance provides accessibleAppearance(appearance)) {
            DefaultTheme(ThemeMode.valueOf(theme)) {
                DefaultApp(chats, preferences, search, onAccount = { accountOpen = true })
                if (accountOpen) AuthenticationDialog(account, profile, onClose = { accountOpen = false })
            }
            }
        }
    }
}
