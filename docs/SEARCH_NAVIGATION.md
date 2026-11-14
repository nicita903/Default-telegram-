# Default — Search navigation overhaul

Implemented 13 November 2026. This stage builds on the existing module gesture refactor and preserves the terminal design, circular controls, compact blue New chat action, authentication, TDLib, repositories and Gradle configuration.

## Behavior

- **Open:** pull down from the top of Chats. The existing elastic list movement, transient indicator, configured distance (72 dp by default), one threshold haptic and release-only opening remain. The enabled/distance Appearance preferences are respected. Normal Chats has no permanent search field or search button.
- **Close:** pull down in Chat Search or Music Search. The search surface follows the finger and reveals the live Chats screen underneath. Release beyond the configured threshold closes it; a short/reversed/cancelled/multitouch pull returns it smoothly. Results accept dismissal only if their list started the gesture at its top. The scope-label header also accepts dismissal when results are lower.
- **Switch:** fast right from Chat Search selects Music; fast left returns. A dedicated directional recognizer checks distance, velocity, elapsed time, reversal and release. The default minimum is 48 dp / 450 dp per second, adjusted by the existing sensitivity setting. Recent move age and a 400 ms maximum distinguish a flick from a held gesture. Average displacement velocity provides a fallback for sparse samples; a second release cannot navigate again.
- **Lock:** a nonempty query locks horizontal switching when automatic locking is enabled. The tiny existing lock remains beside the label. Blocked attempts produce at most 8 dp resistance, spring-back, one optional haptic tick and a brief highlight. Clearing a query unlocks it. Reopening a saved scope does not replay an old blocked-attempt animation.
- **Back:** checks current platform IME visibility when invoked. It hides the keyboard first; otherwise it closes Search. Explicit close/dismissal hides the keyboard and clears focus without deleting the query.

Inputs, selection and interactive controls have explicit Search exclusions. Category strips retain their own horizontal scrolling. Horizontal Search intent is established before claiming pointer events; ordinary vertical result scrolling remains with the list. Android gesture edges are excluded. Existing button-based close and scope switching remain available for accessibility.

## Architecture and preserved state

Search is an overlay, not a module or a module-history entry. The Chats NavHost remains composed below it. Underlying semantics are hidden while the overlay owns input, but the actual chat-list instance, position and screen state stay alive. A long-list test verifies the same `LazyListState` instance and an exact nonzero index/offset after dismissal.

Chat and Music retain independent queries, result-list positions, categories and collection selection. Scope content uses separate saveable-state providers. No query/result reset is performed by dismissal.

The Search workspace has its own single pointer/nested-scroll owner. The module pointer coroutine returns without awaiting events while Search or its closing transition is present. Header module-surface registrations contain no detectors. Module holds resume after the overlay closes; they do not compete with Search input.

The module-menu Search action also preserves visit history when invoked from another module: it temporarily reveals Chats without calling `visit`/`select`. After Search closes to Chats, the next module Back reveals the original history head. That module's internal screen stack and the earlier visit history are unchanged. The Music playlist → Search → Chats → Back sequence is covered on the Samsung.

The existing repository-backed result layouts are retained. Chat results remain explicitly labeled demo data until Telegram synchronization is implemented. Music uses the existing permission-aware MediaStore repository. No Telegram results, music tracks, playback history or imports were fabricated.

## Verification

| Check | Result |
| --- | --- |
| `:app:assembleDebug` | Passed |
| `:core:test :app:testDebugUnitTest` | All 40 JVM tests passed |
| `:app:connectedDebugAndroidTest` | All 41 tests passed on Samsung SM-S9310 / Android 16; zero skips/failures |
| `:app:lintDebug` | Zero errors; 12 existing SDK/tool/dependency notices |
| `:app:compileReleaseKotlin` | Passed |

The device suite covers opening, interactive reveal/release-only dismissal, dismissal in both scopes, lower-result scrolling, reversal/system cancel/multitouch, fast switching, lock/unlock and spring feedback, module suppression/restoration, independent query retention, nonzero Chats scroll restoration, unchanged module history, keyboard-first Back, existing messaging controls and authentication/Keystore regressions. Test-only long-list data does not alter the application's repository. Device timeout and appearance preferences are restored by the test helpers.

The first Back checks exposed a stale IME visibility snapshot; the callback now reads live platform insets. A previous preference-focused test now uses the explicit Close search control, while the dedicated Back test verifies both keyboard dismissal and workspace closing.

[Interactive dismissal screenshot](screenshots/search-navigation/dismiss-reveal.png) — captured while the finger is still down, showing the original Chats controls revealed beneath Search; reviewed visually.

Automated injected gestures were exercised on the physical Samsung. Human trials across grips and other devices, TalkBack/large-font checks, predictive-back variations and frame-time/high-refresh-rate profiling remain manual follow-up. Populated music libraries and provider-specific media behavior were not newly tested here. No live Telegram login or measured FPS improvement is claimed.

## Files changed in this stage

Paths below are relative to the repository root. The earlier uncommitted module-engine work was preserved.

| File | Change |
| --- | --- |
| `app/src/main/java/com/vector/messenger/navigation/DefaultApp.kt` | Persistent underlay, overlay visibility, history-neutral entry, live IME Back and input ownership |
| `app/src/main/java/com/vector/messenger/navigation/gesture/VectorGestureController.kt` | Disable the module event loop during Search; remove its former Search switching branch |
| `app/src/main/java/com/vector/messenger/feature/ChatScreens.kt` | Hoistable list state with the same remembered default; no visual changes |
| `app/src/main/java/com/vector/messenger/feature/search/SearchOverlay.kt` | New overlay, scope state holders and interactive vertical reveal |
| `app/src/main/java/com/vector/messenger/feature/search/SearchInput.kt` | New exclusive Search input owner, regions and nested-scroll arbitration |
| `app/src/main/java/com/vector/messenger/feature/search/SearchGestureRecognizer.kt` | New deterministic release-only decisions |
| `app/src/main/java/com/vector/messenger/feature/search/SearchWorkspace.kt` | Preserved result UI wired to retained lists and gesture regions; spring lock feedback |
| `app/src/test/java/com/vector/messenger/SearchNavigationTest.kt` | New deterministic switching/dismissal/cancellation tests |
| `app/src/androidTest/java/com/vector/messenger/SearchNavigationUiTest.kt` | New real-screen dismissal, keyboard, history and cancellation coverage |
| `app/src/androidTest/java/com/vector/messenger/SearchScrollRestorationUiTest.kt` | New actual Chats component/long-list restoration fixture |
| `app/src/androidTest/java/com/vector/messenger/TerminalSearchUiTest.kt` | Preference test uses explicit close; prior regressions preserved |
| `docs/SEARCH_NAVIGATION.md`, `docs/screenshots/search-navigation/dismiss-reveal.png` | This report and device capture |

Protected-file checks confirm no changes to design tokens/components, TDLib/authentication, core data/repositories, native binaries, manifest, module-history implementation or Gradle configuration.
