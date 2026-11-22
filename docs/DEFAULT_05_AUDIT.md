# Default 0.5 — functionality audit

Audit date: 2026-11-15. Scope: the current working tree, including the existing, uncommitted Ghost Mode repair. This is an audit, not a feature implementation. No production code, credentials, dependency versions, package identifiers or interface designs were changed.

**Readiness: not ready to replace a daily-use Telegram client.** Real TDLib adapters and substantial tested state management exist. Blocking gaps include receive-loop recovery, a history/update race, uncertain-send recovery, notifications/background delivery, incomplete contacts/search, and media support. Successful compilation does not prove successful Telegram authentication, message delivery or selective privacy visibility.

## Evidence and status conventions

- **Implemented:** the stated, bounded capability has source implementation and automated coverage. This does not certify its live Telegram integration.
- **Partial:** essential behavior is missing or a concrete source defect remains.
- **Placeholder:** a screen or demonstration behavior exists without the advertised integration.
- **Missing:** no implementation found.
- **Unverified:** integration exists, but live-service or physical-device evidence is still required.

P1 = daily-use blocker/high priority; P2 = significant limitation; P3 = refinement. No confirmed P0 credential disclosure or account-destruction defect was established. Source-derived failure scenarios below were not reproduced against a live account. Historical device reports are identified separately from this audit's fresh checks.

Repository root: `D:\projects\custom_telegram\VectorMessenger_Starter\VectorMessenger_Starter`. Paths named below are relative to that root unless linked to their absolute location. `A` means `app/src/main/java/com/vector/messenger`; `C` means `core/src/main/kotlin/com/vector/core`.

## Architecture

| Layer | Actual implementation |
| --- | --- |
| Build | Existing `app` Android module and `core` Kotlin/JVM module. Existing Gradle 8.13, AGP 8.13.2 and Kotlin 2.3.20 configuration preserved. |
| Transport | One Hilt singleton [TdlibClientManager][transport], official JSON JNI bridge, one IO receive coroutine, correlated requests with 45-second timeouts. Adapters reuse the manager. |
| Authentication/profile | `DefaultAuthenticationRepository` and `DefaultProfileRepository` in core; Android adapters map documented authorization/user/file updates. StateFlow exposes UI state. |
| Messaging | [SessionChatRepository][messages] switches between explicitly signed-out demo data and authenticated TDLib data. One session-scoped event collector, incremental chat updates, ordered metadata, one bounded active message window. |
| Privacy | [SelectiveGhostRepository][ghost] separates encrypted local contact policies from Telegram ShowStatus rules. TDLib adapter, first-match rule evaluation, recovery journal and conflict/readback checks. |
| UI | Compose screens consume ViewModels and lifecycle-aware flows. Composables do not invoke JNI. Activity owns ViewModels; reducers use the Hilt process scope on Main.immediate. IO boundaries cover native receive/request, key vault initialization, file decode and MediaStore reads. |
| Navigation | [ModuleNavigation][navigation] keeps a bounded visit-history stack separate from per-module Navigation Compose controllers. Saveable state holders retain module UI state. Search overlays Chats and does not add module visits. |
| Input/motion | One module pointer owner plus eligibility/exclusion geometry and pure deterministic recognizer. Search owns a separate, mutually excluded recognizer and nested scrolling. Motion specifications are centralized. |
| Background | Process-owned coroutines only. No notification delivery, push registration/receiver, foreground service or durable background synchronization implementation found. |

This architecture is worth retaining. Its principal weaknesses are lifecycle/error recovery and incomplete capabilities, not a need for another TDLib client or a rewrite.

## Feature-status matrix

Statuses cover the precise capability named in each row. A tested core policy and an unverified live integration intentionally have separate rows.

| Feature | Status | Evidence | Problem / remaining validation | Priority |
| --- | --- | --- | --- | --- |
| Phone/code/password authorization state machine | Implemented | `C/auth/DefaultAuthenticationRepository.kt::submit`; 7 authentication JVM tests | Serialized submissions, validation and late-result protection covered; service delivery separate below | P2 |
| Phone login, codes, two-step verification against Telegram | Unverified | `A/telegram/TdlibAuthMapping.kt::toRequest`, `mapAuthorization`; `AuthenticationScreen.kt` | No live login performed in this audit | P1 |
| QR and email authorization | Unverified | Same mapping and authentication UI; QR token rendering | Telegram/device end-to-end test outstanding | P2 |
| Registration, password recovery, purchase-required login | Partial | `mapAuthorization` explicitly reports unsupported registration/purchase; no recovery UI | Requires official-client fallback; no bypass implementation | P2 |
| Encrypted session restoration | Unverified | [Transport][transport] initialization; `TdlibKeyVault.loadOrCreate` | Persistent database/key architecture exists; kill/relaunch and key-loss recovery not exercised here | P1 |
| Ordinary connectivity/reconnection | Unverified | `updateConnectionState`, TDLib-managed networking | Airplane-mode, network handoff, long offline recovery not exercised | P1 |
| Recovery after receive-loop failure | Partial | `TdlibClientManager.start`, outer receive catch | F01: stale nonzero client ID prevents in-process restart | P1 |
| Logout state transitions | Implemented | Authentication and session repository JVM tests | Core clears session data on auth changes; native cleanup is a separate live check | P1 |
| Native logout/key cleanup | Unverified | Transport waits for Closed and confirmed logout before vault cleanup | Confirm live logout, subsequent relogin and cleanup on device | P1 |
| Profile mapping/update state | Implemented | `DefaultProfileRepository`; 8 profile JVM tests | Name/username/photo absence, revisions, connection and logout covered | P2 |
| Real account text/photo display | Unverified | `getMe`, `updateUser`, `downloadFile`, `updateFile`; `ProfileScreen.kt` | No real-account/photo service verification in this audit | P2 |
| Real chat loading/synchronization | Partial | `TdlibMessagingClient.loadChats`; [messages][messages] reducers | Real main/archive requests and incremental updates exist; integration/error recovery incomplete | P1 |
| Chat ordering, pin/archive/unread reducers | Implemented | `MessagingRepositoryTest.positionsDriveOrderPinArchiveRemovalAndUnreadIsServerOwned` | Source uses TDLib positions/read counts; live server mutations unverified | P1 |
| Pin/archive server actions | Unverified | `toggleChatIsPinned`, `addChatToList` | No live cross-client confirmation in this audit | P2 |
| Chat avatars | Unverified | `downloadAvatar`, updateFile mapping, `TelegramAvatar.kt` | Async private-file pipeline exists; real loading/error/retry needs device validation | P2 |
| Message history/pagination | Partial | `loadOlder`, 50-message pages, 400-message window; messaging JVM tests | F02 content-update race; F07 reopening discards prior loaded window | P1 |
| Incoming/new/send-success/delete updates | Implemented | Typed `ChatUpdate` reducers; messaging JVM tests | Core behavior covered; not proof of live delivery | P1 |
| Content edits overlapping history | Partial | `reduce(ChatUpdate.Content)`, `loadOlder` | F02 can drop a not-yet-loaded edited message | P1 |
| Text send/validation/reply request | Partial | `sendText`, `SessionChatRepository.send`; sending-state tests | Native state mapping exists; F03 uncertain outcomes lack durable reconciliation | P1 |
| Actual send/receive between Telegram accounts | Unverified | Existing official sendMessage/getChatHistory integration | No messages sent during this audit | P1 |
| Sending/sent/read/failed indicators | Partial | Message mapping, ReadOutbox reducer, resend tests | States derive from TDLib; Sent does not establish recipient-device delivery; uncertain state unresolved | P1 |
| Failed-message retry | Partial | `retry`, `resendMessages`, canRetry/retryAt checks | F08 missing active-chat identity guard; real retry outcomes unverified | P2 |
| Visible-message read acknowledgments | Implemented | `LifecycleStartEffect`, visibility observer, `viewMessages`; read-path JVM tests | Normal reporting is intentional; server observation still needed | P1 |
| Groups/channels | Partial | Basic/supergroup kind mapping, channel role permissions | No topic/thread composition; no basic-group member-status mapping; broad Telegram parity absent | P2 |
| Telegram chat folders | Partial | `ChatFilter` All/Groups/Channels/Archived UI | Local categories, not synchronized custom Telegram folders | P2 |
| Contacts and New chat | Partial | `ContactsScreen`, mapping `isContact = kind == Contact` | F05 lists loaded private conversations, not the Telegram address book; cannot start arbitrary contact chat | P1 |
| Create group/channel | Placeholder | Search creation dialog explicitly says unsupported | UI entry only | P2 |
| Reactions | Placeholder | Demo-only action row; live `toggleReaction` does nothing | No live reaction fetch/send API wired; live control correctly withheld | P2 |
| Attachments/media/voice/secret chats | Missing | Live attachment action hidden; `send(...attachment)` Unsupported; use_secret_chats=false | Text previews are not media playback/download/upload; no recorder or secret-chat flow | P1 |
| Pull-down Search opening | Implemented | [SearchGestures][pull] nested scroll; search JVM tests | Requires list start at top and valid release; physical first-attempt reliability not tested here | P2 |
| Search downward dismissal | Implemented | [SearchInput][search-input], pure recognizer tests | Header/top-results eligibility; scrolling/IME device regression outstanding | P2 |
| Fast Chat/Music switching | Implemented | `SearchGestureRecognizer`, `DefaultApp.switchSearch`; tests | Direction/distance/velocity/release logic covered; human device testing outstanding | P2 |
| Query locking / blocked feedback | Implemented | `searchLocked`, switch policy; Search navigation tests | Elastic tick/highlight implemented; no new physical feedback check | P2 |
| Search suppresses module gestures | Implemented | `searchOwnsInput`, moduleGestureEligibility and recognizer disabled state; tests | Includes opening/closing transition ownership | P2 |
| Search query/account isolation | Partial | [SearchViewModel][search-vm] SavedStateHandle and recents | F06 queries/recents are not reset or partitioned on account transitions | P2 |
| Real Telegram server search | Missing | [ChatSearchResults][search-ui] local title/message filtering only | F04 no searchChats/searchMessages/public search requests | P1 |
| Search messages/media/files/links | Partial | Same local filter over repository.messages | Normal conversation exit clears its sole message window, so Search commonly has no messages to search | P1 |
| Local music songs/albums/artists/playlists search | Partial | [MusicLibraryRepository][library] MediaStore queries; MusicSearchResults | Real read-only catalog, permission/provider handling; device/provider validation outstanding, no internal playback | P2 |
| Local recently added | Partial | Date-added sorting, last 50 matches | Real metadata path, not exercised against current device catalog | P3 |
| Recently played / Telegram music import | Placeholder | MusicSearchResults deliberately returns no matches with explanatory text | No playback tracking/import pipeline | P2 |
| Music main module / library sections | Placeholder | [MusicSectionScreen][music] | Navigable saved section, empty content; disconnected from real Search catalog | P2 |
| In-app playback / durable offline library | Missing | Song result launches external ACTION_VIEW | No player/service/queue/import/storage-management implementation | P2 |
| Downloads | Placeholder | `MusicScreen.kt::DownloadsScreen` | Empty-state text references an earlier phase; no download manager | P2 |
| Ghost real contact selection | Implemented | [Ghost][ghost] and `TdlibGhostClient.contacts`; selection/account tests | Uses getContacts/getUser, not demo chats; live lookup remains unverified | P2 |
| Ghost encrypted account-bound preferences | Unverified | `SecureGhostStore` AES-GCM, AAD, AtomicFile; repository fake-store tests | JVM persistence contract covered; Keystore/restart behavior requires Android tests not run here | P1 |
| Ghost local typing policy | Implemented | Send-boundary action policy + Ghost JVM tests | No normal composer typing broadcaster exists, so policy alone proves no visible live improvement | P2 |
| Ghost recording/upload-action policy | Implemented | Known voice/video recording/upload action filtering; Ghost tests | Does not block actual file/message upload; no recorder exists to exercise | P2 |
| Ghost selective Last Seen/Online | Unverified | Rules merge/evaluation tests; official get/set ShowStatus APIs | Live visibility, server normalization and concurrent-client behavior require second account | P1 |
| Ghost external changes/server errors | Implemented | Updates, revision checks, readback, journal; 17 Ghost JVM tests | No transactional compare-and-set API; cannot eliminate concurrent edit window | P1 |
| Ghost hide all read receipts / universal invisibility | Missing | UI marks unsupported; normal read path retained | Not a promised capability; cannot represent ShowStatus restrictions as universal invisibility | P2 |
| Module history/back stacks | Implemented | [ModuleNavigation][navigation]; 10 navigation JVM tests | Bounded 32 visits, no consecutive duplicate, back pops history; message-window continuity separate | P2 |
| Right/left swipe-hold engine | Implemented | [Gesture controller][gestures], deterministic recognizer; 5 engine JVM tests | Release-only action, one hold confirmation, exclusions, cancellation; no fresh physical reliability claim | P2 |
| System Back / keyboard Back / accessibility | Partial | DefaultApp.BackHandler, IME query, explicit module/Search buttons, exploration exclusion | Logic exists; predictive Back/TalkBack/OEM interaction not run this audit | P2 |
| Appearance persistence/immediate policy changes | Implemented | AppearanceStore private prefs, normalization; 3 settings JVM tests | Android restart persistence tests available but not rerun here | P2 |
| Motion preferences/system overrides | Implemented | VectorMotionSpec, accessibleAppearance; 5 motion JVM tests | No new release profiling or physical reduced-motion review | P3 |
| Notifications | Missing | Manifest and SettingsScreen explicitly state absence | No channels, runtime notification permission, notifications or routing | P1 |
| Push/background synchronization after process death | Missing | Activity-triggered startup, process coroutine scope only | TDLib being alive in-process is not durable background delivery | P1 |
| Release performance/startup/battery readiness | Unverified | Historical motion report; no current profiler session | No release Macrobenchmark/Perfetto, cold-start, heap or battery evidence | P2 |

## Prioritized defects and exact ownership

### F01 — P1: dead receive loop cannot recover in process

**Files/functions:** [TdlibClientManager.start, lines 70–144][transport]. The receiver's outer exception handler publishes SetupRequired and exits. It does not reset clientId or complete/cancel outstanding requests. A subsequent start returns at the nonzero clientId check even though the receiver is dead. Pending requests wait for their own timeout. The UI accurately asks for restart, but this is insufficient daily-use recovery. This concerns receiver failure, not ordinary network loss managed inside TDLib. Add fault-injection coverage for receiver death, pending completion, safe close/recreate, and preserved session restoration before changing this lifecycle.

### F02 — P1: content update can make a history message disappear

**Files/functions:** [SessionChatRepository.reduce, line 146, and loadOlder, line 205][messages]; `A/telegram/TdlibMessagingMapping.kt::mapChatUpdate` maps updateMessageContent to Content.

Sequence: start history request; receive Content for message X before X is in window; reducer increments versions[X] but cannot patch a missing entry; page returns X; loadOlder skips it because its version is newer than startedAt. Unlike Upsert, Content never inserts X. The message is omitted until another fetch/reopen. The existing `MessagingRepositoryTest.contentAndDeletionUpdatesWinOverInflightHistory` at line 104 emits **Upsert**, not Content, so its passing result does not cover this case. Add a true Content-before-page regression and a bounded patch/refetch strategy. Finding is source-derived, not a newly executed failing test.

### F03 — P1: unknown send outcomes have no durable reconciliation

**Files/functions:** `SessionChatRepository.send`, line 226; `A/telegram/TdlibMessagingClient.kt::sendText`, line 33; `A/feature/ChatsViewModel.kt::send`; `ConversationScreen`, line 140 onward.

The code correctly returns Uncertain on a network/timeout path and does not invent Delivered. However, the app has no durable app-level pending operation identity/reconciliation workflow. The user retains the draft and may send it again after the first operation was accepted but its response was lost. In-flight duplicate guards do not settle that outcome. Define explicit pending/unknown UX and reconcile with TDLib's pending/outgoing states across relaunch before claiming reliable resend. Do not implement Telegram transport/encryption independently.

### F04 — P1: Search cannot provide advertised account-wide message results

**Files/functions:** [SearchViewModel, lines 20–22][search-vm]; [ChatSearchResults, lines 97–139][search-ui]; `ConversationScreen::LifecycleStartEffect`, line 71; `SessionChatRepository.closeConversation`, line 200.

Search filters chat titles and the single active message window. Closing/leaving the conversation clears that window, while Search is shown over the Chats root. Thus even previously opened real conversations normally supply no messages to Search. No Telegram search request is made. A result click opens only a chat, not the matched message. Keep the limited-source label until proper paged TDLib search/result navigation exists.

### F05 — P1: Contacts/New chat use private chats as contacts

**Files/functions:** `A/telegram/TdlibMessagingMapping.kt::mapTelegramChat`, line 101; `A/navigation/DefaultApp.kt::VectorNavigationHost`, Contacts branch; `A/feature/ChatScreens.kt::ContactsScreen`, line 235; New chat dialog.

The isContact flag denotes private chat kind, not user.is_contact. People with a loaded private conversation can appear regardless of address-book membership; real contacts without a loaded conversation cannot be selected. Ghost has a proper getContacts/getUser adapter, but main Contacts does not reuse a contact domain source and cannot createPrivateChat. This is a functionality defect, not fabricated authenticated contact data.

### F06 — P2: Search input/history survives account transitions

**File/function:** [SearchViewModel][search-vm], constructor and rememberQuery. Chat query, music query and recent strings are activity/ViewModel/save-state scoped, with no auth/session observer. Reusing the activity after logout/relogin retains the prior account's search inputs/recents. Chats/messages themselves are reset by the repository. Scope account-specific Search state to the authenticated account or clear it explicitly on transition. No secret values were inspected; this is a source-proven lifecycle gap, not evidence that another account's chats are returned.

### F07 — P2: saved conversation position outlives its loaded window

**Files/functions:** `ConversationScreen::LifecycleStartEffect`, line 71; `SessionChatRepository.openConversation/closeConversation`, lines 192/200; module saveable holders in DefaultApp.

On stop/dispose, messages and pagination window are discarded. Reentering fetches newest history, while Compose may restore an older list position/previousLastId. The same visual index no longer necessarily identifies the message the user left. This also affects module switching and background/foreground, not only opening another conversation. Define bounded per-chat anchor restoration through TDLib rather than assuming a saveable list index restores content. Physical reproduction remains outstanding.

### F08 — P2: retry identity is not checked against the requested chat

**File/function:** `SessionChatRepository.retry`, line 244. It reads window[messageId] but does not check active == chatId or message.chatId == chatId before resend. The normal UI supplies the right chat; a stale callback can pair a chat-local message ID from one active window with another chat argument. Add explicit identity/session guards and a delayed-callback regression. No wrong-chat resend was attempted against Telegram.

### F09 — P2: restriction removal does not restore active history

**File/function:** `SessionChatRepository.reduce(ChatUpdate.Access)`, lines 162–174. Restriction cancels history, empties messages and sets hasMore=false. The later unrestricted update does not reset that state or refetch; loadOlder immediately returns when hasMore=false. Reopening can recover. Add restrict→unrestrict coverage and an explicit restoration path.

### F10 — P1 capability gap: notifications/background delivery absent

**Files/functions:** `app/src/main/AndroidManifest.xml`; `A/MainActivity.kt::onCreate`; `A/feature/SettingsScreens.kt::SettingsScreen`; `A/telegram/TelegramModule.kt::telegramScope`.

There is no notification/push implementation or process-death recovery service. A process-owned receiver can continue while Android keeps the process alive, but delivery after it is killed is not provided by this architecture. The existing preview control explicitly says notifications are not implemented and is a SavedStateHandle preference only. Do not present it as a working notification privacy setting.

### F11 — P2 capability gap: media/library/download surfaces incomplete

**Files/functions:** [MusicSectionScreen/DownloadsScreen][music]; [MusicSearchResults][search-ui], line 151; [MusicLibraryRepository.readLibrary][library]; live attachment guard in ConversationScreen.

Search reads real MediaStore songs/collections and delegates opening externally. The main library remains empty. Recently played/Telegram import are deliberate empty states. No audio engine, durable import, media-message viewer/uploader or download manager exists. No claim of an in-app music system is supported.

## Performance and lifecycle assessment

| Observation | Evidence and confidence | Action / limit |
| --- | --- | --- |
| Native I/O and storage initialization are off Main | `start`, receive and initialize use Dispatchers.IO; vault is called from initialize | No source evidence for claiming native receive/key creation blocks Main |
| One receive path can experience subscriber backpressure | profileEvents/ghostEvents use suspending emit with 64-item extra buffers inside receive loop; `getMe` completes after publishCurrentUser | Concrete coupling, not a measured deadlock. A sufficiently slow collector/burst can delay unrelated request completion. Measure and isolate dispatch without dropping correctness-critical updates |
| Messaging queue has no bound | Channel.UNLIMITED in transport, independent of 400-message UI window | Burst/backlog memory risk is structural; no OOM or heap leak measured. Define bounded/coalesced processing/backpressure strategy and stress test |
| Reducers run on Main | TelegramModule process scope; sort/projection/filter of loaded records | Coalesced chat publication/yield and order-dirty flag reduce repeated work. CPU/frame cost not measured; do not move mutable reducers across dispatchers without serialization |
| Histories and update tombstones are bounded | WINDOW=400 and bounded version/replacement tracking | Positive memory protection; chat metadata and receive queue are separate unbounded inventories |
| Lazy lists use stable keys | Chat/message/search IDs; contentType on chat/message rows | Retain. A stable key does not fix F07 dataset/anchor restoration |
| Images decode asynchronously with sampling | TelegramAvatar and ProfileAvatar use IO decoding | No unbounded bitmap cache added; actual image churn/heap usage unmeasured |
| Music catalog queries can be expensive | Full MediaStore catalog plus per-playlist member query; observer refresh uses mapLatest(IO) | Real O(songs + playlist membership) work, off Main; cancellation of blocking provider queries is not instrumented. Avoid speculative caching |
| Event collector lifecycle | Session collectLatest cancels child collectors; viewModelScope/Compose scopes own UI jobs; callbackFlow unregisters MediaStore observer | No duplicate native clients/receive loops or proven leaking coroutine found. This is source inspection, not a heap/leak-detector result |
| Motion evidence is historical | `docs/MOTION_PERFORMANCE.md` and raw JSON artifacts | Samsung debug replay previously reduced applied-change batches: Chats pull 20→3, Search pull 19→0, filled press 27→6. These are not total recompositions or current-audit measurements |

Historical frame timings were mixed; module/Search replay had deadline overruns in both samples and is explicitly unsuitable for human-navigation FPS claims. No new jank, input latency, 120 Hz, cold-start, battery, release Macrobenchmark or Perfetto measurement was made. Existing reduced-motion/system-haptic overrides and lifecycle-scoped animation jobs are present. No permanent animation loop was found.

## Security findings

| Area | Finding | Remaining work |
| --- | --- | --- |
| Git credentials/artifacts | Tracked-file name scan found zero local credential/session/keystore/database candidates. Narrow credential-literal/private-key/token pattern scan found zero matching tracked files. Reachable Git history path scan for local credentials/keys/session databases found zero occurrences | This is not a full entropy/history-content secret scan or proof no secret ever existed in an unreachable commit/external artifact. No credential values printed |
| Developer API identity | telegram.properties and generated asset ignored; Gradle reads ignored local file/environment and validates without printing values | API ID/hash ship in generated APK asset and are extractable. Do not describe this as an APK secret vault. Account authorization is separate |
| Session key | Keystore AES-GCM wraps random 32-byte TDLib database key; noBackup AtomicFile; fails rather than replacing missing original key | Android key invalidation, restart/logout cleanup and corruption recovery require device tests; database encryption does not imply downloaded file bytes are encrypted |
| Ghost local policy | Separate Keystore key, account-bound AAD, hashed filenames, atomic writes, private noBackup directory | Local contact rules intentionally persist per account after logout; confirm retention policy/product expectations. Never delete managed server exceptions automatically on logout |
| Backups/components/network | allowBackup=false, cloud/device-transfer exclusions, cleartext=false; only launcher activity exported | MainActivity export is necessary for launcher. No custom exported provider/service/network listener found |
| Permissions | INTERNET; READ_MEDIA_AUDIO; READ_EXTERNAL_STORAGE capped at API 32 | Audio permission requested for local catalog. No broad storage, contacts, microphone, background location or unnecessary network-service permission found. Notification permission is absent alongside missing notifications |
| Logs/errors | Native logStreamEmpty and verbosity=0 before configuration; auth strings redacted; sanitized errors. Debug gesture logs contain only state/direction/threshold/reason/action; release diagnostics no-op | No production message/code/password logging found by source scan. Device/native crash output not captured in this audit |
| Authentication UI | Secure dialog flag; code/password inputs are not saveable, reset on step/submit; no password/code persistence API | JVM strings/JSON copies cannot be reliably wiped; no claim of zero memory residency. Auth/profile screenshot protection does not cover the entire messaging activity |
| Search state | F06 retains query/recents through account changes, some query state is saveable | Clear/partition at auth transitions; sensitive retention gap even though it is not a password store |
| TDLib provenance | Build verifies local native inventory/hashes against manifest and pinned official source commit `c8828d50f5eac2fc7089ce1089d7ce9095c61b5e`; build script retained | Verification passes for current local binaries; not a reproducible-build attestation or native security audit. A build with no binaries is permitted and reports setup unavailable at runtime |

No changes to Telegram cryptography, authentication data, live privacy rules or sessions were made during this audit.

## Ghost Mode guarantees and limits

The current working tree already contains the earlier Ghost repairs. They are part of this audit baseline, not new work performed here. Local typing and supported recording/action suppression are independent of server privacy intent. In the current application there is no regular composer typing sender or recorder; the filter is tested, but toggling it does not prove a new peer-visible effect. Only the Ghost action adapter sends sendChatAction; the shared manager gates every such outgoing request immediately before JNI. Actual media/message delivery APIs are not blocked.

Selective online rules use getUserPrivacySettingRules / setUserPrivacySettingRules with userPrivacySettingShowStatus and userPrivacySettingRuleRestrictUsers. Rules are merged with existing explicit exceptions, checked after readback, and observed through updateUserPrivacySettingRules. Unknown predicates can remain Unsupported; errors, Applying and conflicts are distinct from Enabled. The whole-setting API has no conditional version parameter, so read-check-write cannot eliminate the concurrent-client edit window. See [official setter documentation](https://core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1set_user_privacy_setting_rules.html).

Default still reports visible messages normally. force_read=false is not a receipt-hiding mode; it avoids forcing reads in a closed chat. [Official viewMessages documentation](https://core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1view_messages.html) describes viewing-related effects and the force_read parameter. There is no optional read-deferral implementation. Do not promise complete invisibility: Telegram documents temporary online visibility for certain interactions even with restricted last-seen visibility. [Telegram online FAQ](https://telegram.org/faq#q-who-can-see-me-39online-39). No two-account visibility test was conducted here.

## Build and automated test results

Executed against the current tree:

```powershell
.\gradlew.bat :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --max-workers=1 --console=plain
.\gradlew.bat :core:test :app:testDebugUnitTest --rerun-tasks --max-workers=1 --console=plain
```

- First command: **BUILD SUCCESSFUL**, 33 seconds, 67 actionable tasks, 12 executed/55 up-to-date. Includes requested assembleDebug. Lint: **0 errors, 12 warnings**, comprising target-SDK currency and dependency/tool update notices. No dependency upgrades made.
- Second command forces fresh unit execution rather than relying on cached task success: **BUILD SUCCESSFUL**, about 1 minute, all 38 actionable tasks executed.
- **83 JVM tests passed, 0 failures, 0 errors, 0 skipped**: core 49, app 34. No failing tests removed or edited. No audit-enabling source fix was necessary.

| Suite | Tests |
| --- | ---: |
| AuthenticationRepositoryTest | 7 |
| DemoRepositoryTest | 7 |
| MessagingRepositoryTest | 10 |
| ProfileRepositoryTest | 8 |
| SelectiveGhostTest | 17 |
| AppearanceSettingsTest | 3 |
| ChatsViewModelTest | 4 |
| GestureEngineTest | 5 |
| MotionSpecTest | 5 |
| SearchGestureTest | 3 |
| SearchNavigationTest | 4 |
| VectorNavigationTest | 10 |

Local ignored logs: `default-05-audit-checks.log`, `default-05-audit-unit-tests.log`. Machine-readable results: `core/build/test-results/test/TEST-*.xml`, `app/build/test-results/testDebugUnitTest/TEST-*.xml`; lint report: `app/build/reports/lint-results-debug.txt`. APK: `app/build/outputs/apk/debug/app-debug.apk`.

**Not run in this audit:** Android instrumentation/device tests, release build, Telegram authentication/message exchange, second-account privacy observation, actual MediaStore catalog interaction, process-death notification tests, predictive Back/TalkBack/OEM gestures, or profiling. Android JSON-mapping/UI/Keystore tests exist under androidTest but are not counted as executed above. Prior-stage device pass counts do not establish the current audit's physical coverage. First-attempt gestures remain a manual validation requirement. New source findings F01–F09 need focused regressions; green existing tests do not refute uncovered paths.

## Next five implementation milestones

| Order | Milestone and scope | Complexity | Dependencies | Exit criteria |
| --- | --- | --- | --- | --- |
| 1 | Recovery and correctness blockers: F01/F02, restriction recovery, session/chat identity guards, F06 account-state isolation | High; approximately 1–2 engineer-weeks | Existing manager/reducer architecture; fake/fault-injectable transport seam | Actual Content-before-history, dead receiver, cancellation, stale callback, account transition and restrict→unrestrict regressions pass; no session/key replacement |
| 2 | Daily-use text messaging: F03 durable unknown/pending reconciliation, F07 anchor restoration, true Contacts/New chat, paged TDLib Search and message result targeting; basic group/topic permission accuracy | High; approximately 2–4 engineer-weeks | Milestone 1; official schema review, two test accounts | Offline/reconnect/relaunch send outcomes are explainable without blind duplicate sends; bidirectional text/reply/read/pin/archive tests; contacts without chats can be opened; account-wide Search works |
| 3 | Ghost accuracy release gate: live two-account status/read/action observation, external-client conflict/normalization and interrupted-apply checks; align every capability label with actual evidence | Medium–high; approximately 1–2 engineer-weeks plus observation time | Milestones 1–2, consented test accounts; current merge/journal implementation | Selected/nonselected behavior verified, unrelated rules retained, failures never show confirmed success; limitations remain explicit; no promise of total invisibility |
| 4 | Notifications and background synchronization: official TDLib notification/push integration, platform permissions/channels, secure previews, account-aware deep links and process-death lifecycle | High; approximately 2–4 engineer-weeks | Stable session/recovery and messaging; choose supported push strategy and infrastructure | Real incoming notifications across foreground/background/process death, permission denial, logout and network changes; no stale-account notification routing; battery/Doze/device coverage |
| 5 | Media/download/music functionality and performance gate: native message media download/upload/viewer, download lifecycle, durable imports, real module catalog, player/session/queue and playback history | Very high; approximately 3–6+ engineer-weeks | Milestones 1–2 and notification/lifecycle foundation; storage/content restriction design | Real media and catalog tests, protected-content handling, interruption/retry/storage cleanup, background playback correctness; release Macrobenchmark/Perfetto, startup/heap/battery baselines on representative devices |

Estimates are planning ranges for implementation plus automated coverage, not delivery commitments. Live Telegram/platform edge cases can extend them. Keep current design, one TDLib client and existing dependency versions; make focused changes with measurable acceptance criteria.

## Audit change record

Only this report was added for the audit. Existing modified Ghost production/test/documentation files and the untracked `docs/GHOST_MODE_AUDIT.md` were retained. Build output/logs are ignored. No feature or large refactor was implemented.

[transport]: D:/projects/custom_telegram/VectorMessenger_Starter/VectorMessenger_Starter/app/src/main/java/com/vector/messenger/telegram/TdlibClientManager.kt:70
[messages]: D:/projects/custom_telegram/VectorMessenger_Starter/VectorMessenger_Starter/core/src/main/kotlin/com/vector/core/messaging/SessionChatRepository.kt:146
[ghost]: D:/projects/custom_telegram/VectorMessenger_Starter/VectorMessenger_Starter/core/src/main/kotlin/com/vector/core/ghost/SelectiveGhostRepository.kt:9
[navigation]: D:/projects/custom_telegram/VectorMessenger_Starter/VectorMessenger_Starter/app/src/main/java/com/vector/messenger/navigation/ModuleNavigation.kt:14
[gestures]: D:/projects/custom_telegram/VectorMessenger_Starter/VectorMessenger_Starter/app/src/main/java/com/vector/messenger/navigation/gesture/VectorGestureController.kt:105
[pull]: D:/projects/custom_telegram/VectorMessenger_Starter/VectorMessenger_Starter/app/src/main/java/com/vector/messenger/navigation/gesture/SearchGestures.kt:33
[search-input]: D:/projects/custom_telegram/VectorMessenger_Starter/VectorMessenger_Starter/app/src/main/java/com/vector/messenger/feature/search/SearchInput.kt:56
[search-vm]: D:/projects/custom_telegram/VectorMessenger_Starter/VectorMessenger_Starter/app/src/main/java/com/vector/messenger/feature/search/SearchViewModel.kt:15
[search-ui]: D:/projects/custom_telegram/VectorMessenger_Starter/VectorMessenger_Starter/app/src/main/java/com/vector/messenger/feature/search/SearchWorkspace.kt:97
[library]: D:/projects/custom_telegram/VectorMessenger_Starter/VectorMessenger_Starter/app/src/main/java/com/vector/messenger/feature/search/MusicLibraryRepository.kt:38
[music]: D:/projects/custom_telegram/VectorMessenger_Starter/VectorMessenger_Starter/app/src/main/java/com/vector/messenger/feature/MusicScreen.kt:40
