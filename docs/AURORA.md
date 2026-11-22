# Aurora interface design

Aurora is the only theme in the app. The two earlier designs (Telegram Glass and Soft Console) and the
Light/Dark/System theme modes were removed, along with the pixel and glass material branches they
needed. There is one canvas, one palette family and one shape set.

## The idea

Light behind the glass. Instead of a flat black sheet, three slow colour fields sit under a
near-black canvas, and every surface is hairline glass rather than a card. Message bubbles carry a
tint that belongs to the field, so the conversation is lit rather than painted.

- Canvas `#070910`; panels `#0E1220`; raised `#161C2E`; hairlines `#2A3150`
- Primary light `#7C8CFF`, with `#A78BFA` (violet) and `#48E3C0` (teal) behind the existing accent setting
- Text `#F0F3FF`, secondary `#8A93AC`, outgoing `#1B2A52`
- Corners: 24 dp panels, 19 dp bubbles with a 7 dp tail, 28 dp composer, circular controls

There is no light palette, so `DefaultTheme` no longer takes a theme mode: it always renders the dark
canvas. AMOLED Black keeps a pure black base and leaves the transparent canvas alone, so the field
still shows.

## Stored preferences

`appearance_v2` previously held `interfaceDesign` and `theme`. Neither is read any more:
`AppearanceStore` ignores both keys on load, and the next `update()` removes them. A file written by
an older build therefore cannot resurrect a removed theme, which
`AppearancePersistenceTest.legacyDesignAndThemeKeysAreIgnoredRatherThanApplied` covers.

## Implementation

| Concern | Where |
| --- | --- |
| Palette, shapes, typography, root composition | `design/DefaultTheme.kt` |
| Colour field and drift | `design/AuroraField.kt` |
| Glass material, canvas, dialog blur | `design/DesignMaterials.kt` |
| Header over the field | `design/DefaultHeader.kt` |
| Settings copy and accent labels | `feature/SettingsScreens.kt` |
| Full-screen module navigation | `navigation/VectorModuleMenu.kt` |
| Hold cue for the gesture | `navigation/gesture/GestureCue.kt` |
| Direction published for feedback | `navigation/gesture/VectorGestureController.kt` |

Removed with the other themes: `PixelShape`/`PixelShapes` and `pixelOutline`, `TerminalTypography`,
the `GlassNight`/`GlassDay`/`Terminal` palettes, `PixelGlyphs` (and the `DesignIcon` indirection),
the `designFrame(decorated = …)` dotted edging, the `InterfaceDesign` enum, `ThemeMode`, and the
per-component `pixel` branches in chats, conversation, controls, media, voice, search, music artwork,
profile and the module menu.

### The colour field

`AuroraField` draws three radial gradients once into cached layers. Drift is a `graphicsLayer`
translation driven by an infinite transition, so a moving field updates only the layer matrix: it
does not recompose and does not re-record the brush. Reduced motion, disabled animators or zero
intensity freeze the same three lights instead of falling back to flat black. A cached vertical scrim
(22% alpha at the top, opaque by 78%) keeps text readable and gives the field a horizon.

### Surfaces

`glassSurface` is Aurora-only: translucent panel fill, a top-left light sweep, a faint accent bloom
and a 0.75 dp lit border. No drop shadow, so list rows stay cheap and the design stays distinct from
the removed glass theme. Reduced transparency resolves to an opaque panel. Modal dialogs keep the
native compositor blur through `GlassDialogMaterial`; the main window never blurs.

### Full-screen module menu

The module menu is an in-window full-screen surface instead of a dialog: it covers the display,
keeps the field visible behind a translucent base, and opens no second window. Rows are 44 dp icon
chips with the active module marked by an accent wash and a dot. Search and Account moved to a row of
two actions at the bottom. Dismissal is the close control, module selection and Android Back, all
owned by `DefaultApp`; the menu itself never navigates. Its window insets come from `safeDrawing`.

Because the menu opens no second window it cannot use the window-level `blurBehindRadius`, so the
screen it covers is blurred as content instead: `DefaultApp` applies `Modifier.backdropBlur(menu)` to
the `Scaffold`, and the sheet takes its scrim from `backdropScrim()`. The two rules are pure and
tested (`canBlurBackdrop`, `backdropScrimAlpha`): the scrim is 62% only when a blur really renders
(API 31+, glass on, radius above zero), otherwise 92%, so the list is never sharp behind the rows.
Radius reuses the existing "Glass blur" steps (off/8/16/24 dp) instead of adding a second control.

`AuroraUiTest.moduleMenuCoversTheScreenAndReturnsToTheSameRoute` asserts the menu surface measures at
least 90% of the root height and that closing it returns to the nested Music route.

## Gestures

Recognition is untouched: the same distance, hold, vertical tolerance, reversal tolerance, edge
exclusion, multi-pointer and consumption rules, the same single owner, the same deadline handling.
No threshold, clock, arbitration or navigation callback changed.

Two pieces of feedback accompany the hold:

1. **Hold cue** (`AuroraGestureCue`) — while a swipe passes the activation distance, an arrow chip
   appears on that edge and a line charges over the configured hold duration, labelled Menu (right) or
   Back (left). It consumes no pointer input, sets no semantics, and lives outside the scaled surface.
2. **Surface lean** — while the hold charges the surface leans 10 dp toward the swipe direction
   (scaled by animation intensity), then returns. It is a graphics-layer translation on the node that
   already scales, so it adds no layout work.

`VectorGestureController` publishes `direction` alongside `state`, read-only for the cue.

## Not done yet

- No bespoke per-screen layouts: groups, media, music, downloads and empty states use the shared
  screen code with Aurora's tokens.
- The colour field is not rendered inside the media viewer, where a black canvas is correct.
- No device or screenshot run for the visuals; renderings in `design-concepts/previews/` are the HTML
  references, not captures of the app.

## Verification

| Check | Result |
| --- | --- |
| `:app:assembleDebug` | Passed; `app/build/outputs/apk/debug/app-debug.apk` |
| `:app:testDebugUnitTest` | 44 tests, 0 failures |
| `:core:test` | Passed |
| `:app:assembleDebugAndroidTest` | Passed (instrumentation APK compiles; not executed) |
| `:app:lintDebug` | 0 errors, 21 warnings; the same categories as before the refactor |

Log: `aurora-only-verification.log`.

Tests updated for the single theme:

- `InterfaceDesignTest` became `AuroraThemeTest`: palette and accent clamping, AMOLED base, contrast
  on every surface, gesture/motion independence, date headers.
- `AppearancePersistenceTest` drops the design/theme round-trip and instead proves legacy keys are
  ignored.
- `PixelTerminalUiTest` became `AuroraUiTest`: per-module screenshots, the full-screen menu assertion,
  draft/actions survival across accent changes, and the existing hold/release/pull-search ownership
  regression.
- `MusicUiTest`, `MusicDeviceTest`, `ChatsViewModelTest`, `TerminalTestSupport` lost their
  design/theme parameters; the music test now changes accent and glass opacity instead of the design,
  which still proves the native player and queue survive appearance changes.
- `InterfaceDesignRecreationUiTest` was deleted: it only asserted that a design choice survives
  activity recreation, and there is no choice left.
