# Default motion refinement

## Changes

Motion specifications are centralized in `VectorMotionSpec`: press compression, configured surface zoom, module and screen transitions, Search transitions, menu reveal/exit, and lock resistance. Controls respond with a short press-in and a critically damped release. Speed, intensity and reduced-motion settings apply consistently. Swipe-to-reply retains its original spring parameters.

`rememberInteractiveOffset` observes drag targets outside composition and exposes an animation value for graphics-layer reads. Interactive movement follows the finger directly; release starts a cancellable return. Hidden or reduced-motion surfaces reset immediately. Its collection and animation are scoped to the composable lifecycle.

Menu and Search visibility targets update in the same composition, removing the previous effect-driven handoff. Fixed-size module/Search transitions no longer animate content size. Lock feedback clears on cancellation and preference/query changes. Haptics remain attached to the existing recognition events; no gesture thresholds, ownership rules or navigation actions changed.

The AMOLED design, shapes, colors, layouts and compact New chat action are preserved. Existing lazy-list keys/content types and lifecycle-aware StateFlow collection were inspected. Startup, TDLib and media-loading paths were left unchanged: this task produced no cold-start or image-loading evidence warranting changes. No dependencies, build versions, caches or permanent animation loops were added.

## Measurements

Samsung SM-S9310, Android 16. Three trials per workload, debug APK, instrumented Compose frame-clock replay. Applied-change counts use the public `RecomposerInfo.changeCount`; they count applied-change batches, **not total recompositions or individual component executions**. Window frame durations use Android `FrameMetrics`, omit first-draw frames, and exclude separate Dialog windows. No frame reports were dropped in the before/final samples.

| Workload | Median applied batches, before → final | Median of trial P95 frame duration, ms, before → final | Sampled frames / deadline overruns, before → final |
| --- | ---: | ---: | ---: |
| Chats pull/cancel | 20 → 3 | 4.933 → 4.402 | 108 / 1 → 63 / 0 |
| Filled icon press/cancel | 27 → 6 | 8.264 → 3.463 | 27 / 3 → 27 / 0 |
| Search pull/cancel | 19 → 0 | 8.675 → 4.633 | 55 / 9 → 57 / 0 |
| Module/Search roundtrip | 71 → 49 | 73.180 → 62.316 | 21 / 21 → 21 / 21 |
| 80-row list scroll | 0 → 0 | 7.968 → 5.432 | 554 / 35 → 554 / 16 |

The targeted applied-batch reductions were 85% for Chats pulls, 100% for Search pulls and approximately 78% for filled icon presses. Counts were identical across the three trials in each group. Zero in the scrolling workload does not mean lazy items never compose.

Frame durations are exploratory. Artificial clock replay batches navigation into few real window frames: the roundtrip deadline statistics are **not representative of human navigation jank or FPS**. Frame times varied between sessions. An APK crossover reproduced the applied-batch reductions, but scrolling timing was mixed. Its candidate scroll P95 was 12.990 ms versus 10.301 ms for the repeated baseline. An earlier candidate also sampled worse scrolling timing. Consequently, speculative row/menu-row drawing changes were reverted, and the final quiet-icon paint path was restored. The lower final scrolling sample does not establish an FPS improvement.

The crossover candidate predates that final quiet-icon restoration and must not be treated as the final APK. Raw artifacts and their labels:

- [Before/final summary](performance/motion/summary.json)
- [Before interactions](performance/motion/before-interactions.json), [before scrolling](performance/motion/before-scroll.json)
- [Final interactions](performance/motion/final-interactions.json), [final scrolling](performance/motion/final-scroll.json)
- [Crossover baseline interactions](performance/motion/crossover-before-interactions.json), [scrolling](performance/motion/crossover-before-scroll.json)
- [Crossover candidate interactions](performance/motion/crossover-candidate-interactions.json), [scrolling](performance/motion/crossover-candidate-scroll.json)
- [Initial candidate scrolling](performance/motion/candidate-initial-scroll.json)

No input-to-photon, release Macrobenchmark, cold-start, battery or heap-allocation measurement was performed. No guaranteed refresh rate is claimed. Human motion review, other devices, TalkBack, real-media workloads and release Perfetto/Macrobenchmark runs remain follow-up work.

## Verification

Final command (one Samsung transport selected with `ANDROID_SERIAL`):

```powershell
.\gradlew.bat :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:connectedDebugAndroidTest :app:compileReleaseKotlin '-Pandroid.testInstrumentationRunnerArguments.motionProfileLabel=final' --max-workers=1 --console=plain
```

Result: **BUILD SUCCESSFUL**. 45 JVM tests and 45 Samsung instrumentation tests passed, with no failures or skips. Lint reported zero errors and 12 existing SDK/tool/dependency notices. Debug assembly and release Kotlin compilation passed. Local execution output is in `motion-final-checks.log`.

New tests cover motion preference calculations, press timing, nonoscillating returns, exact preservation of reply-follow physics, interactive-offset updates without fixture-parent recomposition, disposal during return, and reduced-motion cancellation. Existing regression tests cover module history/Back, hold gestures and cancellation, Search ownership/switching/locking, keyboard Back and scroll restoration. These automated replays do not establish universal physical-device gesture reliability or perceptual smoothness.

Protected-file SHA-256 comparison against the pre-task snapshot confirmed unchanged gesture recognizers/controllers, Search input/recognition, module history, authentication/Telegram/core sources, manifest, startup and Gradle configuration. `git diff --check` passed. Test-only profiling repositories and observers do not ship in the application. Frame listeners, observer scopes and worker threads are removed/terminated after sampling. The device screen timeout was restored to its original 300000 ms; temporary test instrumentation was removed without clearing application data.

## Files changed in this phase

All Kotlin paths below are under `app/src/`:

- `main/java/com/vector/messenger/design/VectorMotionSpec.kt`
- `main/java/com/vector/messenger/design/InteractiveOffset.kt` (new)
- `main/java/com/vector/messenger/design/DefaultControls.kt`
- `main/java/com/vector/messenger/feature/ChatScreens.kt`
- `main/java/com/vector/messenger/feature/ConversationScreen.kt`
- `main/java/com/vector/messenger/feature/search/SearchOverlay.kt`
- `main/java/com/vector/messenger/feature/search/SearchWorkspace.kt`
- `main/java/com/vector/messenger/navigation/DefaultApp.kt`
- `main/java/com/vector/messenger/navigation/VectorModuleMenu.kt`
- `test/java/com/vector/messenger/MotionSpecTest.kt` (new)
- `androidTest/java/com/vector/messenger/MotionLifecycleUiTest.kt` (new)
- `androidTest/java/com/vector/messenger/MotionProfileUiTest.kt` (new)

Documentation: this report and `docs/performance/motion/*.json`. Other existing working-tree changes belong to previous stages and were preserved.
