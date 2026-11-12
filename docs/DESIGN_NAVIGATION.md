# Modular navigation and Telegram-inspired design

This is an incremental change to DEFAULT's working Phase 1 architecture. No Gradle, AGP, Kotlin, KSP, Hilt, wrapper, SDK, manifest, credential, or TDLib configuration was changed. There are no new dependencies. Existing demo messaging, search, unread state, pin/archive actions, settings, privacy controls, and music sections are preserved.

## Interaction

| Action | Result |
|---|---|
| Swipe right 28 dp, hold 180 ms | One haptic callback; content springs to 95% scale |
| Release the activated right hold | Floating module menu opens |
| Swipe left 28 dp, hold 180 ms, release | Pop the previously visited module |
| Left hold with no history | Stay in place and show brief feedback |
| Release early, reverse, scroll vertically, add a pointer, or cancel | Reset scale; no navigation |
| Menu button | Accessible alternative on every screen |
| Android Back | Close menu/dialog, otherwise pop internal screen, otherwise module history, otherwise exit |

The menu never opens while the initiating finger remains down. Selecting the current module does not add history. History is bounded to 32 visits; going back removes an entry without appending it again.

Gestures can start on eligible module content and header areas, away from system edges. Search fields, the folder strip, composer, message rows, attachment/reaction controls, and settings switches are explicitly excluded. Message bubbles retain swipe-to-reply and long-press actions. Media/document viewers and dialogs are outside the module detector. Any future embedded media viewer must register its interaction region with `excludeModuleGestures()`.

The adapter observes the Main pointer pass after children, yields to consumed child movement, and consumes only after horizontal intent and the displacement threshold. It uses Android touch slop, a 1.5 directional ratio, 20 dp vertical tolerance, 14 dp reversal tolerance, and at least 24 dp edge exclusion (larger system gesture insets win). Root-space coordinates keep scaling from moving the recognition threshold under a stationary finger. Configuration is centralized in `VectorGestureConfig`.

One cancellable hold job exists only during a recognized gesture. Release, cancellation, disposal, disabled gestures, and navigation scope changes cancel it. There is no runtime polling. Movement remains inside the recognizer; UI observes state transitions rather than each pixel. Scale reads animated state in `graphicsLayer`, avoiding per-frame layout measurement. Central motion specifications use Compose's Android duration-scale support. Touch exploration automatically disables the custom gestures; a settings toggle is also available.

## State and architecture

`VectorNavigationController` owns module visit history. Each of the six modules retains its own Navigation Compose controller and internal back stack. `SaveableStateHolder`, `rememberNavController`, and screen saveable state restore the previous section, draft, and scroll position. A Music section such as Playlists remains open after switching away and back. System Back pops that section before returning to module history. Swipe-left deliberately returns to the previous module regardless of the current internal screen.

The Calculator and Gallery gesture sources under `D:/projects` were inspected read-only. Their displacement/hold/scale/release interaction model was reused; the recognizer, child-gesture priority, exclusions, cancellation, and module history are implemented for this project's existing architecture.

## Design and messaging

- Circular avatars; compact chat rows with timestamps, unread counters, pin icons, search, and folders for all chats, unread, groups, channels, and archive.
- New-message action opens the existing sample contacts, which navigate to working conversations.
- AMOLED/graphite surfaces, restrained mint accent, centralized typography, code-native line icons, and subtle transitions. Light/system themes remain available.
- Familiar conversation header, incoming/outgoing grouped bubbles, timestamps, reply previews, reaction toggles, local attachment selection, and a rounded composer with send action.
- Reverse-layout message scrolling keeps the latest message anchored above the composer during IME resizing. Stable keys and remembered list state are retained.
- Long press offers reply, reactions, and selectable text. Reactions toggle off when selected again.

This remains the existing offline foundation. Replies, reactions, and selected attachments use its demo repository; none reach Telegram. The status icon explicitly means local-only storage. Server delivery/read receipts are not fabricated. Attachment selection uses Android's document picker without broad storage permission; no file is copied or uploaded, and URI access may expire. Music sections still have their existing honest empty states; no playlist contents or playback were invented.

## Changed files

Paths are relative to the project root.

| Area | Files |
|---|---|
| Navigation | `app/src/main/java/com/vector/messenger/navigation/DefaultApp.kt`, `ModuleNavigation.kt`, `VectorModuleMenu.kt` |
| Gestures | `app/src/main/java/com/vector/messenger/navigation/gesture/VectorGestureController.kt`, `VectorGestureRecognizer.kt` |
| Design | `app/src/main/java/com/vector/messenger/design/DefaultTheme.kt`, `ChatComponents.kt`, `VectorIcons.kt`, `VectorMotionSpec.kt` |
| Screens | `app/src/main/java/com/vector/messenger/feature/ChatScreens.kt`, `ConversationScreen.kt`, `MusicScreen.kt`, `SettingsScreens.kt` |
| ViewModels | `app/src/main/java/com/vector/messenger/feature/ChatsViewModel.kt`, `PreferencesViewModel.kt` |
| Existing demo model/repository | `core/src/main/kotlin/com/vector/core/Models.kt`, `Repositories.kt`, `demo/DemoRepositories.kt` |
| JVM tests | `app/src/test/java/com/vector/messenger/VectorNavigationTest.kt`, `core/src/test/kotlin/com/vector/core/DemoRepositoryTest.kt` |
| Device tests | `app/src/androidTest/java/com/vector/messenger/FoundationUiTest.kt`, `NavigationGestureUiTest.kt`, `DesignScreenshots.kt` |
| Documentation | `README.md`, this file, and sample screen captures in `docs/screenshots/` |

## Verification

- `:app:assembleDebug`: successful with the existing toolchain.
- `:app:assembleDebugAndroidTest`: successful.
- JVM tests: 21 passed (7 repository, 4 existing ViewModel/settings/route, 10 gesture/history).
- Device tests: 12 passed on physical Samsung SM-S9310 / Android 16; zero failures, errors, or skips in the final run.
- Device coverage: all six modules; restored Music section and chat draft/search; actual vertical scrolling and restored privacy scroll position; Android Back for dialogs, internal screens and module history; outside-tap dismissal; right holds on three consecutive first attempts including the chat-list interior; left-hold history and empty-history feedback; early release, reversal, vertical intent, extra pointer and system cancellation; disabled gestures; search-field exclusion; message swipe-to-reply and reaction toggling.
- Lint: zero errors, 11 existing SDK/tool/dependency update warnings. Versions were intentionally preserved and no warnings were suppressed.
- Visually reviewed device captures: [chat list](screenshots/chats.png), [conversation](screenshots/conversation.png), [module menu](screenshots/module-menu.png), and [reply](screenshots/reply.png). Captures contain only this app's sample content. The reply capture excludes the system keyboard window, which appears as black space below the app surface.

The device locked during an intermediate run. That run was not treated as success; after the user unlocked the phone, the full final suite passed. Quick-Back window-focus timing and keyboard/list anchoring were fixed during verification.

Run the same checks from the project root:

```powershell
.\gradlew.bat :app:assembleDebug --console=plain
.\gradlew.bat :core:test :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest --console=plain
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

Reports: `core/build/reports/tests/test/index.html`, `app/build/reports/tests/testDebugUnitTest/index.html`, `app/build/reports/androidTests/connected/debug/index.html`, and `app/build/reports/lint-results-debug.html`. Screen artifacts also appear under `app/build/outputs/connected_android_test_additional_output/`.

## Limits

Gesture behavior has been exercised by injected pointer sequences on one physical Samsung SM-S9310 running Android 16. This is not a statistical first-attempt reliability study. No 120 Hz/frame-pacing benchmark, battery profile, broad device matrix, TalkBack session, reduced-motion device test, or physical predictive-back edge-swipe test was performed. Android Back key dispatch, menu dismissal, and recognizer exclusion logic are covered separately. No performance improvement is claimed without measurements.

Actual document picker/provider and external-viewer compatibility are not automated in this test suite. Repository attachment metadata is tested. Session/authentication, production messaging, server privacy enforcement, and offline music playback remain future phases.
