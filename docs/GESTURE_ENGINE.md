# Default gesture engine refactor

13 November 2026. Scope: module gesture reliability, eligibility and diagnostics. No visual redesign or Telegram features.

## Diagnosis

The inspection covered all pointer-input handlers, nested scroll search, message swipe-to-reply, module history, eligibility geometry, motion specifications and tests. The 28 dp distance was already converted correctly; changing that number alone would not address the failure paths.

Concrete implementation problems and risks:

1. **Child-local system-edge rejection.** Header and content each installed their own detector. The header applied the 24 dp Android edge guard to its own local bounds. A touch 4 dp inside the header could therefore be rejected even when far from the display edge. The new regression exercises that exact starting area.
2. **Independent owners of shared state.** The two detectors wrote the same controller and launched separate delayed hold jobs. Scope changes/disposal could reset shared visual state independently of the active sequence. There is now one event owner and no separate hold coroutine job.
3. **Transformed/cached geometry.** Input handlers lived inside the Scaffold being scaled, and exclusion rectangles were cached on layout callbacks. Graphic transforms and layout callbacks have different lifetimes. The new owner is outside that transform; exclusions retain attached layout coordinates and resolve their current bounds at touch-down. A fixture moves an excluded control using only a graphics transform to exercise this case.
4. **Search did not exclude module navigation.** Both header and content could still activate module holds in Search. Search is now an explicit denial reason, checked at touch-down, deadline and release. There are no child module detectors left behind the workspace. The navigation callback also reads live eligibility to cover state changes before the next composition.
5. **Timing and cancellation were implicit.** The pure state machine previously accepted activation whenever an external job called it. It now owns an explicit hold deadline, cancellation reason and terminal release decision. The adapter uses a single monotonic processing clock; pointer timestamps remain reserved for the existing fast-search calculation. This avoids stale/buffered or synthetic timestamps prematurely expiring a hold. Deadline/up ties resolve once before release navigation.

These findings explain rejection/state-loss paths; they are not a claim that every device-specific failure reported by a user has been reproduced.

## Current architecture

- One `vectorModuleGestures` owner wraps the unscaled application surface.
- Header/content modifiers register eligible geometry and expose the existing semantics/test tags. They contain no pointer detectors.
- Edge exclusion is measured against the full input surface, while buttons, inputs, message rows, folders and other existing exclusions retain priority.
- The deterministic recognizer tracks direction, threshold, hold deadline, activation and cancellation. Threshold movement is measured from the original down position without subtracting touch slop or accumulating deltas from another attempt.
- Compose's scoped pointer timeout waits for either the next event or the remaining hold duration. There is no polling loop, launched timer job or animation coroutine owned by the recognizer.
- One activation produces one haptic request. Navigation occurs once, only after a valid release; early release, reversal, vertical intent, another pointer, priority consumption, system cancellation or eligibility/scope changes invalidate the attempt.
- Search keeps the existing fast-switch distance, velocity, duration and locking implementation. Module eligibility is denied there, independent of whether the menu happens to be visible. Search does not alter module visit history.
- The existing six module controllers, bounded visit-history stack, Android Back policy, scale/motion settings, theme and compact blue **New chat** control are preserved.

The pointer implementation uses Compose's [scoped timeout and event-pass APIs](https://developer.android.com/reference/kotlin/androidx/compose/ui/input/pointer/AwaitPointerEventScope).

## Debug diagnostics

Debug and release use separate source-set implementations; no Gradle changes or BuildConfig flags were needed.

Enable debug logging:

```powershell
adb shell setprop log.tag.DefaultGesture DEBUG
adb logcat -s DefaultGesture:D
```

Disable it after diagnosis:

```powershell
adb shell setprop log.tag.DefaultGesture INFO
```

The debugger/test can alternatively set `GestureDiagnostics.enabled` and attach its debug-only observer. Records contain only direction, configured threshold/hold duration, state, enumerated cancellation reason and navigation action. They contain no coordinates, raw pointer samples, timestamps, screen/contact names, message/query text, Telegram API configuration or account information. Logging is opt-in by default.

The release implementation has no logger, observer or enable switch. Release Kotlin compilation and bytecode inspection verify its `record` method performs no logging or event allocation, regardless of the device log property.

## Verification

Final checks passed using the existing toolchain:

| Check | Result |
| --- | --- |
| `:app:assembleDebug` | Passed; debug APK at `app/build/outputs/apk/debug/app-debug.apk` |
| `:core:test :app:testDebugUnitTest` | 36 tests passed, zero failures |
| `:app:lintDebug` | Zero errors; 12 existing SDK/tool/dependency update notices |
| `:app:connectedDebugAndroidTest` on Samsung SM-S9310 / Android 16 | All 35 tests passed, zero failures or skips |
| `:app:compileReleaseKotlin` | Passed; disabled diagnostics bytecode inspected |

The existing native TDLib create/close smoke test passed in this run. Its existing configuration safety guard was not changed. No live Telegram authentication is claimed from this run.

Automated cases include:

- Exact 28 dp activation across six densities, both directions, repeated cancelled/valid attempts.
- Deadline boundary, jitter without restarting the hold, one activation, one release action, cancellation reason/deadline invalidation.
- Right/left holds, early release, reversal, multitouch/system cancel and repeated first attempts on the Samsung.
- Stationary finger while the child surface scales, live transformed exclusions, competing event ownership and mid-hold Search exclusion.
- Search body/header exclusion, unchanged fast switching/locking, query retention, module visit history/state and Android Back.
- Debug activation/cancellation/action records and release diagnostics isolation.
- Existing authentication/Keystore tests, reply/reactions and chat operations.

The UI regression selector for the already-existing compact **New chat** button was corrected from the stale “New message” accessibility description; the control itself was not changed.

Device tests temporarily extend and then restore the Samsung screen timeout during preparation; existing test helpers also keep their activity awake and restore appearance preferences. No authentication data is read or reset.

Physical-device coverage is limited to the connected Samsung SM-S9310 / Android 16. Manual human gesture trials across grips, hand motion and refresh rates, TalkBack, predictive/system-back edge variations, media/text-selection scenarios and frame-time profiling remain follow-up. Automated injected gestures are not a guarantee of universal first-attempt success or measured 120 Hz smoothness.

## Changed files

Paths relative to the repository root:

- `app/src/main/java/com/vector/messenger/navigation/DefaultApp.kt` — single host, registered surfaces and live policy.
- `app/src/main/java/com/vector/messenger/navigation/gesture/VectorGestureController.kt` — geometry, arbitration, scoped timeout and cleanup.
- `app/src/main/java/com/vector/messenger/navigation/gesture/VectorGestureRecognizer.kt` — deterministic deadline and cancellation state.
- `app/src/main/java/com/vector/messenger/navigation/gesture/GestureDiagnosticEvent.kt` — safe diagnostic fields and shared eligibility policy.
- `app/src/debug/java/com/vector/messenger/navigation/gesture/GestureDiagnostics.kt` — opt-in debug logger/observer.
- `app/src/release/java/com/vector/messenger/navigation/gesture/GestureDiagnostics.kt` — disabled release implementation.
- `app/src/test/java/com/vector/messenger/GestureEngineTest.kt` — deterministic regression coverage.
- `app/src/test/java/com/vector/messenger/VectorNavigationTest.kt` and `SearchGestureTest.kt` — explicit deadline API in existing tests.
- `app/src/androidTest/java/com/vector/messenger/GestureHostUiTest.kt` — real Compose input/transform/priority fixture.
- `app/src/androidTest/java/com/vector/messenger/NavigationGestureUiTest.kt` — edge-boundary and diagnostic regressions.
- `app/src/androidTest/java/com/vector/messenger/TerminalSearchUiTest.kt` — Search must reject module holds.
- `app/src/androidTest/java/com/vector/messenger/RedesignUiTest.kt` — existing New chat control selector.
- `docs/GESTURE_ENGINE.md` — this report.

TDLib/authentication, module history implementation, search repository/UI/recognition function, visual components, manifest, local-data handling and Gradle configuration were not modified.
