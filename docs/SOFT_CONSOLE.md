# DEFAULT 0.6 — Soft Console

Historical implementation report. The subsequent [Telegram Glass / Pixel Terminal redesign](INTERFACE_DESIGNS.md) supersedes this visual selection and migrates the old Soft Console preference to Pixel Terminal. The verification below records the earlier stage.

## Scope and reference

This is a presentation-only extension of the existing app. Telegram Glass names the existing interface and remains the default on a fresh installation and when older/unknown preferences are loaded. Soft Console is selected under Settings → Appearance → Interface Design. The submitted attachment contained the text specification only; no reference image was available. The implementation follows its written palette and visual requirements, without claiming a match to an unseen image.

Both designs use the same composables, repositories, ViewModels, navigation controllers and player. There is no Activity recreation, theme-keyed subtree, additional TDLib client, new service, data migration or dependency/build configuration change.

## Presentation

- Soft Console dark palette: background `#050607`, surface `#0C1116`, elevated surface `#121B23`, default accent `#389BFF`, secondary accent `#53758B`, text `#E8EDF2`, secondary text `#84929F`, active indicator `#75C8FF`, important-action token `#E8A45B`.
- Central theme tokens supply surfaces, foregrounds, pressed accents, media chrome, scrims and geometry. Existing accent selections, System/Dark/Light mode, density, font scaling and reduced-motion preferences remain usable. Light mode has a corresponding readable light palette; the specified AMOLED palette applies in dark mode.
- Chats retain the original rows, real/demo data source separation, stable list keys, avatars, folders, pin and unread controls, contextual menu and compact New chat action. Soft Console uses compact typography, bright readable unread badges and a restrained folder marker.
- Conversations retain grouping, alignment, read indicators, text selection, reply swipes, reactions, all existing media renderers and the exact send/attachment/recording callbacks. Soft Console changes bubble geometry/spacing and gives the existing composer a floating rounded surface.
- Headers, circular controls, inputs, dialogs, menus, contacts, profile, authentication, settings, Ghost Mode, Search, Music, Downloads, loading/empty/error states inherit the shared palette and typography. Media viewers use themed chrome; conversation thumbnails gain soft clipping. The single existing music/voice mini-player remains in its original reserved area.
- No glow, blur, gradients, decorative commands, new permanent navigation bar, fabricated data or continuous decorative animations.

The redesign does not add missing messaging capabilities. For example, the current chat model does not expose muted status; live attachments and emoji are not newly implemented here. Every existing composer action is preserved rather than adding nonfunctional controls. Telegram Glass retains its existing rendering approach; the name does not introduce a new glass/blur pipeline.

## State and preservation

`InterfaceDesign` is a typed field in the existing `AppearanceSettings`/`AppearanceStore`. Only its enum name is added to `appearance_v2` SharedPreferences. Unknown values fall back to Telegram Glass. Changing it updates the same StateFlow and composition. It does not call any authentication, playback, download, Ghost, conversation or navigation mutation.

The pre-edit source hash snapshot in ignored `build/soft-console-before.json` was used to check scope: **47 protected source files checked, zero changes**. Gesture recognizers/coordinators, search input/nested-scroll implementation, thresholds, motion specifications, module history/host, TDLib/authentication/messaging/profile repositories, secure storage, all core production code and the audio/music/download service implementations remain byte-for-byte unchanged from the start of this phase. In gesture-bearing UI files, edits affect only painting, shapes, typography and padding; no pointer-input callback or key was changed.

Theme-specific tokens are remembered by design, darkness and accent. Existing list keys, graphics-layer motion, lifecycle-aware collection, image loaders, bounded caches and animation disposal are retained. No frame-rate or performance improvement is claimed without measurement.

## Changed files for this phase

Paths are relative to the repository root; `A` = `app/src/main/java/com/vector/messenger`.

| Files | Change |
| --- | --- |
| `A/design/DefaultTheme.kt` | Two design palettes, material token mapping, Soft Console typography and geometry |
| `A/feature/AppearanceSettings.kt` | Typed interface choice in existing preference store |
| `A/feature/SettingsScreens.kt` | Accessible selected-state design selector; themed toggle foreground |
| `A/design/DefaultControls.kt`, `DefaultHeader.kt`, `ChatComponents.kt` | Shared controls, header, avatar fallback and separator styling |
| `A/feature/ChatScreens.kt`, `ConversationScreen.kt` | Chat/bubble/composer presentation |
| `A/navigation/VectorModuleMenu.kt` | Menu surfaces and active indicator |
| `A/feature/search/SearchWorkspace.kt` | Search category styling only |
| `A/feature/media/MediaAttachmentView.kt`, `MediaViewer.kt` | Preview clipping and viewer chrome only |
| `A/feature/audio/VoiceUi.kt` | Microphone icon foreground token only |
| `app/src/test/.../InterfaceDesignTest.kt` | Defaults, palette, accent/light support, contrast and independent gesture settings |
| `app/src/androidTest/.../AppearancePersistenceTest.kt` | Persisted design and old/unknown preference fallback |
| `app/src/androidTest/.../SoftConsoleUiTest.kt` | Isolated theme/navigation/draft/Search/gesture regressions and screenshots |
| `app/src/androidTest/.../MusicDeviceTest.kt` | Confirm the native player identity, queue and playing phase survive theme changes |
| `app/src/androidTest/.../MusicUiTest.kt` | Run the existing isolated playlist CRUD regression in Soft Console |
| `app/src/androidTest/.../FoundationUiTest.kt`, `SearchNavigationUiTest.kt` | Assert actual Music destination identity without assuming the user's catalogue/playlists are empty |
| README and this report | Design selection and verification documentation |

Pre-existing uncommitted changes from earlier phases are preserved and are not attributed to this redesign.

## Verification

The debug build, **123 JVM tests (zero failures)** and lint (**zero errors, 21 warnings**) passed. The final Samsung SM-S9310 / Android 16 run passed **95 instrumentation tests, zero failures, zero skips**, with **BUILD SUCCESSFUL in 3m 2s**. Two microphone-class cases were excluded explicitly, as explained below. Warnings concern SDK/dependency notices, KTX suggestions and the existing exported media-session service. Kotlin initially fell back to in-process compilation because sandbox access to its user-cache daemon directory was denied; subsequent verification runs use the approved normal compiler environment. No tool versions were changed.

JVM verification used `:core:test :app:testDebugUnitTest`. Final build/lint/device command, with `ANDROID_SERIAL=RFCYA11WD1B`:

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug :app:connectedDebugAndroidTest `
  '-Pandroid.testInstrumentationRunnerArguments.notClass=com.vector.messenger.VoiceAudioDeviceTest' `
  '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true' `
  '-Dorg.gradle.jvmargs=-Xmx1024m' --no-daemon --max-workers=1 --console=plain
```

Passing checks include the existing module history, cancellation, system Back/keyboard, Search exclusion/locking/scroll restoration, media-viewer, profile/authentication fixture and Ghost fixture tests. New checks cover design persistence/fallback, text contrast, retained routes/drafts/queries, Soft Console hold/release/pull gestures and real native music-player continuity through theme changes. Playlist CRUD also runs with the Soft Console presentation. These tests do not authenticate or send through a live Telegram account.

Final cleanup confirmed screen timeout restored to **300000 ms**, and user-0 audio-library and microphone permissions remained at their original **granted** values. The installed app data directory retained its original inode. No app was uninstalled, no session was cleared, and temporary music fixtures/preferences were cleaned up or restored. `git diff --check` passed.

Final logs (ignored): `soft-console-final-verification.log` records the passing JVM checks and the two stale UI assertions, and `soft-console-final-device-tests.log` records the clean 95-case rerun. Reports: `core/build/test-results/test`, `app/build/test-results/testDebugUnitTest`, `app/build/reports/lint-results-debug.html`, `app/build/reports/androidTests/connected/debug/index.html`. APK: `app/build/outputs/apk/debug/app-debug.apk`, signed with the same normal debug certificate as the installed app.

The new UI tests use isolated local preferences and demo repositories, never a live TDLib account. They check immediate design selection, retained internal Music route, retained Search query/lock, retained conversation draft and local-only send callback, hold activation before release with menu opening after release, pull-down Search, horizontal scope switching and dismissal. Screenshots contain synthetic fixture data only. Music transport verification uses the existing temporary silent WAV fixtures and shared player, with settings restored and fixtures deleted afterward.

Verification issues encountered and handled:

- The first focused run passed 8 of 9 cases. Its new playback assertion checked `Playing` immediately after the next-track identity changed, while Media3 was still `Loading`. The test now waits for both the selected track and `Playing` before comparing player identity, queue and phase across theme changes; no playback code was changed.
- A broader attempt was interrupted by the wireless device disconnecting. USB was used for final verification.
- Sandboxed and normal builds used different default Android debug keys. Installation was rejected before any tests ran. The normal key's public certificate was compared with the installed APK and matched. Both APKs were rebuilt in that environment, preserving app/session data; nothing was uninstalled or reset.
- A forced normal-environment rerun exhausted the host paging file during JVM test startup. The build daemon was stopped, and a temporary command-line 1 GB Gradle heap was used for the retry. No checked-in configuration or tool versions changed.
- The first complete USB suite ran all 95 selected cases: 93 passed, with two older tests failing because they assumed Albums and Playlists must be empty. Their assertions now check the stable Music section tags, including the restored Playlists route after Search, without deleting the navigation/back assertions or changing the user's library. The full suite was rerun after these corrections.

The microphone-recording instrumentation class (`VoiceAudioDeviceTest`, two cases) is excluded from this visual-only regression run. This does not remove or modify those tests. Voice UI gesture code and the recording engine remain unchanged; the existing JVM recording/ownership tests remain included.

Real Telegram login, message delivery, live media/download operations and server privacy guarantees cannot be established from synthetic UI tests. Those remain subject to the manual checks in their existing feature reports. A visual-only diff plus passing regressions is not a guarantee of every runtime condition or refresh rate.

## Reviewed screenshots

These Samsung captures use isolated synthetic fixtures, not live account data. The Settings capture confirms the design selector's ripple stays within its rounded surface.

| Screen | Capture |
| --- | --- |
| Chats | [Chats](screenshots/soft-console/chats.png) |
| Conversation and composer | [Conversation](screenshots/soft-console/conversation.png) |
| Module menu | [Menu](screenshots/soft-console/menu.png) |
| Settings and design selector | [Settings](screenshots/soft-console/settings.png) |
| Search and tiny lock | [Search](screenshots/soft-console/search.png) |
| Music | [Music](screenshots/soft-console/music.png) |
| Contacts | [Contacts](screenshots/soft-console/contacts.png) |
| Ghost demonstration | [Ghost Mode](screenshots/soft-console/ghost.png) |
| Downloads signed-out state | [Downloads](screenshots/soft-console/downloads.png) |
