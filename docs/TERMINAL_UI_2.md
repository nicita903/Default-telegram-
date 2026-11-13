# Default — Terminal UI 2.0

Implemented 13 November 2026 on top of the existing application. This checkpoint supersedes the square visual treatment in the earlier A/B redesign; it retains the application architecture, six module controllers, module history, authentication and local data.

## Interface and interactions

- AMOLED black `#000000`; surfaces `#0B0D11` / `#151921`; text `#F2F4F7` / `#858C98`; functional blue `#469CFF`, pressed blue `#2679DB`; dividers `#1E232B`. The existing light-theme option remains available.
- Circular controls/avatars, capsule input, rounded message surfaces, minimal separators and compact conversation rows. No permanent search field or search button is present on normal Chats. No permanent bottom navigation bar was added.
- Pull down on the conversation list **starting at the top**. The list follows with resistance, a transient search glyph appears, and the first crossing of the configured 72 dp threshold produces one haptic. A genuine release opens the full-screen search workspace with a coordinated transition. Short pulls, reversal, multitouch, cancellation and pulls starting lower do not open it.
- Search is also available in the module menu and as a custom accessibility action on the Chats list container. This provides access when pull gestures are disabled without putting search controls on normal Chats.
- Chat Search has people/conversation/message/media/file/link filters, session recent searches and a contact picker. Selecting a result opens its existing conversation. Search uses the actual current repository, which is still the application's explicitly labeled in-memory demo repository; no Telegram synchronization was invented.
- Music Search has a distinct surface and reads real local songs, album/artist groupings, provider playlists and recently added audio from MediaStore. Track selection opens an installed audio player with a temporary URI grant. Audio access is requested only when the user chooses **Allow audio access**. Denial and permission changes have explicit states.
- Fast right swipe switches Chat Search to Music Search; fast left swipe returns. The release must satisfy direction, distance, velocity and duration checks. The existing hold recognizer and fast search recognizer share the same pointer loop, so a held/cancelled sequence cannot also trigger a fast switch. Controls, input fields and system edges retain exclusions.
- Within Search, continuing movement postpones module hold activation until the finger pauses. A real Android touch-event test covers continuous travel lasting beyond the 180 ms hold timer. The existing hold behavior outside Search remains unchanged.
- Entering any text automatically locks the current search scope. The tiny lock sits beside the scope label; clearing the query unlocks switching. A blocked switch produces up to 8 dp of elastic resistance and one optional haptic, then returns without changing the query or scope. An explicit scope-switch button follows the same lock rule.
- Module navigation remains swipe right/left 28 dp, hold 180 ms, scale toward 95%, then navigate **after release**. The module menu now has rounded rows and circular icons. Header and button alternatives remain available. Search is screen state within Chats, not an extra module or an entry in module history.

## Appearance → Navigation & Gestures

The settings screen now persists non-sensitive UI preferences in app-private `appearance_v2` SharedPreferences and publishes them through StateFlow. Compose collects the state with lifecycle awareness. Settings affect the running interface immediately:

| Area | Controls |
| --- | --- |
| Module navigation | Enabled, activation distance, hold time, haptics, zoom, animation speed |
| Search navigation | Pull enabled/distance, fast switching/sensitivity, automatic lock, lock visibility, elastic feedback |
| Interface | Compact/comfortable rows, three accent choices, animation intensity, reduced spatial motion, typography scale |

Reduced motion removes spatial travel/zoom/compression, and central transition durations respond to the setting. Compose also inherits Android's system animator scale. Typography multiplies the existing system font scale rather than replacing it. Interactive controls retain 48 dp targets and semantic labels; the lock glyph is a non-interactive status indicator.

## Verification

The requested `:app:assembleDebug` build passed with the existing Gradle, AGP, Kotlin and KSP versions. Final results:

| Check | Result |
| --- | --- |
| `:core:test :app:testDebugUnitTest` | 31 tests passed, zero failures |
| `:app:lintDebug` | Zero errors; 12 pre-existing SDK/tool/dependency update notices |
| `:app:connectedDebugAndroidTest` on Samsung SM-S9310 / Android 16 | 30 tests: 29 passed, one intentional TDLib safety skip, zero failures/errors |
| `:app:assembleDebug` | Passed; APK at `app/build/outputs/apk/debug/app-debug.apk` |

The real-time gesture check injects finger events using Android's native input path and measures their actual down/up timestamps. Its movement samples stay within the velocity tracker's sampling window. The complete final suite includes this case, rather than combining partial test counts.

The automated coverage includes:

- No search controls on normal Chats; short pull, threshold crossing, release-only opening and Back.
- Pulls starting lower in a long test-only list, including reaching the top during the same gesture.
- Reversal, multitouch and synthetic system cancellation. The cancellation regression caught and fixed a consumed synthetic up event being mistaken for a release.
- Fast switching in both directions, automatic locking/unlocking in both workspaces, query preservation and absence of an accidental module menu.
- Measured locked-swipe displacement at most 8 dp and return to zero using the Compose animation test clock.
- Hold navigation inside Search, existing module navigation/history/state, message swipe-to-reply, reactions, drafts, pin/archive actions and secure Account UI.
- Actual settings controls and a second preference-store instance restoring their values. UI tests restore the user's previous appearance settings and use a test-only keep-screen-awake flag.

One intermediate run was invalidated by the Samsung locking; it was rerun after unlock. The native TDLib smoke test retains its existing safety skip when developer API configuration is present, avoiding a competing native receiver.

## Security and limitations

- TDLib source/binaries, authentication code, database-key/session handling, Telegram API configuration, backup protection and build versions were not changed. Protected file hashes were compared with the prior baseline. The manifest change adds only Android's audio read permission and the API ≤32 equivalent; backup/export/network restrictions remain intact.
- No credentials or authentication codes are logged. Search text is kept in ViewModel/SavedStateHandle/session recents; it is not written into appearance preferences. Existing authentication integration remains separate. Live Telegram authentication was not retested or claimed here.
- Telegram group/channel creation and audio importing require the absent messaging/media repositories. Their UI states explicitly explain that limitation. Recently played remains empty because this client has no playback-history source; external-player history is not fabricated. Existing module library placeholders remain intact.
- Audio permission denial/empty-library UI was exercised. A populated library, provider-specific playlists, removable storage, audio permission revocation and external-player handoff still need manual testing with real media. Playlists depend on the device's legacy MediaStore playlist provider; provider failure does not discard accessible songs.
- Physical-device gesture tests ran on the Samsung SM-S9310, Android 16. Full TalkBack/large-font/device-matrix review, notification-shade and predictive-back edge checks, frame-time/120 Hz profiling and battery measurements remain manual. No frame-rate or battery improvement is claimed.

The implementation follows Android's [nested scroll phases](https://developer.android.com/develop/ui/compose/touch-input/scroll/nested-scroll-modifiers) and [shared audio access model](https://developer.android.com/training/data-storage/shared/media).

## Device screenshots

Captured from the app's Compose surface with sample chat data; no account window or personal music library was captured. The screenshots were visually reviewed.

- [Chats](screenshots/terminal-ui-2/chats.png)
- [Conversation](screenshots/terminal-ui-2/conversation.png) and [reply](screenshots/terminal-ui-2/reply.png)
- [Chat Search](screenshots/terminal-ui-2/chat-search.png) and [automatic lock](screenshots/terminal-ui-2/search-locked.png)
- [Music Search](screenshots/terminal-ui-2/music-search.png)
- [Module menu](screenshots/terminal-ui-2/module-menu.png)
- [Appearance settings](screenshots/terminal-ui-2/settings.png)

## Changed files for this checkpoint

All paths are relative to the repository root. Earlier uncommitted A/B work was retained; `docs/TDLIB_AUTH.md` was already edited before this checkpoint and was not touched.

| Path | Purpose |
| --- | --- |
| `app/src/main/AndroidManifest.xml` | Audio read permissions only |
| `app/src/main/java/com/vector/messenger/MainActivity.kt` | Search ViewModel and appearance provider |
| `app/src/main/java/com/vector/messenger/design/DefaultTheme.kt` | New palette and typography scaling |
| `app/src/main/java/com/vector/messenger/design/DefaultControls.kt` | Circular controls and capsule input |
| `app/src/main/java/com/vector/messenger/design/DefaultHeader.kt` | Compact optional subtitle |
| `app/src/main/java/com/vector/messenger/design/VectorIcons.kt` | Decrease icon |
| `app/src/main/java/com/vector/messenger/design/VectorMotionSpec.kt` | Preference-aware motion |
| `app/src/main/java/com/vector/messenger/di/RepositoryModule.kt` | Appearance store binding |
| `app/src/main/java/com/vector/messenger/feature/AppearanceSettings.kt` | New persistent preference model/store |
| `app/src/main/java/com/vector/messenger/feature/PreferencesViewModel.kt` | Lifecycle state and updates |
| `app/src/main/java/com/vector/messenger/feature/SettingsScreens.kt` | Functional compact settings controls |
| `app/src/main/java/com/vector/messenger/feature/ChatScreens.kt` | Flat Chats, pull integration, accessible action |
| `app/src/main/java/com/vector/messenger/feature/ConversationScreen.kt` | Rounded message surfaces |
| `app/src/main/java/com/vector/messenger/feature/MusicScreen.kt` | Compact circular library navigation |
| `app/src/main/java/com/vector/messenger/feature/search/MusicLibraryRepository.kt` | New read-only MediaStore layer |
| `app/src/main/java/com/vector/messenger/feature/search/SearchViewModel.kt` | New separate search state |
| `app/src/main/java/com/vector/messenger/feature/search/SearchWorkspace.kt` | New Chat/Music search UI and lock feedback |
| `app/src/main/java/com/vector/messenger/navigation/DefaultApp.kt` | Search screen state, transitions and Back priority |
| `app/src/main/java/com/vector/messenger/navigation/VectorModuleMenu.kt` | Rounded module rows and accessible Search entry |
| `app/src/main/java/com/vector/messenger/navigation/gesture/VectorGestureController.kt` | Shared hold/fast arbitration and preference thresholds |
| `app/src/main/java/com/vector/messenger/navigation/gesture/SearchGestures.kt` | New pull adapter and deterministic fast-swipe decision |
| `app/src/test/java/com/vector/messenger/ChatsViewModelTest.kt` | Preference dependency fixture |
| `app/src/test/java/com/vector/messenger/SearchGestureTest.kt` | New velocity/direction/locking tests |
| `app/src/androidTest/java/com/vector/messenger/FoundationUiTest.kt` | Search workspace regression path |
| `app/src/androidTest/java/com/vector/messenger/NavigationGestureUiTest.kt` | Updated normal-Chats assertions and retained navigation checks |
| `app/src/androidTest/java/com/vector/messenger/RedesignUiTest.kt` | Updated search/contact-picker path |
| `app/src/androidTest/java/com/vector/messenger/TerminalTestSupport.kt` | New preference isolation and pull helper |
| `app/src/androidTest/java/com/vector/messenger/TerminalPullUiTest.kt` | New long-list/cancellation adapter checks |
| `app/src/androidTest/java/com/vector/messenger/TerminalSearchUiTest.kt` | New search/lock/settings/device checks |
| `docs/TERMINAL_UI_2.md`, `docs/screenshots/terminal-ui-2/` | This report and sample-data captures |
