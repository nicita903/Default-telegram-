# TDLib session lifecycle investigation — 2026-11-25

## Source findings

- `MainActivity.onStop` changes notification/sound visibility only. TDLib and the authenticated repositories are application-scoped singletons. No Activity stop/resume path calls TDLib close/destroy or creates a replacement client. `closeChat` is conversation-visibility reporting, not client shutdown. Connection changes do not reset authorization or clear chat records.
- `ConversationScreen` intentionally leaves its conversation on Lifecycle STOP, but `SessionChatRepository.closeConversation` cleared all messages. Resume cleared them again and requested `getChatHistory(only_local=false)`. This was a verified application-side cache/loading defect. It can make returning conversations wait on history/network work despite an existing authorized client.
- The sole blocking native receive loop called multiple suspending SharedFlow.emit operations before receiving the next response/update. A slow subscriber or a sufficiently large burst could stall response completion, connection-state delivery and every other feature behind it. This is a verified backpressure path, not proof of the complete runtime cause of every previously reported delay. The request timeout is 45 seconds, so a feature waiting for a response behind its own backpressure could prolong a stall.
- Default-network callbacks queried synchronous active-network/capability snapshots, including during onAvailable/onLost. Missing/racing capabilities could become networkTypeNone. The new implementation consumes the callback's capabilities, tracks the actual current network, ignores loss of a replaced default and preserves TDLib's probing on unknown/unvalidated VPN/proxy networks. Real UID/network blocking can still report None. Activity visibility never becomes a network hint.

## Focused changes

- Per-feature ordered, epoch-filtered relays decouple SharedFlow subscribers from native receiving. No critical updates are intentionally dropped and no second TDLib receive loop/client is added. RPC responses and connection changes remain on the native receive path. FIFO mailboxes are transient handoffs, not a persistent cache; pathological consumer backlog still requires diagnosis rather than an unlimited-memory guarantee.
- Keep the existing bounded 400-message window for the last conversation when it is not visible. Continue applying relevant edits, reactions, deletions and incoming updates, while visibility/read reporting and foreground arrival feedback remain disabled. Reopening that conversation does not clear/refetch it. A different conversation, logout/account session change, content restriction or explicit Latest action retains the appropriate clearing behavior.
- On a genuinely empty history window, request documented TDLib only_local history first and publish it before online-capable history. Existing encrypted TDLib storage remains the only disk message cache. A complete local page avoids an immediate duplicate page request. Cold-process cache availability still depends on what TDLib has stored/loaded; no custom plaintext chat cache is introduced.
- Fixed debug-only monotonic markers cover Application/Activity lifecycle, PID, client creation/close, authorization, transport hints, connection transitions, cached data, frame-bound populated UI and applied new-message updates. Relay waits over 100 ms are measured without message/contact/credential data. UI readiness and actual TDLib Ready remain separate. No client-restart loop, polling, wake lock, idle foreground service, dependency/version or native call change was introduced.

## Measurement interpretation

Return-to-UI is measured from the Application Activity-start foreground marker to a populated Chats/conversation frame marker. This is instrumented frame readiness, not screenshot verification or guaranteed frame-rate/scroll performance. An already-Ready return has no new Ready transition; it must not be reported as a measured reconnect. Process recreation is distinguished by PID/ClientCreated events. No comparable instrumented pre-change build was tested, so these data do not establish a quantified before/after speedup.

Device results are recorded after the authorized run. No messages were sent, calls placed, account reset or network setting changed. Screen timeout is temporarily extended only for the test and restored afterward.

## Push and remaining checks

FCM client configuration remains absent in the current build. Existing registerDevice/receiver-account mapping/processPushNotification and encrypted deduplicated work handoff were inspected; no receipt/delivery or new registration success is asserted. Keeping a socket alive is not reliable push delivery. Normal process death, Android cached-process suspension and explicit Force Stop remain distinct; Force Stop is not bypassed.

Live background message-notification delivery, Wi-Fi/mobile/VPN switching, cold-process cache restoration, active voice-call continuity and long-term consumer-backlog/memory behavior need separate checks. The authorized timing run alone cannot validate them. Calls/JNI/encryption/foreground call service and notification behavior were not redesigned.
