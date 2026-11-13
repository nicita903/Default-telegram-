# Default visual redesign — Stages A and B

Implemented on 13 November 2026. This checkpoint covers the design foundation, Chats, and conversation presentation. The application name remains **Default**.

## Completed work

**Stage A** establishes centralized night/day colors, typography, spacing, small corner radii, keylines, and custom controls. Night colors use base `#05070A`, panel `#111820`, raised `#1D2936`, accent `#75B8FF`, primary text `#EFF4FA`, and secondary text `#8291A2`. Foundation-based buttons, text input, popups, dialogs, and a compact header replace the standard Material appearance in the redesigned screens. MaterialTheme remains a compatibility adapter for existing destinations. Controls have press feedback and keyboard focus frames; existing motion specifications remain shared.

**Stage B** introduces an editorial conversation heading, rectangular search and compose action, folder underlines, precise conversation rows, circular avatars, outlined unread counters, pin indicators, and a selection rail. Conversations use restrained chamfered message surfaces, reply/file strips, reaction controls, and a fixed composer rail. Stable list keys, drafts, attachments, selection, sending, reply and reaction behavior remain connected to the existing ViewModel/repository.

Demo data remains explicitly labeled. The redesign does not claim that sample messages are synchronized, sent to Telegram, or remotely read.

## Screenshots

Captured from the Compose surface on the connected Samsung SM-S9310 running Android 16, using sample conversations:

- [Chats](screenshots/redesign-ab/chats.png)
- [Conversation](screenshots/redesign-ab/conversation.png)
- [Reply](screenshots/redesign-ab/reply.png)

## Changed files

Paths below are relative to the repository root.

| File | Change |
| --- | --- |
| `app/src/main/java/com/vector/messenger/design/DefaultTheme.kt` | Centralized tokens, typography, compatibility theme |
| `app/src/main/java/com/vector/messenger/design/DefaultControls.kt` | New reusable custom controls, input, dialog, popup and rule |
| `app/src/main/java/com/vector/messenger/design/DefaultHeader.kt` | New compact presentation component |
| `app/src/main/java/com/vector/messenger/design/ChatComponents.kt` | Circular avatar treatment |
| `app/src/main/java/com/vector/messenger/feature/ChatScreens.kt` | Chats, contact picker and row presentation |
| `app/src/main/java/com/vector/messenger/feature/ConversationScreen.kt` | Message surfaces, contextual controls and composer presentation |
| `app/src/main/java/com/vector/messenger/navigation/DefaultApp.kt` | Header presentation; existing controller and navigation callbacks retained |
| `app/src/androidTest/java/com/vector/messenger/RedesignUiTest.kt` | Search, contact picker, pin and archive regression coverage |
| `app/src/androidTest/java/com/vector/messenger/DesignScreenshots.kt` | Wait for keyboard closure before conversation captures |
| `app/src/androidTest/java/com/vector/messenger/FoundationUiTest.kt` | Use stable full-height conversation capture |
| `app/src/androidTest/java/com/vector/messenger/NavigationGestureUiTest.kt` | Assert reply/header visibility and capture after keyboard closure |
| `docs/REDESIGN_AB.md` and `docs/screenshots/redesign-ab/` | Checkpoint report and device captures |

The pre-existing edit to `docs/TDLIB_AUTH.md` was left untouched.

## Preserved behavior and security

Module controllers/history, gesture recognizer, authentication screen/ViewModel, TDLib integration, encryption/session storage, manifest, API configuration and Gradle configuration were not edited. Protected source/configuration hashes were checked against a baseline taken before this work. The header retains the existing dedicated gesture surface and accessible navigation button. Message rows and the composer retain gesture exclusion regions; no permanent bottom navigation bar was introduced.

No credentials, passwords, verification codes or account data were added to screenshots or documentation. Authentication integration remains unchanged; successful live Telegram authentication was not tested or claimed in this checkpoint.

## Verification

- Stage A: `:app:assembleDebug` passed before Stage B began.
- Stage B: `:app:assembleDebug` passed.
- `:core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest` passed: 28 JVM tests, no failures. Lint: zero errors; 12 existing SDK/tool/dependency notices.
- Samsung device suite: 20 passed, one skipped. Coverage includes module destinations/history, both existing hold gestures and cancellation, scrolling exclusions, Android Back, retained module scroll/drafts, reply/reactions, search/contact selection, context actions, secure Account UI and isolated Keystore behavior.
- The native TDLib smoke test intentionally skips when developer API configuration is present, preventing its isolated client from competing with the configured app receiver. Its guard was preserved.
- Custom interactive controls provide minimum 48 dp targets, labels/roles, selected folder semantics and visible focus borders. Token contrast checks: secondary text on panel 5.55:1, secondary on raised surface 4.58:1, primary on panel 16.16:1; day secondary on panel 5.53:1.

Screenshots were inspected for full-height layout and keyboard closure. Automated tests are evidence for the exercised interactions on this Samsung, not a guarantee across all devices. Full TalkBack review, large-font/device-matrix checks, predictive-back edge behavior and frame-time/battery profiling remain manual follow-up. No 120 Hz smoothness or measured performance improvement is claimed.

## Later stages

Stages C–G are outside this checkpoint: redesigned module menu/coordinated transitions, dedicated Chat/Music Search screens, fast search switching and automatic/manual lock, persistent appearance settings, and broad animation/accessibility optimization. Existing inline chat search and the current module menu remain functional. The new search gestures and tiny-lock behavior have not been implemented or claimed as verified.
