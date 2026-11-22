# DEFAULT

Official TDLib authentication is available from the module menu's Account button after developer configuration. Real profile/chat adapters, basic text messaging and Telegram media viewing/downloads reuse that single client. See [TDLib setup, security, native provenance, and verification](docs/TDLIB_AUTH.md), [messaging](docs/TELEGRAM_MESSAGING.md), and [media/download implementation and verification](docs/TELEGRAM_MEDIA.md). Live-service validation and remaining limitations are documented separately from fixture tests.

The foundation now includes Telegram-inspired screens, a full-screen module menu, and release-only swipe-and-hold navigation. See [design/navigation implementation, changed files, and verification](docs/DESIGN_NAVIGATION.md). Existing architecture and build versions are preserved.

DEFAULT is the user-requested name for the Vector specification. Application ID: `com.vector.messenger`. Android 10 / API 29 minimum; compile and target API 36.

Signed-out conversations remain an explicitly labeled demonstration source. Authenticated messaging uses TDLib data without merging demonstration chats. Appearance settings persist locally; selective Ghost policies use account-bound encrypted storage and separate server privacy confirmation. Messenger notifications remain unimplemented. Music now shares the Media3 engine and supports background playback through a media session; voice/video remain foreground-only. The [0.5 audit](docs/DEFAULT_05_AUDIT.md) records the earlier implementation snapshot; subsequent media, voice and music reports document newer capabilities.

## Implemented

- MVVM, repository contracts, StateFlow, lifecycle-aware collection, and Hilt dependency injection.
- One theme: Aurora. A single dark canvas, Material typography, and spacing tokens; the earlier Telegram Glass and Soft Console designs and the Light/Dark/System modes were removed. See [Aurora](docs/AURORA.md).
- Appearance offers AMOLED Black, accent (blue/violet/teal), glass surface opacity, supported-overlay blur, borders and reduced transparency. Headers, composer, dialogs and the module menu use the shared hairline glass material; chat rows remain lightweight and the visible chat-filter bar is removed.
- Edge-to-edge layout, standard text input, IME padding, full-screen module menu, right/left swipe-and-hold navigation with an Aurora hold cue, retained module screen stacks, system back handling, and conversation back button. No permanent navigation bar.
- Searchable chat list with stable keys, unread filter/badges, pinned sorting, archive/unarchive, and contextual actions.
- Demonstration history, grouped bubbles, local composer, swipe-to-reply, reply previews, reaction toggles, and document selection. Local messages are labeled and never presented as delivered/read by Telegram. Validation retains failed drafts; demo history is bounded to 100 messages per chat. Selected attachments reference local document URIs and are not uploaded.
- Contacts, settings, appearance selection, and notification-preview demonstration controls.
- Per-contact Ghost Mode UI for typing, recording, online, and last seen, with Telegram limitations explained.
- Music sections for tracks, albums, artists, playlists, favorites, and recently played, with honest empty states. Downloads has a separate destination.
- Telegram photo/video/animation/document metadata, sampled conversation previews, a zoomable photo viewer, lifecycle-bound Media3/GIF playback, and a TDLib-backed Downloads manager. Open/share uses restricted, revocable content grants rather than filesystem URLs. See the media report for supported formats, tests and limitations.
- Hold/release voice recording with cancel/lock gestures, mono Ogg/Opus encoding, native TDLib voice sends, real amplitude/Telegram waveforms, shared voice/audio playback and a compact floating controller. See [voice/audio implementation and verification](docs/VOICE_AUDIO.md). Playback is foreground-only; live Telegram voice delivery still requires account testing.
- Offline Music: MediaStore discovery, eligible private Telegram downloads, albums/artists/folders, persistent local playlists and recent history, a reorderable queue, shuffle/repeat/speed, Now Playing and one global mini player. See [music architecture, verification and limits](docs/OFFLINE_MUSIC.md). Device music needs audio-media permission; Telegram cache entries need the existing authenticated account.
- Repository, ViewModel, settings restoration, route validation, and Compose interaction tests.

## Project structure

```text
core/                          Pure Kotlin module; no Android or TDLib
  src/main/kotlin/com/vector/core/
    Models.kt                  Models, filtering, result types
    Repositories.kt            Data access contracts
    demo/                      In-memory fixtures/repositories
    auth/                      Auth states/contracts/repository
  src/test/                    Repository behavior tests
app/
  src/main/java/com/vector/messenger/
    DefaultApplication.kt      Hilt application
    MainActivity.kt            Activity and system bar setup
    di/                        Demo repository bindings
    design/                    Central colors and spacing
    navigation/                Navigation Compose graph/menu
    telegram/                  TDLib JNI owner, mappings, configuration, key vault
    feature/                   ViewModels and feature screens
  src/test/                    ViewModel and route tests
  src/androidTest/             Compose device tests
gradle/wrapper/                Wrapper JAR and configuration
docs/PHASE1.md                 Change inventory and verification
```

Two modules keep Android-independent behavior testable without fragmenting empty features. The UI never calls TDLib directly. Room, Coil, Media3, and WorkManager remain deferred; Android Keystore now protects the TDLib database key.

## Toolchain and setup

Use the supplied Gradle Wrapper, not a machine-wide Gradle 9 installation.

| Component | Pinned value |
|---|---|
| Android Gradle Plugin / Gradle | 8.13.2 / 8.13 |
| Kotlin / Compose compiler plugin | 2.3.20 / 2.3.20 |
| Compose BOM | 2025.10.01 |
| KSP / Hilt | 2.3.6 / 2.58 |
| Java and Kotlin bytecode | JVM 17 |
| Runtime | JDK 17 minimum; validated here with JDK 21 |
| SDK platform / Build Tools | 36 / 35.0.0 |

The prototype BOM 2026.09.00 requires API 37 and AGP 9.1+, so it was replaced with a compatible stable BOM. References: [AGP compatibility](https://developer.android.com/build/releases/agp-8-13-0-release-notes), [Kotlin compatibility](https://kotlinlang.org/docs/gradle-configure-project.html), [Compose compiler setup](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler), and [Dagger 2.58 release notes](https://github.com/google/dagger/releases/tag/dagger-2.58). Later Hilt plugins require AGP 9; upgrade the toolchain together.

1. Open this directory in Android Studio; select JDK 17 or 21 for Gradle.
2. In SDK Manager install Android SDK Platform 36, Build Tools 35.0.0, and Platform Tools. Command-line equivalent:

   ```powershell
   & "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" "platforms;android-36" "build-tools;35.0.0" "platform-tools"
   & "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" --licenses
   ```

3. Set `sdk.dir` in ignored `local.properties` to your SDK path.
4. Build and test:

   ```powershell
   .\gradlew.bat :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest --console=plain
   ```

5. With an authorized device/emulator connected:

   ```powershell
   .\gradlew.bat :app:connectedDebugAndroidTest --console=plain
   ```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Reports: each module's `build/reports/`; device reports: `app/build/reports/androidTests/connected/`.

## Security and remaining phases

- TDLib authentication data is private and excluded from backup/transfer; its database key is wrapped with Android Keystore. API configuration is ignored in Git and generated only for local builds. Account passwords/codes are not persisted or logged. See the authentication security notes for APK-extractability and transient-memory limits.
- Cleartext traffic remains prohibited. The app exports its launcher activity; AndroidX adds a permission-protected profile installer receiver, and debug UI-test dependencies add a test activity. Review the merged release manifest before shipping.
- Ghost Mode is a UI foundation. It cannot guarantee invisibility. Telegram server rules and normal online exceptions apply; Premium does not generally bypass others' last seen restrictions.
- Cloud chats are not end-to-end encrypted; supported Secret Chats have a separate encryption model.
- The next messaging phase requires real chat synchronization, message history, sending/receiving and notification lifecycle handling through the official TDLib layer. Live authentication must first be verified manually with your developer configuration and account.
- Real per-contact privacy preferences require protected persistent storage and account scoping. Demo controls must never be silently treated as server-confirmed settings.
- No independent security audit or performance improvement is claimed. Accessibility, background behavior, and performance need further device testing before daily use.

The existing Git working tree, including prior uncommitted design/navigation changes, is preserved. No commit or reset was performed for the authentication phase.
