# Selective Ghost Mode

The later [Ghost Mode audit and repair](GHOST_MODE_AUDIT.md) supersedes the original implementation details and test totals below. Local suppression and server exclusions are now independent, with per-contact verified capability states.

Ghost Mode now has an authenticated, real-contact interface in its existing destination. It supports contact search, selection/removal, a master enable switch, per-contact typing/recording policies, and combined exact Last Seen & Online exclusions. Signed-out demonstration controls remain explicitly labeled and separate. Authenticated contact selection uses official TDLib `getContacts`/`getUser` results, not demo conversations or guessed chat IDs.

## Privacy guarantees and limits

| Control | Scope and behavior |
| --- | --- |
| Typing suppression | Blocks applicable new outgoing direct-chat activity requests from Default to a selected user while enabled. |
| Recording suppression | Blocks recording-video, recording-voice-note, recording-video-note and voice/video-note upload actions on the same direct-chat policy. |
| Cancel action | Always permitted, so an existing activity indicator can be cleared. |
| Group/channel activity | Cannot selectively hide a group action from one member; local group actions remain unaffected. |
| Last Seen & Online | One combined Telegram privacy setting. Apply adds selected-user exclusions at the front of the ordered rules. These are account-wide server rules and affect other clients as well. |
| Messages, reads, synchronization, notifications | Existing behavior remains unchanged. Reading and sending can still briefly expose online activity. |
| Premium | An earlier explicit per-user denial takes precedence over existing Premium-allow rules under TDLib's first-match rule ordering. Premium does not remove the normal online-visibility exceptions. |

**This is not complete invisibility.** Approximate Last Seen categories may remain visible. Telegram documents temporary online exposure after sending messages, reading a direct message or broadcasting typing, including relevant shared-group activity. Default does not suppress reads, interfere with other clients, force an offline status or bypass server rules.

### Current activity-path audit

The subsequent [voice/audio integration](VOICE_AUDIO.md) now emits recording-voice-note actions only after actual capture starts, through this same policy-controlled gateway. It cancels the action on stop and never suppresses the actual voice-message upload operation. No routine typing or video-recorder broadcasts were added. These local controls affect Default's emitted activity, not other clients or Telegram's server-side visibility exceptions.

There are two direct native send call sites: startup's fixed `getAuthorizationState` request and the manager's correlated `send` method. Every `sendChatAction` through the latter is checked immediately before JNI. `getChat`/`updateNewChat` resolve a private chat to its actual user ID; groups/channels are distinguished. Unresolved private targets and unreadable preference state fail closed for indicators. Other message/authentication requests bypass this narrow activity filter.

## Local controls and server changes

Local changes are saved before their active state is published. The local suppression switch affects only Default's action policy. **Apply privacy changes** is the explicit account-wide write action. Turning local suppression off does not change Telegram privacy. Clear a requested exclusion or remove a selected contact, then Apply to restore managed server rules.

Before writing, the repository fetches current `userPrivacySettingShowStatus` rules. It preserves their order and contents, including unrelated user/chat exceptions and unknown rule fields. A selected-user denial is prepended only when needed. Telegram's user-exception limit is checked before a write. Unchanged proposals do not cause another server write. Privacy operations are serialized independently of message and authentication work.

An encrypted journal records the original baseline, last confirmed applied rules, requested IDs and a pending attempt before a server write. A readback confirms the result; interrupted/uncertain operations are reconciled on refresh or session restoration. If the current rules differ from the last managed snapshot, automatic restoration pauses for review. **Keep current server rules** retains all current server exceptions and abandons ownership assumptions. Earlier Ghost exclusions may remain as existing server rules and can be reviewed in an official Telegram client's Last Seen & Online settings.

TDLib replaces this privacy setting as a whole and exposes no expected-version/compare-and-set parameter. A simultaneous change by another client between the final read/check and the write cannot be atomically prevented. Incoming updates, pre-write comparison and readback reduce this risk; the implementation does not claim cross-client transaction isolation. Avoid concurrent edits during an Apply operation.

Logging out clears active in-memory policy/contact state and cancels session work. It does not restore server privacy rules. Saved local settings remain encrypted and account-bound for a subsequent login to the same Telegram account. Another account loads a separate file and starts without the first account's selected contacts.

## Storage and architecture

- `SelectiveGhostRepository` extends the existing Ghost repository contract; the existing Preferences ViewModel and Ghost destination host it. Navigation and gesture routing are unchanged.
- `TdlibGhostClient` uses the original singleton client manager and official requests/updates. No new TDLib client, networking stack or receive loop was added.
- Current contacts and privacy-rule updates use a separate typed observation channel. Contact updates are cached by ID and published in batches; incremental changes win over an older in-flight contact snapshot.
- Local preferences and the recovery journal use Android Keystore AES-GCM protection in atomic, account-specific files under `noBackupFilesDir/ghost`. Filenames hash the account ID; authenticated encryption also binds each file to that account ID. Contact names, photos, passwords and authentication codes are not persisted here.
- This is a separate local preference key. The existing TDLib key vault, database encryption and authentication/storage lifecycle are untouched. No Telegram cryptography was implemented or changed.
- A missing key or corrupt existing file is reported, not silently replaced. UI status does not claim server success after an error. No contact, rule contents, credentials or raw server errors are logged.

## Official references

- [Telegram Last Seen/Online FAQ](https://telegram.org/faq#q-who-can-see-me-39online-39) — shared visibility rules and activity exceptions.
- [TDLib setUserPrivacySettingRules](https://core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1set_user_privacy_setting_rules.html) — account privacy updates.
- [TDLib sendChatAction](https://core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1send_chat_action.html) — activity/cancel notifications.
- [Reviewed TDLib schema](https://github.com/tdlib/td/blob/c8828d50f5eac2fc7089ce1089d7ce9095c61b5e/td/generate/scheme/td_api.tl) — first-match ordered rules, user exceptions, contact retrieval and privacy updates. The precedence statement above follows that documented ordering.

## Changed files in this phase

- `core/src/main/kotlin/com/vector/core/ghost/SelectiveGhost.kt` (new): contracts, account policy, ordered privacy-rule composition.
- `core/src/main/kotlin/com/vector/core/ghost/SelectiveGhostRepository.kt` (new): session state, persistence, error/conflict/recovery handling.
- `core/src/main/kotlin/com/vector/core/Repositories.kt`: backward-compatible Ghost repository methods/state.
- `core/src/main/kotlin/com/vector/core/demo/DemoRepositories.kt`: rename its private Ghost preference field to avoid an interface-name collision; demo behavior is unchanged.
- `app/src/main/java/com/vector/messenger/telegram/TdlibGhostClient.kt` (new): official contacts/privacy/action adapter and lossless rule mapping.
- `app/src/main/java/com/vector/messenger/telegram/TdlibClientManager.kt`: narrow activity gate, target resolution and privacy-update observation.
- `app/src/main/java/com/vector/messenger/di/RepositoryModule.kt`: shared-manager Ghost binding.
- `app/src/main/java/com/vector/messenger/feature/SecureGhostStore.kt` (new): separate encrypted local preference storage.
- `app/src/main/java/com/vector/messenger/feature/PreferencesViewModel.kt`: Ghost state and actions.
- `app/src/main/java/com/vector/messenger/feature/SelectiveGhostScreen.kt` (new): compact authenticated UI.
- `app/src/main/java/com/vector/messenger/feature/SettingsScreens.kt`: authenticated host branch and backward-compatible enabled-toggle overload.
- Tests: `SelectiveGhostTest`, `GhostPersistenceTest`, `GhostPrivacyMappingTest`, `SelectiveGhostUiTest` (new).
- This report. Previous working-tree changes are preserved.

## Verification

Final command (Samsung transport selected with `ANDROID_SERIAL`):

```powershell
.\gradlew.bat :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:connectedDebugAndroidTest :app:compileReleaseKotlin --max-workers=1 --console=plain
```

**BUILD SUCCESSFUL** in 2m 41s. **75 JVM tests and 75 Samsung SM-S9310 / Android 16 tests passed**, zero failures or skips. Debug assembly and release Kotlin compilation passed. Lint: zero errors, 12 existing dependency/tool notices. `git diff --check` passed. Output: `ghost-final-checks.log`.

New tests cover contact selection/search/removal, account-scoped persistence and logout, supported typing/recording action policies, ordered/unknown privacy-rule preservation, user-exception limits, duplicate-request prevention, server errors and uncertain-write reconciliation, external-edit conflicts, and incremental contacts winning over an in-flight snapshot. Device tests verify real Keystore encryption, account-AAD binding, missing-key handling, and the compact UI. Existing authentication, messaging, profile, Appearance, navigation/gesture and Search tests also passed.

Automated privacy/action tests use fake TDLib clients and synthetic identities only. Device storage tests use separate test aliases/directories and remove them afterward. No automated test modifies a live Telegram account's privacy rules. The Samsung's original 300000 ms timeout was restored; temporary test instrumentation was removed. Gradle configuration, manifest, TDLib key vault, authentication repository/mapping, navigation, Search gestures, Music and existing messaging implementation files were not changed in this phase.

**Two-account visibility has not been manually verified.** Functional server integration and documented behavior are not a live-service visibility guarantee. Notifications/background delivery were not measured against a live account.

Remaining manual checks with two accounts:

1. Sign in to Default and use a second account as an observer. Record the first account's existing Last Seen & Online rules and other-client settings.
2. Select the observer contact, request its exact-status exclusion, then Apply. Confirm the exception in an official client and check the observer's normal Last Seen/Online view. Local suppression is an independent control.
3. Send/read messages and check Telegram's brief online exceptions. If testing Premium, confirm explicit exclusions and activity exceptions separately.
4. Start voice recording with the observer selected/unselected and compare recording visibility from a second account. Local microphone/gesture and fake-policy tests exist; live peer visibility still requires that second-account check. Other clients' broadcasts remain outside Default's control.
5. Clear the observer's requested exclusion or remove the observer and Apply. Confirm original unrelated exceptions remain. Change rules from another client and verify conflict review before restoration.
6. Exercise an offline Apply, refresh/recovery, restart, logout and a different-account login. Confirm correct pending/error states and account separation; restore the test accounts' intended privacy rules afterward.
