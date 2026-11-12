package com.vector.messenger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vector.core.ThemeMode
import com.vector.messenger.design.DefaultTheme
import com.vector.messenger.feature.ChatsViewModel
import com.vector.messenger.feature.PreferencesViewModel
import com.vector.messenger.navigation.DefaultApp
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val chats: ChatsViewModel by viewModels()
    private val preferences: PreferencesViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val theme by preferences.theme.collectAsStateWithLifecycle()
            val dark = theme == ThemeMode.Dark.name || (theme == ThemeMode.System.name && isSystemInDarkTheme())
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            DefaultTheme(ThemeMode.valueOf(theme)) { DefaultApp(chats, preferences) }
        }
    }
}
