# Ghost Mode audit and repair

This report describes the source that was actually inspected and the focused repairs. It supersedes earlier Ghost Mode claims where they differ. No live two-account visibility test was performed. The user's observed behavior cannot be conclusively attributed to a live Telegram exchange without that test; the source defects below are reproducible with fake clients.

## Component inventory

| Component | Actual source and responsibility |
| --- | --- |
| Authenticated UI | `app/src/main/java/com/vector/messenger/feature/SelectiveGhostScreen.kt`; hosted by `SettingsScreens.kt::PrivacyScreen`. Signed-out demonstration controls are a separate branch. |
| ViewModel | `feature/PreferencesViewModel.kt`: lifecycle-collected Ghost state and repository commands. No direct TDLib calls. |
| Repository | `core/src/main/kotlin/com/vector/core/ghost/SelectiveGhostRepository.kt`: account/session observation, contacts, local saves, server requests, recovery journal and external-edit conflicts. |
| Policy/rule models | `core/.../ghost/SelectiveGhost.kt`: selected-user policies, first-match evaluation, merging and capability states. |
| TDLib adapter | `app/.../telegram/TdlibGhostClient.kt`: official contact lookup, ShowStatus rule get/set, typed rule decoding and chat-action gateway. |
| Transport enforcement | `telegram/TdlibClientManager.kt::send`: existing `sendChatAction` gate immediately before JNI. Its other direct native send is the fixed startup authorization query. |
| Local preferences | `feature/SecureGhostStore.kt`: existing account-bound Keystore AES-GCM/AtomicFile storage and recovery journal. Encryption and file format are unchanged by this repair. |
| Contact selection | Official `getContacts` plus `getUser`; user IDs are used for rules, and `getChat` resolves action targets. Authenticated selection does not use demo chats. |
| Read reporting | `ConversationScreen` visibility observer → `ChatsViewModel.visible` → `SessionChatRepository.viewMessages` → `TdlibMessagingClient.view`. |

App-relative paths above are under `app/src/main/java/com/vector/messenger/`; core-relative paths are under `core/src/main/kotlin/com/vector/core/`.

## Capability matrix and repair findings

| Feature | Existing implementation | Verified root cause / gap | Repair or supported behavior | TDLib methods | Verification / physical work remaining |
| --- | --- | --- | --- | --- | --- |
| Contact selection and persistence | Real contacts, search, selected list, remove, encrypted per-account storage already existed. | Selection was functional; it did not establish a server exclusion by itself. | Retained. UI now distinguishes a requested exclusion from actual Telegram state. | `getContacts`, `getUser`, `getMe` | Existing selection, account-switch, logout and Keystore tests retained. Live contact-service checks remain manual. |
| Local typing | A policy gate existed at the shared send boundary. No normal composer typing broadcaster exists in this build. | A configured filter could be mistaken for proof of a live typing exchange being suppressed. | Kept the gate; show Enabled/Disabled/Applying/Failed for the saved local policy, with honest broadcast limitations. No new presence broadcasts were added. | `sendChatAction` | Fake-client action filtering verified. Peer observation still required; current build has no routine typing emitter. |
| Local recording | Recording and voice/video-note activity types were filtered; there is no recorder or microphone permission. | `chatActionUploadingVideo` was missing, and toggles did not distinguish a filter from an implemented recorder. | Added video-upload activity filtering. Actual file upload/message APIs are untouched. UI separately labels recorder support Unsupported. Cancel remains allowed. | `sendChatAction` | Fake typing/recording/upload-action coverage; document-action and group activity remain allowed. A recorder UI cannot be tested because it is absent. |
| Per-contact Last Seen & Online | Official get/set APIs and an explicit Apply action already existed. | The local master switch also masked server intent. Verification stopped at unrelated allow rules, accepted only a narrow rule layout, and did not verify preservation of unrelated explicit exceptions after readback. | Server requests are independent of the local switch. Typed allow/deny user rules, selected-only merging, per-user first-match evaluation and readback checks cover normalized rule layouts. Other users' exceptions and broad-rule effects are verified. | `getUserPrivacySettingRules`, `setUserPrivacySettingRules`, `userPrivacySettingShowStatus`, `userPrivacySettingRuleRestrictUsers` | Multiple selected users, existing exceptions, normalization, limits and lost-exception rejection verified with fakes. Two live accounts still required. |
| External-client changes and errors | Updates, snapshot conflict checks and a recovery journal existed. | A stale refresh could replace a newer update; an error could be cleared by a local save and stale state could look confirmed. Contact membership changes could leave the effective-rule display stale. | Added refresh revisions, separate server errors, operation states, contact-dependent re-evaluation and per-contact server capability state. Failed confirmation remains Failed until a successful check. | `updateUserPrivacySettingRules`, `updateUser`, rule get/set | Stale-refresh, sticky failure, contact membership, interrupted write, external conflict, switching and logout tests. Live concurrent-client behavior remains manual. |
| Read reporting | Visible messages are reported normally; live `markRead` is a no-op. | There was no selective receipt-hiding or deferred-read feature. `force_read=false` still permits reads in an open chat. | Normal reporting stays Enabled. Hiding all read receipts is explicitly Unsupported. No fake toggle or unimplemented read mode was added. | `viewMessages`, `openChat`, `closeChat`; incoming `updateChatReadInbox`/`updateChatReadOutbox` | Read-path regression added; messaging production files unchanged. Real receipt visibility needs a second account. |
| Premium / universal invisibility | Limitations were already described. | A universal enabled-looking switch conflated local intent and server behavior. | Local switch is labeled **Enable local suppression**. Server exclusions and normal reads are separate. Complete invisibility remains unsupported. | Ordered ShowStatus privacy rules | Explicit denial before Premium-allow rules is tested. Server activity exceptions need live observation. |

## Online-rule repair details

1. Changing a per-contact requested exclusion saves local intent. **Apply privacy changes** reads the current ShowStatus rules and computes a selected-user change; it does not replace the setting with only one user's restriction.
2. Existing rules are retained as the baseline. Selected IDs are moved out of existing explicit allow/deny lists into the leading selected denial, preventing duplicate entries from exhausting the 1,000-user limit. Unselected IDs, their rule order and unknown rule contents remain in the proposal. The baseline is retained for restoration.
3. Readback evaluates each selected contact against the actual returned rules. Unrelated allow-user entries are skipped rather than stopping verification. Known contact/Premium predicates are evaluated from actual contact metadata. Unknown group-membership/future-rule predicates remain Unsupported when they prevent proof.
4. Harmless known-rule normalization can be accepted only when selected exclusions are effective, all unrelated explicit user IDs remain, and unrelated/default rule effects are preserved. Unknown-rule normalization is checked conservatively. Missing unrelated exceptions cause Failed/conflict state instead of Enabled.
5. The native applied snapshot is journaled. Later snapshot differences from another client stop automatic restoration. Keep current server rules retains existing server exceptions and abandons uncertain ownership; it does not silently remove exclusions.
6. Last Seen/Online intent no longer depends on the local indicator master switch. Turning local suppression off does not change Telegram rules. Clear a requested exclusion or remove the selected user, then Apply, to restore managed server rules.

This API replaces a whole privacy setting and has no expected-version/compare-and-set parameter. A concurrent edit between the last check and write cannot be made atomic by Default. Pre-write checks, update observation and readback reduce risk; cross-client transaction isolation is not claimed.

## Read-receipt investigation

| Location | Actual behavior | Deferral decision |
| --- | --- | --- |
| Chat row → `ChatsViewModel.open` → `markRead` | Only the demonstration repository changes its local counter here; live mode does not send a read request. | Already safe; unchanged. |
| Conversation lifecycle start | Calls `openChat` and loads a bounded history page. | Preserve opening: TDLib uses it for updates, particularly groups/channels. Do not fake a closed chat while rendering it. |
| History retrieval | `getChatHistory` fetches messages; it does not call `viewMessages` in this code. | Paging remains independent of read reporting. |
| Visible-message observer | Reports loaded IDs actually present in the viewport. | Reporting is naturally deferred until visibility. Additional Ghost delays while messages are visible would alter read/unread/view-counter behavior; no such mode is implemented or advertised. |
| Repository `viewMessages` | Deduplicates visible IDs; only acts for the active chat. Adapter uses `messageSourceChatHistory` and `force_read=false`. | `false` is not a receipt-hiding flag. Preserve normal reporting. |
| Conversation lifecycle stop | Calls `closeChat`, cancels history work and clears the local active window. No new read flush is issued. | Preserve closing and subscription cleanup. |
| Read synchronization | Inbox/outbox updates change real counters and delivery indicators. | Preserve authoritative incoming state. Marking unread later cannot undo a receipt already sent. |

No per-contact hide-all-receipts guarantee is provided by the APIs used here. This repair changes only ShowStatus privacy rules, not other privacy categories. Sending/reading/typing can still trigger documented temporary online visibility. Other Telegram clients remain independent.

## Safety and source scope

The original architecture is retained. Changed production files are limited to `SelectiveGhost.kt`, `SelectiveGhostRepository.kt`, `TdlibGhostClient.kt` and `SelectiveGhostScreen.kt`. The Preferences ViewModel, secure store and shared transport gate were audited and reused. No client was duplicated.

The pre-edit protected-file hash inventory is in `app/build/ghost-audit-protected.json`. Authentication, key vault, existing messaging production code, conversation visibility code, navigation, Search, Music and Gradle configuration remain unchanged. No live account privacy write is performed by the automated tests. Demo-only interaction tests skip when the Samsung is signed in, protecting real contacts/messages.

## Tests and build

Final command (Samsung transport selected using `ANDROID_SERIAL`):

```powershell
.\gradlew.bat :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:connectedDebugAndroidTest :app:compileReleaseKotlin --max-workers=1 --console=plain
```

**BUILD SUCCESSFUL** in 2m 45s. **83 JVM tests and 76 Samsung SM-S9310 / Android 16 instrumentation tests passed**, zero failures, errors or skips in the final run. Debug assembly and release Kotlin compilation passed. Lint: zero errors, 12 existing dependency/tool notices. `git diff --check` passed. Output: `ghost-audit-final.log`.

An intermediate device run skipped 38 demo-only tests when the existing signed-in-account guard was active; those skips were not reported as passes. The final run completed all 76 tests without skips. The agent did not perform a live privacy change or force a logout to run tests. The original 300000 ms phone timeout was restored, and temporary instrumentation was removed. All protected production hashes matched the pre-edit snapshot.

The repaired Ghost suite contains 17 JVM tests, including the existing selection/persistence/session tests. Android tests cover Keystore persistence (2), privacy mapping (2) and Ghost UI states/selection (3); messaging UI and read-reporting regressions also passed. New/repaired regressions cover:

- merging multiple selected users and preserving existing/unrelated exceptions;
- normalized readback, contact/Premium predicates and unknown rules;
- no Enabled status from an unverified/stale server state;
- local/server independence, server errors, stale refreshes and contact membership changes;
- recording/video-upload action suppression without blocking unrelated action types;
- normal read reporting only after visibility, no optimistic read counter and no read on closed chats;
- existing encrypted persistence, account switching, logout and conflict recovery.

Changed test files: `core/src/test/kotlin/com/vector/core/SelectiveGhostTest.kt`, `core/src/test/kotlin/com/vector/core/MessagingRepositoryTest.kt`, `app/src/androidTest/java/com/vector/messenger/GhostPrivacyMappingTest.kt`, and `app/src/androidTest/java/com/vector/messenger/SelectiveGhostUiTest.kt`. The existing secure-store tests were rerun unchanged.

### Remaining physical/service verification

Fake-client UI tests and actual Android Keystore tests do not prove Telegram visibility. With two accounts you control, verify selected and unselected observers, Native rule confirmation, restored exceptions, external-client edits, offline errors and online activity exceptions. Recording UI tests remain Unsupported until a recorder exists. No complete invisibility or hidden-all-receipts guarantee is made.

## Official references

- [ShowStatus rule updates](https://core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1set_user_privacy_setting_rules.html)
- [TDLib reviewed schema and ordered rule semantics](https://github.com/tdlib/td/blob/c8828d50f5eac2fc7089ce1089d7ce9095c61b5e/td/generate/scheme/td_api.tl)
- [viewMessages](https://core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1view_messages.html)
- [openChat](https://core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1open_chat.html), [closeChat](https://core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1close_chat.html)
- [Telegram online-visibility exceptions](https://telegram.org/faq#q-who-can-see-me-39online-39)
