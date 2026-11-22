# Real Telegram chats and text messaging

This document records the text-messaging stage. The subsequent [media/download implementation](TELEGRAM_MEDIA.md) adds attachment metadata, viewing and file transfers. [Voice/audio integration](VOICE_AUDIO.md) subsequently adds native voice-message sending and shared playback. Those reports supersede the corresponding omissions below.

## Data source and architecture

`SessionChatRepository` implements the existing `ChatRepository`. When authorization is Ready, it exposes only Telegram data; when signed out, it exposes the separate in-memory `DemoChatRepository` under the existing explicit demo labels. A session generation resets histories, drafts, selected chat state and in-flight request eligibility. Late send/history results cannot populate another session.

The existing singleton `TdlibClientManager` still owns the only JNI client, receive loop, request correlation, storage keys and authorization lifecycle. `TdlibMessagingClient` is a typed adapter, not another TDLib client. A single session-tagged update queue retains startup updates before subscription and discards prior-session events at authorization changes. The repository has one incremental-update consumer per active session. All JNI sends/receives run on IO; reducers run on the existing serialized Main scope.

`ChatsViewModel` and `SearchViewModel` retain their existing repository dependencies. Compose talks to ViewModels, never JNI. Module stacks, Search ownership and gesture recognizers are unchanged. Conversation routes now accept negative IDs, which are used by Telegram groups/channels.

## Implemented behavior

- Main and Archive chat lists load in pages of 100 through TDLib. Initial cached updates are retained. Scrolling or the compact Load more action requests subsequent pages; no periodic reload is used.
- Incremental snapshots, positions, draft-position changes, title/photo changes, last-message previews, unread counts, read-outbox markers, permissions and content protections update the cache.
- TDLib position order and chat-ID tie-breaking determine ordering. Android JSON string-to-Double conversion rounded a near-limit order in a device test; order strings now use exact Long parsing. Only position changes invalidate the order cache. Other metadata changes reuse it; publication coalesces receive bursts using a yield, without a polling timer.
- Groups/channels are distinguished. Known sender names appear on ungrouped incoming group bubbles, and grouping also checks sender identity. Channel posting follows available permissions/administrator status and TDLib's final validation.
- Small circular chat avatars download through TDLib when rows become visible. Completed paths must be inside the existing private TDLib files directory; decoding is asynchronous and sampled to bounded dimensions. Missing photos retain initials.
- History uses pages of 50, deduplicates inclusive cursors and retains at most 400 messages for the active conversation. Short pages with progress are not treated as the end. Updates and deletion tombstones win over older in-flight pages. The Latest messages action restores the newest window after older paging; distant live messages are not stitched into an evicted older window.
- Incoming messages, content edits, permanent deletions, send replacements and outbox-read updates apply incrementally. Cache eviction is not treated as a message deletion. Reopening a conversation loads its current bounded history; no complete history is retained in application memory.
- Plain-text sending preserves optional same-chat replies. Empty, oversized, unavailable-chat, forbidden and unsupported-attachment inputs are rejected. Only actual TDLib message states produce Sending, Failed, Sent-to-Telegram and Read indicators. A send acknowledgement/quick acknowledgement is not treated as delivery.
- A delayed response clears only the submitted draft/context, preserving edits made while the request was pending. New outgoing messages scroll into view even with the keyboard open; older-page eviction does not trigger that scroll.
- Failed messages retain their native state. Retry is offered only when TDLib permits the supported resend path and its waiting period has elapsed. Sender changes, replacement quotes and paid-message retries are not silently performed. An uncertain network outcome retains the draft and asks the user to check the conversation before sending again; it never triggers automatic resend.
- `openChat`/`closeChat` follow the conversation lifecycle. Read reporting uses visible loaded message IDs, deduplicates them and uses `force_read=false`. Unread counts change only through TDLib updates, not optimistically on a row tap.
- Search keeps its existing gestures and scopes. Its chat/message results use loaded real data while signed in and are labeled accordingly; this phase does not add server-wide message search. The compact blue New chat action selects from existing private Telegram conversations when signed in.

## Security and boundaries

No cryptography, native binary, credential, key-vault, backup or manifest changes were made. TDLib keeps control of networking and durable storage. No message/session/credential logging was added; displayed errors are fixed application text rather than arbitrary TDLib error strings. Core chat, message and attachment diagnostic strings redact their contents.

Protected chats disable text selection/copy actions. Peer/message restrictions hide content; sensitive, self-destructing, ephemeral and unsupported Secret Chat content is conservatively withheld. Spoiler text/captions are masked rather than rendered as exposed plaintext. Restricted peers do not trigger avatar downloads. No media export, forwarding, external media downloader, paid sending or restriction bypass was added.

This is **basic text messaging**. Media messages have type/caption previews; real attachment transfer, media playback, reactions, creating chats/groups/channels, Secret Chats, topic-specific composing, paid messages and full remote Search are outside this phase. Demo-only attachment/reaction interactions remain available in the labeled demonstration source. Music, Ghost Mode and other unrelated modules are unchanged.

## Official references

Implementation uses the existing reviewed TDLib source pin and official documentation:

- [TDLib update model](https://core.telegram.org/tdlib/getting-started)
- [Pinned TDLib API schema](https://github.com/tdlib/td/blob/c8828d50f5eac2fc7089ce1089d7ce9095c61b5e/td/generate/scheme/td_api.tl) for list positions, read reporting, permissions and resend constraints
- [getChatHistory](https://core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1get_chat_history.html)
- [sendMessage](https://core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1send_message.html)
- [updateMessageSendSucceeded](https://core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1update_message_send_succeeded.html)

## Changed files in this phase

Core:

- `Models.kt`, `Repositories.kt`: live message/chat fields and backward-compatible lifecycle/pagination methods.
- `messaging/TelegramMessaging.kt`, `messaging/SessionChatRepository.kt`: typed transport contract, incremental session repository (new).
- `MessagingRepositoryTest.kt`: fake-client repository regressions (new).

Android production, under `app/src/main/java/com/vector/messenger/`:

- `telegram/TdlibClientManager.kt`: messaging event routing and authorized requests.
- `telegram/TdlibMessagingClient.kt`, `telegram/TdlibMessagingMapping.kt`: typed requests/mapping (new).
- `di/RepositoryModule.kt`: session-aware repository binding using the shared manager.
- `feature/ChatsViewModel.kt`, `feature/ChatScreens.kt`, `feature/ConversationScreen.kt`: live data, pagination, receipts, sending/retry and session resets.
- `design/TelegramAvatar.kt` (new), `design/DefaultHeader.kt`: existing circular-avatar presentation connected to local TDLib images.
- `navigation/DefaultApp.kt`, `navigation/ModuleNavigation.kt`: source/connection labels, header photo and valid signed chat IDs; navigation behavior unchanged.
- `feature/search/SearchViewModel.kt`, `feature/search/SearchWorkspace.kt`: accurate source labels for loaded Search data.
- `feature/AuthenticationScreen.kt`, `feature/SettingsScreens.kt`: remove obsolete statements that signed-in chats are always demo data.

Tests: `TelegramMessagingMappingTest.kt`, `RealMessagingUiTest.kt` (new); `ChatsViewModelTest.kt` covers signed routes. `TerminalTestSupport.kt` waits for authentication initialization and skips demo-only UI tests if a live account is active, preventing those tests from sending through a real account. Previous stages' working-tree changes are preserved.

## Verification

Final command (the Samsung transport was selected with `ANDROID_SERIAL`):

```powershell
.\gradlew.bat :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:connectedDebugAndroidTest :app:compileReleaseKotlin --max-workers=1 --console=plain
```

**BUILD SUCCESSFUL** in 2m 35s. **65 JVM tests and 69 Samsung SM-S9310 / Android 16 tests passed**, zero failures or skips. Debug assembly and release Kotlin compilation passed. Lint: zero errors, 12 existing dependency/tool notices. `git diff --check` passed. Output is in `messaging-final-checks.log`. Gradle configuration, manifest, authentication repository, auth mapping and key-vault files have no changes in this phase.

Automated messaging tests use fake clients and synthetic data only. They cover ordering, unread updates, positions, pagination bounds/cursor overlap, stale history, native send states, same-ID failure, resend eligibility, read updates, session isolation, restrictions, missing photos and negative IDs. Android tests additionally render the existing UI with a fake messaging client and verify delayed-draft preservation and keyboard-visible send feedback. Existing authentication, profile, Appearance, module/gesture and Search regression tests also passed. No real Telegram messages are sent by these tests.

Device tests exposed and led to correction of exact int64 parsing and sent-bubble visibility with the keyboard open. A delayed-send fixture verifies draft preservation. Another run failed while the Samsung was locked; those failures are not counted as passing verification. One earlier Search startup test also failed to find the Chats list; it passed on rerun. The final clean run passed all tests. The phone's original 300000 ms screen timeout was restored and temporary test instrumentation was removed.

**No live-account message delivery was tested.** A physical-device fake-client test demonstrates UI/repository behavior, not Telegram service delivery or performance guarantees.

Remaining manual service checks:

1. Sign in using the existing Account flow and [TDLib setup](TDLIB_AUTH.md). Verify only your real chats appear, including archive/group/channel folders and photos.
2. Scroll through multiple list/history pages. Compare order, previews, pin/archive changes and unread counts with an official Telegram client.
3. Exchange text with a consenting test contact. Verify pending → sent → read transitions in both clients, edit/delete updates and permitted retry after a controlled connection failure. Check an unknown send outcome before resending.
4. Test offline cached history, reconnection and supported channel posting permissions.
5. Switch modules/Search, background/restore the app and log out/in. Verify no previous session's messages, draft, avatar or demo conversation appears as real account data.
