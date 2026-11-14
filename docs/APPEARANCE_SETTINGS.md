# Appearance and gesture customization

Open **Settings → Appearance** from the compact module menu. The existing terminal design and circular controls are retained. Categories now group module navigation, Search navigation, animations and interface controls.

| Preference | Default | Available range or choices |
| --- | --- | --- |
| Module gestures | On | On / off |
| Swipe activation distance | 28 dp | 20–60 dp |
| Hold duration | 180 ms | 120–400 ms |
| Haptic feedback | On | Shared by module and Search navigation |
| Screen zoom intensity | 5% | 0–10% |
| Pull-down search | On | On / off |
| Pull-down activation distance | 72 dp | 48–120 dp; shared opening/dismissal threshold |
| Swipe-down dismissal | On | Independent of opening and horizontal switching |
| Fast Chat/Music switching | On | Gesture only; scope buttons remain available |
| Fast-swipe sensitivity | 100% | 70–140%; higher values reduce required distance and velocity |
| Automatic search locking | On | Nonempty active query locks its scope |
| Lock indicator visibility | On | Preserves the existing optional visual indicator |
| Lock feedback | On | Elastic movement, brief highlight and blocked-swipe haptic |
| Animation speed | 100% | 50–150% |
| Animation intensity | 100% | 0–100% |
| Reduced motion | Off | Removes animated motion |
| Density | Compact | Compact / Comfortable |
| Accent color | Electric blue | Electric blue / Ice blue / Periwinkle |
| Typography scale | 100% | 85–130%, combined with Android font scale |

## Persistence and immediate application

`AppearanceStore` retains the existing private `appearance_v2` SharedPreferences file and StateFlow interface. Updates emit immediately and use asynchronous `apply()` disk writes. No persistence dependency or Gradle configuration change is required. Numeric bounds are shared between loading and updating. Invalid theme names are rejected. Older files default the new `dismissSearch` preference to true, preserving previous behavior.

Settings contain non-sensitive presentation values only. Queries, messages, account data, codes, passwords and TDLib keys are not stored here. Authentication and TDLib components are unchanged.

`accessibleAppearance` applies live platform restrictions without overwriting saved preferences. Android's disabled animator setting forces reduced motion; disabled system haptics suppress app haptics. Content observers register while the UI is started and unregister on stop/disposal. Existing touch-exploration gesture exclusions remain in place. Font scaling continues to include the system font scale.

The existing `elastic` key now controls all optional blocked-lock feedback, avoiding a second conflicting feedback preference. Turning it off leaves the query lock and optional lock icon intact. The shared haptic preference remains an additional prerequisite for any navigation haptic.

## Navigation safety

- The header's module-menu button remains available when module gestures are off.
- The module menu's Search action and Chats' accessibility action remain available when pull-down search is off.
- Search retains visible Chat/Music scope controls when fast switching is off. Automatic query locking still applies until the query is cleared or automatic locking is disabled.
- The header Close search button and Android Back remain available when dismissal is off. Keyboard Back behavior is preserved.
- Search continues to suppress module gestures and does not add module-history entries.
- Changing gesture thresholds or enabling/disabling gestures cancels the affected active pointer coroutine and resets its transient state. Recognition still commits only on release.

## Files in this phase

Production files under `app/src/main/java/com/vector/messenger/`:

- `feature/AppearanceSettings.kt`: new dismissal preference, shared validation, persisted updates.
- `feature/AccessibleAppearance.kt`: lifecycle-scoped system preference overrides (new).
- `feature/SettingsScreens.kt`: dismissal toggle, unified lock feedback, animation category and explicit density choices.
- `MainActivity.kt`: effective appearance provider.
- `navigation/DefaultApp.kt`: consumes effective appearance; gates blocked-swipe haptics by lock-feedback preference.
- `feature/search/SearchInput.kt`: independent dismissal eligibility, nested-scroll gating and pointer cancellation keys.
- `feature/search/SearchWorkspace.kt`: suppresses optional lock feedback when disabled.

New tests: `AppearanceSettingsTest`, `AppearancePersistenceTest`, `AppearanceNavigationUiTest`, `AccessibleAppearanceUiTest`. Other working-tree changes from previous stages are preserved.

## Verification

Final command (Samsung transport selected with `ANDROID_SERIAL`):

```powershell
.\gradlew.bat :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:connectedDebugAndroidTest :app:compileReleaseKotlin --max-workers=1 --console=plain
```

**BUILD SUCCESSFUL** in 2m 49s. **48 JVM tests and 53 Samsung SM-S9310 / Android 16 tests passed**, zero failures or skips. Debug assembly and release Kotlin compilation passed. Lint: zero errors, 12 existing dependency/tool notices. SDK 36 also emits deprecation warnings for reading the legacy system haptic setting; its live override was verified on the Samsung. Local output: `appearance-final-checks.log`. `git diff --check` passed.

The tests cover preference bounds, saved file contents and store reconstruction, migration defaults, live gesture changes, safe button navigation with gestures off, query locking/feedback, density/typography changes, platform motion/haptic overrides and existing navigation regressions. The Settings screenshot was reviewed for continuity with the existing design. Tests restore the user's Appearance preferences and platform animation/haptic values; temporary instrumentation was removed.

Protected-source hashes confirm that Gradle configuration, manifest, authentication/Telegram/core files, module history and module recognizers/controllers remain unchanged. The intentional protected-source changes are limited to the MainActivity effective appearance provider and Search input's dismissal setting.

Persistence testing reopens the store and verifies completed private-file writes. It does not kill the application process during an authentication session. Physical-device tests do not establish universal gesture reliability across all devices or accessibility configurations.
