# Essential Telegram settings

## Simple modular presentation

The November 28 visual redesign keeps the repositories, TDLib requests, preference keys and account-operation safeguards above intact. Settings home now has the existing Telegram profile row, four material tiles (Appearance, Privacy, Sounds, Alerts), and five secondary rows (Chats, Calls, Devices, Storage, Music). The Settings-only Back button uses existing module history; global navigation and gestures retain their handlers.

Appearance retains interface, AMOLED, accent, type size and density controls, with Motion & Gestures as a subsection. Sounds owns message/interface effects, volume and call sounds/ringtone; Calls links to the same sound controls. Haptics has one canonical control under Motion & Gestures, also reachable from Sounds. Chats keeps cached-circle autoplay and links to the same automatic-download screen as Storage. Alerts retains device preferences and Android channel links, adding direct routes to the existing private/group/channel notification scopes. No notification channel IDs change.

Privacy uses clock, phone, profile, forward, group, receiver, block, key, lock and device vectors instead of repeated eye icons. Audience rows expose radio semantics; user/group exceptions open compact lists with the same confirmation and repository edits. Device rows open details before offering termination. Download limits open small selectors. Storage shows measured Telegram media/database usage, with a proportional usage bar. Longer explanations are disclosed on demand.

`SettingsVisuals.kt` adds only code-native vectors and theme-aware presentation helpers. Tile compression is 0.98, switches use existing 180 ms motion, and segmented selectors move their indicator in 200 ms (all honoring motion preferences). Touch targets remain at least 48 dp; tiles can grow with text. Search keeps historical terms such as notifications, messaging, typography, peer-to-peer calls and active sessions while pointing at the renamed canonical screens. Navigation shortcuts are excluded from the index when the actual preference is already indexed.

The redesign compiled with `:app:assembleDebug` after correcting a missing shape import, and was installed with replacement installation. No unrelated test suite or native call rebuild was run. Device visual/theme checks are pending because Android's device lock covers Default. No theme or account settings were changed during this verification attempt. Live account mutations and calling behavior are not revalidated by this UI build.

## Integration

The existing Settings catalog, category routes, rows, dialogs and theme tokens remain in use. The account row continues to open the existing profile editor (name, username, photo, bio and account details). Privacy & Security now groups account privacy, security and Ghost Mode. Devices is a dedicated module. Search indexes the new implemented routes. Existing local notification preferences remain additional device controls, with separate synced Telegram defaults.

`TelegramSettingsRepository` uses the existing `TdlibClientManager.messagingRequest`, pinned TDLib 1.8.70 schema at `.native/td/td/generate/scheme/td_api.tl`. Every RPC carries an expected account ID; the existing manager also checks the TDLib session epoch. No credentials, database locations, authorization flow, push registration, Telecom or native call code were changed.

| Feature | TDLib APIs |
| --- | --- |
| Blocked accounts | `getBlockedMessageSenders` with `blockListMain`, `setMessageSenderBlockList` with user/chat senders; null block list unblocks; `getContacts`, `getUser`, `getChat` |
| Privacy | `getUserPrivacySettingRules`, `setUserPrivacySettingRules`; show status, phone visibility, phone discovery, profile photo, invites, calls, peer-to-peer calls, forwarded links |
| Devices | `getActiveSessions`, `terminateSession`, `terminateAllOtherSessions` |
| Two-step verification | `getPasswordState`, `setPassword`, `setRecoveryEmailAddress`, `checkRecoveryEmailAddressCode`, `resendRecoveryEmailAddressCode`, `cancelRecoveryEmailAddressVerification`, `requestPasswordRecovery`, `recoverPassword` |
| Notification defaults | `getScopeNotificationSettings`, `setScopeNotificationSettings` for private/group/channel scopes; existing Android channel settings for device sound/vibration |
| Downloads | `getAutoDownloadSettingsPresets`, `setAutoDownloadSettings` for mobile/Wi-Fi/roaming; preserve unrelated fields |
| Storage | Existing `getStorageStatisticsFast`; `optimizeStorage` restricted to `fileTypeThumbnail`, one-hour new-file immunity |

Changes are displayed only after Telegram replies and values are fetched again. Privacy and scope edits first fetch and compare the displayed snapshot. Unknown rules and ordered user/group exceptions survive general audience changes. TDLib's implicit final deny rule is normalized for confirmation comparison. Opposing exceptions require explicit removal. Phone discovery has only Everybody/My Contacts, without an unsupported Nobody/exceptions editor.

Screens refresh on entry, resume and every 30 seconds while resumed. This includes changes from other Telegram clients. Telegram does not offer atomic compare-and-set for these settings: another client can still race the interval between the preflight read and write. Failed/uncertain requests show an error and allow refresh, without optimistic success.

Blocked lists paginate in batches of 50; search covers loaded entries. The existing avatar loader and contact selector resolve actual Telegram accounts. Group exception additions use groups in the loaded conversation list; all existing server group exceptions are retained and can be removed. Device termination rereads sessions immediately before submission and rejects the current session.

## Local security

- Passcode lock is local to Default; disabled until enabled explicitly.
- A random salt and PBKDF2-HMAC-SHA256 verifier (210,000 iterations) are encrypted using a non-exportable AES-GCM Android Keystore key. No plaintext passcode is stored. Comparisons use `MessageDigest.isEqual`.
- A persisted attempt counter adds escalating delays after five wrong attempts. Changing/disabling protection or changing auto-lock/biometrics requires the current passcode.
- A cold process starts locked. Leaving the Activity starts the configured interval; reopening checks elapsed time. Notification navigation does not compose chats/settings until unlocked. Locked Back moves the task into the background.
- Strong biometric unlock requires a per-use authenticated Keystore cipher and a successful crypto operation. Enrollment/key invalidation falls back to passcode. Credentials use transient, masked, non-saveable inputs, cleared on backgrounding; security windows use `FLAG_SECURE`.
- Call controls remain composed over the lock gate. Minimizing a call reveals the passcode gate. Neither chats nor profile/account overlays are composed there.
- Keystore failure fails closed. There is deliberately no passcode recovery bypass. Android backups were already disabled and remain disabled.
- Telegram password/code inputs are transient and never written to saved state, preferences or logs. Raw server errors are not exposed because they can contain supplied input. Disable-password, recovery-email removal/cancellation and device termination require confirmation.

## Files changed

Paths are relative to the project root.

| File | Change |
| --- | --- |
| `app/src/main/java/com/vector/messenger/feature/TelegramSettingsRepository.kt` | Account-bound settings RPCs, rule merging, confirmed state, download policy checks |
| `app/src/main/java/com/vector/messenger/feature/TelegramSettingsScreens.kt` | Compact account-settings pages, confirmations, protected inputs and route registry |
| `app/src/main/java/com/vector/messenger/feature/AppPasscode.kt` | Keystore-backed local lock, biometric authorization, lock/settings UI |
| `app/src/main/java/com/vector/messenger/feature/SettingsWorkspace.kt` | Existing catalog, navigation, Devices module and search integration |
| `app/src/main/java/com/vector/messenger/feature/PreferencesViewModel.kt` | Inject settings and local-lock repositories |
| `app/src/main/java/com/vector/messenger/MainActivity.kt` | Activity lifecycle lock enforcement and protected composition gate |
| `app/src/main/AndroidManifest.xml` | Biometric permission |
| `app/src/main/java/com/vector/messenger/feature/ChatScreens.kt` | Correct real-contact/group labels in reused contact selector |
| `app/src/main/java/com/vector/messenger/feature/media/MediaViewModel.kt` | Account-policy check before automatic visible-message downloads |
| `app/src/main/java/com/vector/messenger/feature/media/MediaAttachmentView.kt` | Invoke automatic policy for eligible visible originals |
| `app/src/androidTest/java/com/vector/messenger/feature/TelegramSettingsSecurityTest.kt` | Six isolated rule/security tests |
| `scripts/settings-security.init.gradle` | Explicitly select this instrumentation fixture |
| `docs/TELEGRAM_SETTINGS.md` | Integration report and verification checklist |

## Storage and download scope

Thumbnail cleanup delegates to TDLib's typed cache optimizer. It does not recursively delete app directories, databases, keys, original files or user exports. Encrypted thumbnails remain protected by TDLib. Existing real cache/media/database accounting is reused.

TDLib's low/medium/high account settings map to roaming/mobile/Wi-Fi. The visible-message media loader checks the confirmed policy, network and known original size before an automatic transfer. Unknown policies/sizes fail closed; metered Wi-Fi uses mobile limits, unknown/VPN transport uses roaming limits. Manual downloads, viewer requests and small preview thumbnails retain existing behavior. Transfers already started are not canceled when a network changes. Server policies are cached for at most 30 seconds and refreshed after edits.

## Deliberate omissions

- Chat folder management was deferred, as the request permits, to keep focus on essential privacy/security. No inert folder controls or search results were added.
- Public fallback profile-photo editing was not added. The existing photo editor operates on the account's personal photo; a separate verified public-photo flow is needed before exposing that control.
- Password reset without access to the recovery email and pending-reset cancellation are not exposed. A pending reset is identified and directs the user to an established Telegram client; email-code password recovery is implemented.
- Android groups/channels continue to share the existing groups sound channel. Server notification scopes are distinct. No channels are modified without the user opening Android settings.

## Verification

Recorded November 28, 2026: the debug APK and isolated instrumentation APK built successfully and were installed with `adb install -r` (no app-data reset). All six isolated instrumentation tests passed on the connected Samsung device: `OK (6 tests)`. The first combined build encountered the pre-existing Aurora fixture constructor errors; the explicit source filter was corrected and the combined build completed. No native calling rebuild or unrelated test suite ran.

After the user unlocked the device, read-only checks confirmed that Privacy & Security navigation works; the blocked list loaded all 14 server accounts with real names/avatars; Last Seen & Online loaded its current audience; Devices displayed this device and other sessions with actual activity information; Two-Step Verification loaded the account's enabled state; private/group/channel notification defaults loaded; and automatic media settings loaded actual per-network byte limits. Settings Search successfully opened Devices, Two-Step Verification, Telegram Notifications and Automatic Media Downloads. The blocked screen was visually inspected in the current Aurora theme. No account settings were changed. Live Telegram mutations, full theme coverage, biometric unlock, and call/notification behavior with the lock remain unverified.

Build app and only the isolated settings instrumentation fixture:

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest -I scripts/settings-security.init.gradle
```

The opt-in init script selects the new fixture because older unrelated UI fixtures still construct an obsolete PreferencesViewModel. It does not change normal production builds or run those suites. The fixture uses synthetic privacy JSON and unique test-only preferences/Keystore aliases. It never calls Telegram, changes account passwords or terminates devices.

Device runner:

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class com.vector.messenger.feature.TelegramSettingsSecurityTest com.vector.messenger.test/androidx.test.runner.AndroidJUnitRunner
```

The tests cover preservation of user/group/opaque rules, TDLib deny-rule normalization, opposing-exception conflict detection, precise exception removal, group exception ordering, real Keystore verifier creation, wrong-code rejection, cold-instance locking, immediate background locking, authorization for disable, and absence of plaintext in preferences.

### User-controlled account/device checklist

Use a consenting peer/test account for changes; security mutations are not automatically exercised on the user's account.

1. Block a contact, confirm the list/avatar and Telegram's list match; search/load more; unblock and compare on another Telegram client. Cancel each confirmation once.
2. Record current privacy rules. Change an audience, confirm existing user/group/Premium/bot exceptions survive in another client, add/remove one test exception, and restore the original state. Test phone discovery separately. Change a value on the other client and resume Default.
3. Compare Devices against Telegram. Cancel a termination. With an expendable secondary login, terminate it and confirm sign-out. Verify this device has no termination action. Test Telegram's fresh-session restriction if applicable.
4. On a test account, enable/change the Telegram password, confirm recovery email, resend/reject an incorrect code, recover by email, and disable. Confirm other-client state after each successful operation. Never share passwords/codes in diagnostics.
5. Enable a local passcode, try a wrong code, unlock, change it, test all auto-lock intervals, process restart, rotation, notification navigation, Recents protection, biometric unlock and fallback. Disable only with the current code. While locked, receive/answer/minimize/end a real call; check the gate, speech and proximity.
6. Compare private/group/channel mute and preview defaults in another Telegram client. Preserve an individual chat override. Check denied Android permission, sound-channel links, notification delivery while backgrounded/locked, and existing call notifications.
7. Check actual storage accounting. Confirm thumbnail cleanup can reduce eligible cache while retaining originals, drafts, authentication and exported files. Exercise mobile/Wi-Fi/roaming limits with known-size test files, disabled downloads, manual override and an unknown-size file.
8. Check Settings/Search/back navigation, long names, keyboard scrolling and dialogs in Console, Aurora Glass and AMOLED. Open the existing account editor from the home account row.

Compilation and isolated security tests alone do not verify server account operations, biometric enrollment, incoming calls or notification delivery. Record those as pending until performed on a real account/device.
